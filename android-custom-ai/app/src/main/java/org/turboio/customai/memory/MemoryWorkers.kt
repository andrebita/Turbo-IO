package org.turboio.customai.memory

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.turboio.customai.R
import org.turboio.customai.TurboApp
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.insights.DailyInsightWorker
import org.turboio.customai.journal.Days
import org.turboio.customai.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Notifications of the memory (one channel per kind so each can be silenced in Android settings). */
object MemoryNotifier {
    /** A button on the notification acting on one item without opening the app. */
    data class Action(val label: String, val action: String, val itemId: Long)

    fun show(context: Context, channel: String, channelName: String, id: Int, title: String, text: String,
             importance: Int = NotificationManager.IMPORTANCE_DEFAULT, actions: List<Action> = emptyList()) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, channelName, importance))
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "journal")
            .putExtra(MainActivity.EXTRA_SECTION, if (channel == "memory_weekly") "week" else "pending"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle(title).setContentText(text.take(120)).setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1200)))
            .setContentIntent(open).setAutoCancel(true)
        actions.forEachIndexed { i, a ->
            val intent = Intent(context, ItemActionReceiver::class.java).setAction(a.action)
                .putExtra(ItemActionReceiver.EXTRA_ITEM, a.itemId).putExtra(ItemActionReceiver.EXTRA_NOTIFICATION, id)
            builder.addAction(0, a.label, PendingIntent.getBroadcast(context, id * 10 + i, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        manager.notify(id, builder.build())
    }

    /** Short text on the lenses when the glasses are connected and free. */
    fun lens(app: TurboApp, text: String) {
        if (app.session.isReady && !app.display.active) app.display.show(text.take(270), app.settings.displaySettings())
    }
}

/** Analyses closed conversations (today and yesterday), then notifies new items and refreshes reminders. */
class MemoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TurboApp
        val s = app.settings.memorySettings()
        if (!s.enabled || !app.settings.hasApiKey(app.settings.aiConfig().provider)) return Result.success()
        val now = System.currentTimeMillis()
        val today = Days.of(now)
        return try {
            val run = app.memory.process(listOf(Days.shift(today, -1), today), now)
            if (run.newItems.isNotEmpty()) {
                val q = app.memoryQueries
                val text = run.newItems.take(5).joinToString("\n") { "• " + q.line(it, now) }
                val single = run.newItems.singleOrNull()
                if (s.notifyNew) MemoryNotifier.show(app, "memory_new", "Novas pendências", 7400,
                    if (single != null) "Nova pendência" else "${run.newItems.size} novas pendências", text,
                    actions = if (single == null) emptyList() else listOf(MemoryNotifier.Action("Feita", ItemActionReceiver.DONE, single.id),
                        MemoryNotifier.Action("Descartar", ItemActionReceiver.DISMISS, single.id)))
                if (s.lensNew) MemoryNotifier.lens(app, "Anotado: " + run.newItems.take(2).joinToString("; ") { q.line(it, now) })
            }
            Reminders.sync(app)
            if (run.failed > 0 && runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: Exception) {
            DiagnosticLog.w("MEMORIA", "análise falhou: ${TurboApp.friendly(e)}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val ONCE = "memory-once"
        private const val PERIODIC = "memory-periodic"
        private val NET = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** After new journal content: runs once the conversation has had time to close (≈ 5 min). */
        fun kick(context: Context, delayMinutes: Long = 5) {
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MemoryWorker>().setInitialDelay(delayMinutes, TimeUnit.MINUTES).setConstraints(NET).build())
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MemoryWorker>(30, TimeUnit.MINUTES).setConstraints(NET).build())
        }
    }
}

