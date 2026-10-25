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

/** Event-driven only: posts when something happens, never on a timer (the opt-in [Summary] aside). */
object Notifier {
    private const val ALERTS = "alerts"
    private const val REVIEW = "review"
    private const val CAPTURED = "captured"
    private const val ALERT_ID = 1
    private const val UNPARSED_ID = 2
    private const val SUMMARY = "summary"
    private const val CAPTURED_ID = 3
    private const val SUMMARY_ID = 4
    private const val REVIEW_BASE = 1000
    private const val CHANGE_CODE = -1

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

    /**
     * A captured transaction [id], on a silent channel. The latest replaces the one before. Change and
     * Don't send are offered only until it goes; then [capturedSent] swaps it for a plain "Sent".
     */
    fun captured(context: Context, id: Long, text: String) {
        if (!Settings(context).capturedOn || !canPost(context)) return
        val icon = Icon.createWithResource(context, R.drawable.ic_stat)
        val change = PendingIntent.getActivity(
            context, CHANGE_CODE, Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RECORD, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = base(context, CAPTURED, "Transaction picked up", text)
            .addAction(Notification.Action.Builder(icon, "Change", change).build())
            .addAction(Notification.Action.Builder(icon, "Don't send", action(context, ReviewReceiver.DROP, id)).build())
            .build()
        state(context).edit().putLong("capturedId", id).putString("capturedText", text).apply()
        manager(context).notify(CAPTURED_ID, n)
    }

    /** After an upload: if the "picked up" notification is still showing and its record went, say so. */
    fun capturedSent(context: Context) {
        val s = state(context)
        val id = s.getLong("capturedId", -1)
        if (id < 0) return
        val nm = manager(context)
        if (nm.activeNotifications.none { it.id == CAPTURED_ID }) return s.edit().remove("capturedId").apply()
        if (Store.get(context).get(id)?.status != Store.SYNCED) return
        s.edit().remove("capturedId").apply()
        nm.notify(CAPTURED_ID, base(context, CAPTURED, "Sent to Wallet", s.getString("capturedText", "").orEmpty()).build())
    }

    fun cancelCaptured(context: Context) {
        state(context).edit().remove("capturedId").apply()
        manager(context).cancel(CAPTURED_ID)
    }

    fun summary(context: Context, text: String) {
        if (!canPost(context)) return
        manager(context).notify(SUMMARY_ID, base(context, SUMMARY, "Today in Bank to Wallet", text).build())
    }

    /** Which record the "picked up" notification is about; not a setting, so not exported. */
    private fun state(context: Context) = context.getSharedPreferences("notifier", Context.MODE_PRIVATE)

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

    /** A Cash in with no GO+ account to go to [id]: asks whether to drop every future one. */
    fun askIgnoreCashIn(context: Context, id: Long) {
        if (!canPost(context)) return
        val icon = Icon.createWithResource(context, R.drawable.ic_stat)
        val n = base(
            context, REVIEW, "Ignore Cash in alerts?",
            "No GO+ account is linked to this app, so a Cash in has nowhere to go. Ignore all future ones? " +
                "You can change this in Settings, and you'll be asked again if you link a GO+ account."
        ).addAction(Notification.Action.Builder(icon, "Ignore them", action(context, ReviewReceiver.IGNORE_CASH_IN, id)).build())
            .build()
        manager(context).notify(REVIEW_BASE + id.toInt(), n)
    }

    private fun action(context: Context, action: String, id: Long): PendingIntent {
        val intent = Intent(context, ReviewReceiver::class.java).setAction(action).putExtra("id", id)
        val code = id.toInt() * 3 + when (action) {
            ReviewReceiver.SEND -> 0
            ReviewReceiver.DISCARD -> 1
            else -> 2
        }
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
        nm.createNotificationChannel(NotificationChannel(SUMMARY, "Daily summary", NotificationManager.IMPORTANCE_LOW))
        return nm
    }
}
