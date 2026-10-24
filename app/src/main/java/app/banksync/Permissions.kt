package app.banksync

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.provider.Settings as AndroidSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Neither setting can be granted from a pop-up; the app can only send the user to the right screen. */
object Permissions {
    fun accessEnabled(context: Context): Boolean =
        AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    /**
     * Some phones stop the listener without turning access off, and nothing is captured until it is
     * bound again. Asks the system to rebind it; returns true when a rebind was needed.
     */
    fun reconnectIfStopped(context: Context): Boolean {
        if (!accessEnabled(context) || BankNotificationListener.instance != null) return false
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, BankNotificationListener::class.java))
        }
        return true
    }

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openAccessSettings(context: Context) {
        val detail = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, BankNotificationListener::class.java).flattenToString()
            )
        runCatching { context.startActivity(detail) }
            .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    /**
     * The app's info page, where Battery › Unrestricted lives. Asking for the exemption directly needs
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play only allows for a few kinds of app.
     */
    fun openBatterySettings(context: Context) {
        val details = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(details) }
            .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /**
     * Says what notification access is used for before sending the user to turn it on, as Google Play
     * requires for access to personal data. Only "Agree" opens the system screen; turning access off
     * again needs no explanation.
     */
    fun askAccess(activity: Activity) {
        if (accessEnabled(activity)) return openAccessSettings(activity)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Notification access")
            .setMessage(
                "Bank to Wallet reads notifications from the bank apps you choose, so it can record each " +
                    "transaction in your BudgetBakers Wallet.\n\n" +
                    "• From those alerts it takes the amount, who was paid or who paid you, the reference and the time.\n" +
                    "• Notifications from other apps are ignored and never stored.\n" +
                    "• The details go only to your own Wallet account, using your token. Nothing is sent to the " +
                    "developer or anyone else.\n\n" +
                    "Android will warn that the app can read all notifications. On the next screen, turn on the " +
                    "switch for Bank to Wallet.\n\n" +
                    "If the switch is greyed out (an app installed from a file), open the app's info page, tap the " +
                    "⋮ menu, choose \"Allow restricted settings\" and try again."
            )
            .setPositiveButton("Agree and continue") { _, _ -> openAccessSettings(activity) }
            .setNegativeButton("Not now", null)
            .show()
    }
}
