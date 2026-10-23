package app.banksync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.provider.Settings as AndroidSettings

/** Neither permission can be granted from a pop-up; the app can only send the user to the right screen. */
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

    fun requestBattery(context: Context) {
        val intent = if (batteryExempt(context)) {
            Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        }
        runCatching { context.startActivity(intent) }
            .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
