package app.banksync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build

/** Event-driven only: posts when something needs attention, never on a timer. */
object Notifier {
    private const val ALERTS = "alerts"
    private const val REVIEW = "review"
    private const val CAPTURED = "captured"
    private const val ALERT_ID = 1
    private const val UNPARSED_ID = 2
    private const val CAPTURED_ID = 3
    private const val REVIEW_BASE = 1000

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun alert(context: Context, title: String, text: String) {
        if (!Settings(context).alertsOn || !canPost(context)) return
        val nm = manager(context)
        nm.notify(ALERT_ID, base(context, ALERTS, title, text).build())
    }

    /** Bank alerts kept as "not understood"; one notification that counts them, so none is missed. */
    fun unparsed(context: Context, count: Int, reason: String) {
        if (!Settings(context).alertsOn || !canPost(context)) return
        val title = if (count == 1) "A bank alert needs you" else "$count bank alerts need you"
        manager(context).notify(UNPARSED_ID, base(context, ALERTS, title, "$reason. Tap to enter it.").build())
    }

    fun cancelUnparsed(context: Context) = manager(context).cancel(UNPARSED_ID)

    /** A captured transaction, on a silent channel. The latest replaces the one before. */
    fun captured(context: Context, text: String) {
        if (!Settings(context).capturedOn || !canPost(context)) return
        manager(context).notify(CAPTURED_ID, base(context, CAPTURED, "Transaction picked up", text).build())
    }

    /** A pending transaction waiting for approval, with Send and Discard actions on the notification. */
    fun review(context: Context, id: Long, text: String) {
        if (!canPost(context)) return
        val icon = Icon.createWithResource(context, R.drawable.ic_stat)
        val n = base(context, REVIEW, "Send to Wallet?", text)
            .addAction(Notification.Action.Builder(icon, "Send", action(context, ReviewReceiver.SEND, id)).build())
            .addAction(Notification.Action.Builder(icon, "Discard", action(context, ReviewReceiver.DISCARD, id)).build())
            .build()
        manager(context).notify(REVIEW_BASE + id.toInt(), n)
    }

    fun cancelReview(context: Context, id: Long) = manager(context).cancel(REVIEW_BASE + id.toInt())

    private fun action(context: Context, action: String, id: Long): PendingIntent {
        val intent = Intent(context, ReviewReceiver::class.java).setAction(action).putExtra("id", id)
        val code = id.toInt() * 2 + if (action == ReviewReceiver.SEND) 0 else 1
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(context: Context, channel: String, title: String, text: String): Notification.Builder {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            // Amounts and payees stay off the lock screen.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(context.applicationInfo.loadLabel(context.packageManager)).build()
            )
    }

    private fun manager(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Needs attention", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(REVIEW, "Review transactions", NotificationManager.IMPORTANCE_DEFAULT))
        // Low: the bank's own alert has already made a sound.
        nm.createNotificationChannel(NotificationChannel(CAPTURED, "Transactions picked up", NotificationManager.IMPORTANCE_LOW))
        return nm
    }
}
