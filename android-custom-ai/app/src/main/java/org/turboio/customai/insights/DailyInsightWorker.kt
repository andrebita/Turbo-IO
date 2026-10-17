package org.turboio.customai.insights

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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.turboio.customai.R
import org.turboio.customai.TurboApp
import org.turboio.customai.diag.DiagnosticLog
import org.turboio.customai.journal.Days
import org.turboio.customai.ui.MainActivity
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Generates today's insight at the configured time, notifies, and optionally shows the short version on the lenses. */
class DailyInsightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TurboApp
        val settings = app.settings.journalSettings()
        val today = Days.of(System.currentTimeMillis())
        return try {
            val removed = app.journal.applyRetention(today, settings.retentionDays)
            if (removed > 0) DiagnosticLog.i("DIARIO", "retenção: $removed dia(s) antigos apagados")
            if (app.journal.day(today).isEmpty()) return Result.success()
            val result = app.insights.day(today)
            notify(app, result.insight.resumo.ifBlank { "Resumo do dia pronto." })
            if (settings.lensDaily && app.session.isReady) app.display.show(result.insight.lens(), app.settings.displaySettings())
            DiagnosticLog.i("DIARIO", "resumo automático gerado (${result.windows} bloco(s))")
            Result.success()
        } catch (e: Exception) {
            DiagnosticLog.w("DIARIO", "resumo automático falhou: ${TurboApp.friendly(e)}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun notify(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Resumo do dia", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, "journal"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(7393, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle("Resumo do seu dia").setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(600)))
            .setContentIntent(open).setAutoCancel(true).build())
    }

    companion object {
        private const val CHANNEL = "daily_insight"
        private const val WORK = "daily-insight"

        /** (Re)schedules or cancels the daily job according to the current settings. */
        fun schedule(context: Context) {
            val app = context.applicationContext as TurboApp
            val s = app.settings.journalSettings()
            val wm = WorkManager.getInstance(context)
            if (!s.autoDaily) { wm.cancelUniqueWork(WORK); return }
            val delay = delayUntil(LocalDateTime.now(ZoneId.systemDefault()), s.dailyHour, s.dailyMinute)
            val request = PeriodicWorkRequestBuilder<DailyInsightWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
            DiagnosticLog.i("DIARIO", "resumo automático agendado para %02d:%02d".format(s.dailyHour, s.dailyMinute))
        }

        fun delayUntil(now: LocalDateTime, hour: Int, minute: Int): Duration {
            var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return Duration.between(now, target)
        }
    }
}
