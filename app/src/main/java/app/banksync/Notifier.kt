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
    private const val ALERT_ID = 1
    private const val REVIEW_BASE = 1000

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun alert(context: Context, title: String, text: String) {
        if (!Settings(context).alertsOn || !canPost(context)) return
        val nm = manager(context)
        nm.notify(ALERT_ID, base(context, ALERTS, title, text).build())
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
    }

    private fun manager(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Needs attention", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(REVIEW, "Review transactions", NotificationManager.IMPORTANCE_DEFAULT))
        return nm
    }
}
