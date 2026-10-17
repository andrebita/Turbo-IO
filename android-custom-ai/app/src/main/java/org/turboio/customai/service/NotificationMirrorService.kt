package org.turboio.customai.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.turboio.customai.TurboApp
import org.turboio.customai.device.MirrorPolicy

/**
 * Phone notifications → glasses (user-enabled in Android settings: “Acesso a notificações”). Only apps the user picked
 * are sent, as the glasses' native card; Google Maps navigation goes to the lens text. Nothing is stored or logged.
 */
class NotificationMirrorService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val app = runCatching { applicationContext as TurboApp }.getOrNull() ?: return
        if (sbn.packageName != packageName) app.settings.rememberNotifyingApp(sbn.packageName)
        if (!app.settings.mirrorOn && !app.settings.navOn) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrNull()
        val incoming = MirrorPolicy.Incoming(
            pkg = sbn.packageName, appName = MirrorPolicy.name(sbn.packageName, label),
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
            ongoing = sbn.isOngoing, groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0, category = n.category,
        )
        app.mirrorNotification(incoming)
    }
}
