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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
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
    protected val lockPending get() = locking

    /** Created in onCreate so a prompt that outlives a rotation reports back to the new screen. */
    private lateinit var prompt: BiometricPrompt

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
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                locking = false
                lastActive = SystemClock.elapsedRealtime()
                findViewById<View>(android.R.id.content).visibility = View.VISIBLE
            }

            // Cancelled, backed out of, or left for another app: stay locked. A wrong finger or PIN isn't an
            // error; the prompt just lets them try again.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                locking = false
                finish()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (started++ == 0) checkLock()
    }

    override fun onStop() {
        super.onStop()
        if (--started == 0 && !locking) lastActive = SystemClock.elapsedRealtime()
    }

    private fun checkLock() {
        val content = findViewById<View>(android.R.id.content)
        // Rebuilt (e.g. rotated) while the prompt is up: the prompt carries on, so just keep the content hidden.
        if (locking) {
            content.visibility = View.INVISIBLE
            return
        }
        if (!Settings(this).appLock) return
        if (lastActive != 0L && SystemClock.elapsedRealtime() - lastActive < GRACE_MS) return
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) return
        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) return
        locking = true
        content.visibility = View.INVISIBLE
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Bank to Wallet")
                .setSubtitle("Unlock to continue")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
        )
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

        /** Fingerprint or face, or the phone's PIN, pattern or password; this pair works on every Android version. */
        const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /** The unlock prompt is showing. Process-wide so it survives the screen being recreated under it. */
        var locking = false

        /** Screens of this app currently started; 0 means the app is in the background. */
        var started = 0

        /** Process-wide so the unlock survives the activity being recreated; 0 means never unlocked. */
        var lastActive = 0L
    }
}