/** One reminder per dated open item, [MemorySettings.reminderLeadMin] before the deadline. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TurboApp
        val id = inputData.getLong("item", -1)
        val item = app.memoryStore.item(id) ?: return Result.success()
        if (item.status != ItemStatus.OPEN || item.dueAt == null || item.remindedAt != null) return Result.success()
        val now = System.currentTimeMillis()
        if ((item.snoozeUntil ?: 0) > now + 60_000) return Result.success()      // snoozed again meanwhile
        val line = app.memoryQueries.line(item, now)
        MemoryNotifier.show(app, "memory_reminder", "Lembretes", reminderNotification(id), "Lembrete", line, NotificationManager.IMPORTANCE_HIGH,
            listOf(MemoryNotifier.Action("Feita", ItemActionReceiver.DONE, id), MemoryNotifier.Action("Adiar 1 h", ItemActionReceiver.SNOOZE, id)))
        MemoryNotifier.lens(app, "Lembrete: $line")
        app.memoryStore.updateItem(item.copy(remindedAt = now, snoozeUntil = null, updatedAt = item.updatedAt))
        DiagnosticLog.i("MEMORIA", "lembrete exibido (#$id)")
        return Result.success()
    }
}

fun reminderNotification(itemId: Long) = 8000 + (itemId % 1000).toInt()

/** Notification buttons: Feita / Descartar / Adiar 1 h, handled without opening the app. */
class ItemActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ITEM, -1)
        if (id < 0) return
        val app = context.applicationContext as TurboApp
        val pending = goAsync()
        Thread {
            try {
                val now = System.currentTimeMillis()
                val item = app.memoryStore.item(id)
                if (item != null && item.status == ItemStatus.OPEN) when (intent.action) {
                    DONE -> app.memoryStore.setStatus(id, ItemStatus.DONE, now, "marcada na notificação")
                    DISMISS -> app.memoryStore.setStatus(id, ItemStatus.DISMISSED, now)
                    SNOOZE -> {
                        val until = now + 3600_000L
                        app.memoryStore.updateItem(item.copy(remindedAt = null, snoozeUntil = until, updatedAt = item.updatedAt))
                        WorkManager.getInstance(app).enqueueUniqueWork("reminder-$id", ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(3600_000L, TimeUnit.MILLISECONDS).setInputData(workDataOf("item" to id)).build())
                    }
                }
                DiagnosticLog.i("MEMORIA", "notificação: ${intent.action?.substringAfterLast('.')} (#$id)")
                context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
            } finally { pending.finish() }
        }.start()
    }

    companion object {
        const val DONE = "org.turboio.customai.memory.DONE"
        const val DISMISS = "org.turboio.customai.memory.DISMISS"
        const val SNOOZE = "org.turboio.customai.memory.SNOOZE"
        const val EXTRA_ITEM = "item"
        const val EXTRA_NOTIFICATION = "notification"
    }
}

object Reminders {
    /** (Re)schedules reminders of every open dated item; cancelled ones are skipped by the worker itself. */
    fun sync(app: TurboApp) {
        val s = app.settings.memorySettings()
        val wm = WorkManager.getInstance(app)
        val now = System.currentTimeMillis()
        for (i in app.memoryStore.openItems()) {
            val due = i.dueAt ?: continue
            if (!s.reminders || i.remindedAt != null || due < now - 3600_000L) continue
            val snooze = i.snoozeUntil?.takeIf { it > now }
            if (snooze == null && due < now) continue
            val delay = snooze?.let { it - now } ?: maxOf(0L, due - s.reminderLeadMin * 60_000L - now)
            wm.enqueueUniqueWork("reminder-${i.id}", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(workDataOf("item" to i.id)).build())
        }
    }
}

/** Morning brief: overdue, today's and other open items. */
class MorningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TurboApp
        if (!app.settings.memorySettings().morning) return Result.success()
        val text = app.memoryQueries.morningBrief(System.currentTimeMillis()) ?: return Result.success()
        MemoryNotifier.show(app, "memory_morning", "Resumo da manhã", 7401, "Seu dia", text)
        MemoryNotifier.lens(app, text)
        return Result.success()
    }
}

/** Weekly review on the configured weekday (the job runs daily and acts on that day only). */
class WeeklyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TurboApp
        val s = app.settings.memorySettings()
        if (!s.weekly || LocalDateTime.now().dayOfWeek.value != s.weeklyDow) return Result.success()
        return try {
            val w = app.weeklyReview(force = true) ?: return Result.success()
            MemoryNotifier.show(app, "memory_weekly", "Revisão semanal", 7402, "Revisão da semana", w.text)
            Result.success()
        } catch (e: Exception) {
            DiagnosticLog.w("MEMORIA", "revisão semanal falhou: ${TurboApp.friendly(e)}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object MemorySchedule {
    fun all(context: Context) {
        val app = context.applicationContext as TurboApp
        val s = app.settings.memorySettings()
        val wm = WorkManager.getInstance(context)
        if (!s.enabled) { listOf("memory-periodic", "memory-morning", "memory-weekly").forEach { wm.cancelUniqueWork(it) }; return }
        MemoryWorker.schedulePeriodic(context)
        val now = LocalDateTime.now(ZoneId.systemDefault())
        daily<MorningWorker>(context, "memory-morning", s.morning, DailyInsightWorker.delayUntil(now, s.morningHour, s.morningMinute).toMinutes(), false)
        daily<WeeklyWorker>(context, "memory-weekly", s.weekly, DailyInsightWorker.delayUntil(now, s.weeklyHour, 0).toMinutes(), true)
    }

    private inline fun <reified W : androidx.work.ListenableWorker> daily(context: Context, name: String, on: Boolean, delayMin: Long, network: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) { wm.cancelUniqueWork(name); return }
        val b = PeriodicWorkRequestBuilder<W>(24, TimeUnit.HOURS).setInitialDelay(delayMin, TimeUnit.MINUTES)
        if (network) b.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, b.build())
    }
}
