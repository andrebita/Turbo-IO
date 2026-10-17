package org.turboio.customai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.turboio.customai.R
import org.turboio.customai.ui.MainActivity
import org.turboio.customai.diag.DiagnosticLog

/**
 * Foreground service that keeps the process (and therefore the GATT connection and voice assistant)
 * alive while the screen is off. Started only from the foreground UI. It holds no data of its own.
 */
class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Conexão com os óculos", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle("Turbo IO conectado")
            .setContentText("Mantendo a conexão Bluetooth com os óculos RayNeo iO")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) {
                var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                t
            } else 0
            ServiceCompat.startForeground(this, 7392, notification, type)
            DiagnosticLog.i("SVC", "serviço em primeiro plano ativo")
        } catch (e: Exception) {
            DiagnosticLog.w("SVC", "serviço em primeiro plano recusado: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { DiagnosticLog.i("SVC", "serviço encerrado"); super.onDestroy() }

    companion object {
        private const val CHANNEL = "glasses_connection"
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
                .onFailure { DiagnosticLog.w("SVC", "não foi possível iniciar o serviço: ${it.javaClass.simpleName}") }
        }
        fun stop(context: Context) { context.stopService(Intent(context, ConnectionService::class.java)) }
    }
}
