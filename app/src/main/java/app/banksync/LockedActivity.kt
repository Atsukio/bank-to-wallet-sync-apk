package app.banksync

import android.app.KeyguardManager
import android.os.SystemClock
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

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

    /** Keeps the end of a scrolling page clear of the gesture bar on edge-to-edge phones. */
    protected fun padForNavigationBar(view: View) {
        val base = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            v.updatePadding(bottom = base + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
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
