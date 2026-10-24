package app.banksync

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * With app lock on, the content stays hidden until the phone's own screen lock is passed. Every screen
 * extends this, so the lock applies whichever one the app is reopened on. Moving between the app's
 * own screens doesn't count as leaving.
 */
abstract class LockedActivity : AppCompatActivity() {
    protected var lockPending = false
        private set

    private val lock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lockPending = false
        if (result.resultCode == RESULT_OK) {
            lastActive = SystemClock.elapsedRealtime()
            findViewById<View>(android.R.id.content).visibility = View.VISIBLE
        } else {
            finish()
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused with "don't ask again" (or twice): Android won't prompt any more, so say where to turn it on.
        if (!granted && Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Notifications are off")
                .setMessage(
                    "Android won't ask again, so alerts and \"ask before sending\" requests can't be shown. " +
                        "You can turn notifications on for Bank to Wallet in its settings."
                )
                .setPositiveButton("Open settings") { _, _ -> openNotificationSettings() }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    /** Asks for the notification permission (Android 13+) unless it's already granted. */
    protected fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    protected fun openNotificationSettings() {
        startActivity(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The same on every Android version (15+ forces it anyway), with bar icons that follow the theme.
        enableEdgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        if (started++ == 0) checkLock()
    }

    override fun onStop() {
        super.onStop()
        if (--started == 0 && !lockPending) lastActive = SystemClock.elapsedRealtime()
    }

    private fun checkLock() {
        if (!Settings(this).appLock || lockPending) return
        if (lastActive != 0L && SystemClock.elapsedRealtime() - lastActive < GRACE_MS) return
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) return
        val intent = km.createConfirmDeviceCredentialIntent("Bank to Wallet", "Unlock to continue") ?: return
        lockPending = true
        findViewById<View>(android.R.id.content).visibility = View.INVISIBLE
        lock.launch(intent)
    }

    /**
     * Keeps the screen clear of the system bars, the camera cutout and the keyboard. [root] is padded at
     * the sides (landscape navigation bar, cutout) and, without [scroll], at the top too; [scroll] gets
     * the bottom: the navigation bar, or the keyboard while it's open, so the last field stays reachable.
     * A screen with an app bar leaves the top to it (AppBarLayout with fitsSystemWindows).
     */
    protected fun applyInsets(root: View, scroll: View = root, padTop: Boolean = false) {
        val base = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, right = bars.right, top = if (padTop) bars.top else v.paddingTop)
            scroll.updatePadding(bottom = base + maxOf(bars.bottom, ime.bottom))
            // Children see only what is left to handle: the top, for the app bar.
            insets.inset(bars.left, if (padTop) bars.top else 0, bars.right, 0)
        }
    }

    private companion object {
        const val GRACE_MS = 60_000L

        /** Screens of this app currently started; 0 means the app is in the background. */
        var started = 0

        /** Process-wide so the unlock survives the activity being recreated; 0 means never unlocked. */
        var lastActive = 0L
    }
}
