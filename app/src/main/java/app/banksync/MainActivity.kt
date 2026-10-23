package app.banksync

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var store: Store
    private var askedAccessThisLaunch = false

    // The setup guide covers the permission prompts, so don't stack the same dialogs on top of it.
    private var guideShowing = false
    private var justLeftGuide = false
    private val guide = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        guideShowing = false
        justLeftGuide = true
    }

    private var lockPending = false
    private val lock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lockPending = false
        if (result.resultCode == RESULT_OK) {
            lastActive = SystemClock.elapsedRealtime()
            findViewById<View>(android.R.id.content).visibility = View.VISIBLE
        } else {
            finish()
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(settings.exportJson().toByteArray()) }
        }.onSuccess { toast("Exported. Your token is not included.") }
            .onFailure { toast("Could not write the file") }
    }

    private val importSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
        if (text.isNullOrEmpty()) return@registerForActivityResult toast("Could not read the file")
        MaterialAlertDialogBuilder(this)
            .setTitle("Import settings?")
            .setMessage("This replaces your bank apps, category rules and options with the file's. Your token is kept.")
            .setPositiveButton("Import") { _, _ ->
                runCatching { settings.importJson(text) }
                    .onSuccess { recreate() }
                    .onFailure { toast("That isn't a settings file from this app") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val tokenInput get() = findViewById<EditText>(R.id.tokenInput)
    private val attemptsInput get() = findViewById<EditText>(R.id.attemptsInput)
    private val delayInput get() = findViewById<EditText>(R.id.delayInput)
    private val keepInput get() = findViewById<EditText>(R.id.keepInput)
    private val catchUpInput get() = findViewById<EditText>(R.id.catchUpInput)
    private val ignoreInput get() = findViewById<EditText>(R.id.ignoreInput)
    private val pausedSwitch get() = findViewById<MaterialSwitch>(R.id.pausedSwitch)
    private val retrySwitch get() = findViewById<MaterialSwitch>(R.id.retrySwitch)
    private val wifiSwitch get() = findViewById<MaterialSwitch>(R.id.wifiSwitch)
    private val noteSwitch get() = findViewById<MaterialSwitch>(R.id.noteSwitch)
    private val unclearedSwitch get() = findViewById<MaterialSwitch>(R.id.unclearedSwitch)
    private val reviewSwitch get() = findViewById<MaterialSwitch>(R.id.reviewSwitch)
    private val skipExistingSwitch get() = findViewById<MaterialSwitch>(R.id.skipExistingSwitch)
    private val alertsSwitch get() = findViewById<MaterialSwitch>(R.id.alertsSwitch)
    private val lockSwitch get() = findViewById<MaterialSwitch>(R.id.lockSwitch)
    private val rulesList get() = findViewById<LinearLayout>(R.id.rulesList)
    private val categoriesList get() = findViewById<LinearLayout>(R.id.categoriesList)
    private val recentList get() = findViewById<LinearLayout>(R.id.recentList)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        store = Store.get(this)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.main)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menuGuide -> openGuide()
                    R.id.menuTest -> Dialogs.showTest(this@MainActivity)
                    R.id.menuExport -> exportSettings.launch("bank-to-wallet-settings.json")
                    R.id.menuImport -> importSettings.launch(arrayOf("*/*"))
                }
                true
            }
        }

        attemptsInput.setText(settings.maxAttempts.toString())
        delayInput.setText(settings.batchDelayMin.toString())
        keepInput.setText(settings.keepDays.toString())
        catchUpInput.setText(settings.catchUpHours.toString())
        ignoreInput.setText(settings.ignoreWords)
        pausedSwitch.isChecked = settings.paused
        retrySwitch.isChecked = settings.autoRetry
        wifiSwitch.isChecked = settings.wifiOnly
        noteSwitch.isChecked = settings.includeNote
        unclearedSwitch.isChecked = settings.markUncleared
        reviewSwitch.isChecked = settings.reviewMode
        skipExistingSwitch.isChecked = settings.skipExisting
        alertsSwitch.isChecked = settings.alertsOn
        lockSwitch.isChecked = settings.appLock

        alertsSwitch.setOnCheckedChangeListener { _, on -> if (on) askForNotificationPermission() }
        lockSwitch.setOnCheckedChangeListener { button, on ->
            if (on && !getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                button.isChecked = false
                toast("Set a screen lock on your phone first")
            }
        }

        findViewById<Button>(R.id.grantButton).setOnClickListener { Permissions.openAccessSettings(this) }
        findViewById<Button>(R.id.batteryButton).setOnClickListener { Permissions.requestBattery(this) }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.scanButton).setOnClickListener { scan() }
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            Uploader.schedule(this, 0, replace = true)
            toast("Sync queued")
        }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            val n = store.retryFailed()
            if (n > 0) Uploader.schedule(this, 0, replace = true)
            toast(if (n > 0) "Retrying $n record(s)" else "Nothing to retry")
            refresh()
        }
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            store.clearHistory()
            refresh()
        }
        findViewById<Button>(R.id.addAppButton).setOnClickListener {
            Pickers.addRule(this, token(), onAdded = { refresh() }, onError = ::toast)
        }
        findViewById<Button>(R.id.addCategoryButton).setOnClickListener {
            Pickers.addCategoryRule(this, token(), onAdded = { refresh() }, onError = ::toast)
        }

        if (!settings.onboardingDone) openGuide()
    }

    override fun onStart() {
        super.onStart()
        checkLock()
    }

    override fun onStop() {
        super.onStop()
        if (!lockPending) lastActive = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        when {
            guideShowing || lockPending -> {}
            justLeftGuide -> justLeftGuide = false
            else -> onboard()
        }
    }

    private fun openGuide() {
        guideShowing = true
        guide.launch(Intent(this, OnboardingActivity::class.java))
    }

    /** With app lock on, the content stays hidden until the phone's own screen lock is passed. */
    private fun checkLock() {
        if (!settings.appLock || guideShowing || lockPending) return
        if (lastActive != 0L && SystemClock.elapsedRealtime() - lastActive < GRACE_MS) return
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isDeviceSecure) return
        val intent = km.createConfirmDeviceCredentialIntent("Bank to Wallet", "Unlock to continue") ?: return
        lockPending = true
        findViewById<View>(android.R.id.content).visibility = View.INVISIBLE
        lock.launch(intent)
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Returning users get a nudge on launch. Access is asked on every launch until granted (nothing
     * works without it); the battery exemption and the alerts permission are each asked once.
     */
    private fun onboard() {
        if (!Permissions.accessEnabled(this)) {
            if (askedAccessThisLaunch) return
            askedAccessThisLaunch = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow notification access")
                .setMessage(
                    "Bank to Wallet reads your bank apps' notifications to record transactions. " +
                        "Turn on the switch for this app on the next screen.\n\n" +
                        "If the switch is greyed out, open the app's info page, tap the ⋮ menu and choose " +
                        "\"Allow restricted settings\", then try again."
                )
                .setPositiveButton("Open settings") { _, _ -> Permissions.openAccessSettings(this) }
                .setNegativeButton("Later", null)
                .show()
        } else if (!Permissions.batteryExempt(this) && !settings.batteryAsked) {
            settings.batteryAsked = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Keep it running reliably")
                .setMessage(
                    "Some phones stop notification listeners to save battery, which means missed " +
                        "transactions. The app does no background work of its own, so allowing " +
                        "unrestricted battery use costs almost nothing."
                )
                .setPositiveButton("Allow") { _, _ -> Permissions.requestBattery(this) }
                .setNegativeButton("Not now", null)
                .show()
        } else if (Build.VERSION.SDK_INT >= 33 && !Notifier.canPost(this) && !settings.notificationsAsked) {
            settings.notificationsAsked = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow alerts")
                .setMessage(
                    "The app can tell you when a token is rejected or a transaction couldn't be sent, " +
                        "and it needs this for \"ask before sending\" mode. It only posts when something happens."
                )
                .setPositiveButton("Allow") { _, _ -> askForNotificationPermission() }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun save() {
        tokenInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let {
            settings.token = it
            tokenInput.text = null
        }
        settings.ignoreWords = ignoreInput.text.toString()
        settings.maxAttempts = attemptsInput.text.toString().toIntOrNull() ?: 10
        settings.batchDelayMin = delayInput.text.toString().toIntOrNull() ?: 2
        settings.keepDays = keepInput.text.toString().toIntOrNull() ?: 7
        settings.catchUpHours = catchUpInput.text.toString().toIntOrNull() ?: 24
        settings.paused = pausedSwitch.isChecked
        settings.autoRetry = retrySwitch.isChecked
        settings.wifiOnly = wifiSwitch.isChecked
        settings.includeNote = noteSwitch.isChecked
        settings.markUncleared = unclearedSwitch.isChecked
        settings.reviewMode = reviewSwitch.isChecked
        settings.skipExisting = skipExistingSwitch.isChecked
        settings.alertsOn = alertsSwitch.isChecked
        settings.appLock = lockSwitch.isChecked
        settings.authError = false
        if (reviewSwitch.isChecked) askForNotificationPermission()
        if (store.counts()[Store.PENDING] > 0) Uploader.schedule(this, replace = true)
        toast("Saved")
        refresh()
    }

    /** What the user just typed wins over the saved token, so accounts can be listed before saving. */
    private fun token() = tokenInput.text.toString().trim().ifEmpty { settings.token }

    private fun refresh() {
        val apps = settings.rules().map { it.pkg }.distinct().size
        findViewById<TextView>(R.id.statusText).text = buildString {
            append("Notification access: ")
                .append(if (Permissions.accessEnabled(this@MainActivity)) "on" else "OFF, tap the button below")
                .append('\n')
            append("Battery: ")
                .append(if (Permissions.batteryExempt(this@MainActivity)) "unrestricted" else "optimised (may miss alerts)")
                .append('\n')
            append("Wallet token: ").append(if (settings.token.isEmpty()) "not set" else "saved").append('\n')
            append("Watching $apps app(s)")
            if (settings.paused) append("\nCapture is paused")
            if (settings.reviewMode) append("\nAsking before sending")
            if (settings.authError) append("\nWallet rejected the token. Records are waiting; save a new token.")
        }
        Pickers.renderRules(this, rulesList, settings) { refresh() }
        Pickers.renderCategoryRules(this, categoriesList, settings) { refresh() }

        val c = store.counts()
        findViewById<TextView>(R.id.queueText).text =
            "${c[Store.PENDING]} waiting · ${c[Store.REVIEW]} to review · ${c[Store.SYNCED]} synced · " +
                "${c[Store.FAILED]} failed · ${c[Store.UNPARSED]} not understood"
        renderRecent()
    }

    private fun renderRecent() {
        val list = recentList
        list.removeAllViews()
        val recs = store.recent(25)
        if (recs.isEmpty()) {
            list.addView(TextView(this).apply { text = "Nothing captured yet" })
            return
        }
        val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val pad = (6 * resources.displayMetrics.density).toInt()
        val labels = listOf("waiting", "synced", "failed", "not understood", "to review")
        for (r in recs) {
            val whenText = iso.parse(r.date)?.let { fmt.format(it) } ?: r.date
            val what = if (r.amount != null) {
                String.format(Locale.US, "%+.2f  %s", r.amount, if (r.transferTo.isNullOrEmpty()) r.payee.orEmpty() else "transfer")
            } else {
                r.note.orEmpty().take(60)
            }
            val clickable = r.status == Store.UNPARSED || r.status == Store.REVIEW || r.status == Store.FAILED
            val row = TextView(this).apply {
                text = "• $whenText  $what  [${labels[r.status]}]" +
                    (r.error?.let { "\n   $it" } ?: "") + if (clickable) "\n   tap to open" else ""
                setPadding(0, pad, 0, pad)
                if (clickable) {
                    setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                    setOnClickListener { openRecord(r) }
                }
            }
            list.addView(row)
        }
    }

    /** Asks the bound listener to read what's still in the shade; dismissed alerts can't be recovered. */
    private fun scan() {
        val listener = BankNotificationListener.instance
            ?: return toast("Notification access isn't active. Turn it on first.")
        Thread {
            val found = listener.catchUp()
            runOnUiThread {
                toast(if (found > 0) "Found $found missed alert(s)" else "No missed alerts found")
                refresh()
            }
        }.start()
    }

    private fun openRecord(r: Rec) {
        when (r.status) {
            Store.UNPARSED -> Dialogs.showFix(this, r) { refresh() }
            Store.REVIEW -> MaterialAlertDialogBuilder(this)
                .setTitle("Send to Wallet?")
                .setMessage(String.format(Locale.US, "%+.2f  %s", r.amount ?: 0.0, r.payee.orEmpty()))
                .setPositiveButton("Send") { _, _ ->
                    store.setStatus(r.id, Store.PENDING)
                    Notifier.cancelReview(this, r.id)
                    Uploader.schedule(this, 0, replace = true)
                    refresh()
                }
                .setNegativeButton("Discard") { _, _ ->
                    store.delete(r.id)
                    Notifier.cancelReview(this, r.id)
                    refresh()
                }
                .setNeutralButton("Cancel", null)
                .show()
            Store.FAILED -> MaterialAlertDialogBuilder(this)
                .setTitle("Couldn't be sent")
                .setMessage(r.error ?: "Unknown error")
                .setPositiveButton("Retry") { _, _ ->
                    store.setStatus(r.id, Store.PENDING)
                    Uploader.schedule(this, 0, replace = true)
                    refresh()
                }
                .setNegativeButton("Discard") { _, _ ->
                    store.delete(r.id)
                    refresh()
                }
                .setNeutralButton("Cancel", null)
                .show()
        }
    }

    private fun toast(msg: String) = Snackbar.make(findViewById(R.id.saveButton), msg, Snackbar.LENGTH_SHORT).show()

    private companion object {
        const val GRACE_MS = 60_000L

        /** Process-wide so the unlock survives the activity being recreated; 0 means never unlocked. */
        var lastActive = 0L
    }
}
