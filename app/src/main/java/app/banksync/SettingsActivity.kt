package app.banksync

import android.app.KeyguardManager
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/** Wallet connection, category rules and every option. Changes save as they're made; there's no Save button. */
class SettingsActivity : LockedActivity() {
    private lateinit var settings: Settings
    private lateinit var store: Store
    private var editingToken = false

    private val tokenInput get() = findViewById<EditText>(R.id.tokenInput)
    private val attemptsInput get() = findViewById<EditText>(R.id.attemptsInput)
    private val delayInput get() = findViewById<EditText>(R.id.delayInput)
    private val keepInput get() = findViewById<EditText>(R.id.keepInput)
    private val catchUpInput get() = findViewById<EditText>(R.id.catchUpInput)
    private val ignoreInput get() = findViewById<EditText>(R.id.ignoreInput)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        store = Store.get(this)
        applyInsets(findViewById(R.id.root), findViewById(R.id.scroll))
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        attemptsInput.setText(settings.maxAttempts.toString())
        delayInput.setText(settings.batchDelayMin.toString())
        keepInput.setText(settings.keepDays.toString())
        catchUpInput.setText(settings.catchUpHours.toString())
        ignoreInput.setText(settings.ignoreWords)

        switch(R.id.pausedSwitch, settings.paused) { settings.paused = it }
        switch(R.id.reviewSwitch, settings.reviewMode) {
            settings.reviewMode = it
            if (it) askForNotificationPermission()
        }
        switch(R.id.retrySwitch, settings.autoRetry) { settings.autoRetry = it }
        switch(R.id.wifiSwitch, settings.wifiOnly) {
            settings.wifiOnly = it
            // A job already waiting keeps its old network rule unless it's replaced.
            if (store.counts()[Store.PENDING] > 0) Uploader.schedule(this, replace = true)
        }
        switch(R.id.skipExistingSwitch, settings.skipExisting) { settings.skipExisting = it }
        switch(R.id.noteSwitch, settings.includeNote) { settings.includeNote = it }
        switch(R.id.unclearedSwitch, settings.markUncleared) { settings.markUncleared = it }
        switch(R.id.alertsSwitch, settings.alertsOn) {
            settings.alertsOn = it
            if (it) askForNotificationPermission()
        }
        findViewById<MaterialSwitch>(R.id.lockSwitch).apply {
            isChecked = settings.appLock
            setOnCheckedChangeListener { button, on ->
                if (on && !getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                    button.isChecked = false
                    toast("Set a screen lock on your phone first")
                } else {
                    settings.appLock = on
                }
            }
        }

        findViewById<TextView>(R.id.tokenHelp).apply {
            text = HtmlCompat.fromHtml(
                "Get one in the <a href=\"$WEB_APP\">Wallet web app</a> under Settings (REST API). " +
                    "<a href=\"$API_INFO\">More about the REST API</a>",
                HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            movementMethod = LinkMovementMethod.getInstance()
        }
        findViewById<Button>(R.id.tokenCheck).setOnClickListener { checkToken() }
        findViewById<Button>(R.id.tokenChange).setOnClickListener {
            editingToken = !editingToken
            tokenInput.text = null
            renderToken()
            if (editingToken) tokenInput.requestFocus()
        }
        findViewById<Button>(R.id.addAppButton).setOnClickListener {
            Pickers.addRule(this, settings.token, it as Button, onAdded = { renderRules() }, onError = ::toast)
        }
        findViewById<Button>(R.id.addCategoryButton).setOnClickListener {
            Pickers.addCategoryRule(this, settings.token, it as Button, onAdded = ::renderCategories, onError = ::toast)
        }

        link(R.id.walletDataRow, R.drawable.ic_sync, "Learned from Wallet") { refreshWalletData() }
        link(R.id.memoryRow, R.drawable.ic_label, "Remembered choices") { forgetChoices() }
        link(R.id.accessRow, R.drawable.ic_notifications, "Notification access") { Permissions.openAccessSettings(this) }
        link(R.id.batteryRow, R.drawable.ic_battery, "Battery use") { Permissions.requestBattery(this) }
    }

    override fun onResume() {
        super.onResume()
        renderToken()
        renderRules()
        renderCategories()
        findViewById<View>(R.id.accessRow).findViewById<TextView>(R.id.linkText).text =
            if (Permissions.accessEnabled(this)) "Allowed" else "Off: bank alerts can't be read"
        findViewById<View>(R.id.batteryRow).findViewById<TextView>(R.id.linkText).text =
            if (Permissions.batteryExempt(this)) "Unrestricted" else "Optimised: some phones may stop the app"
    }

    /** Typed values are saved when leaving the screen, so half-typed numbers never take effect. */
    override fun onPause() {
        super.onPause()
        settings.ignoreWords = ignoreInput.text.toString()
        settings.maxAttempts = attemptsInput.text.toString().toIntOrNull() ?: settings.maxAttempts
        settings.batchDelayMin = delayInput.text.toString().toIntOrNull() ?: settings.batchDelayMin
        settings.keepDays = keepInput.text.toString().toIntOrNull() ?: settings.keepDays
        settings.catchUpHours = catchUpInput.text.toString().toIntOrNull() ?: settings.catchUpHours
    }

    private fun switch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    private fun link(id: Int, icon: Int, title: String, onClick: () -> Unit) {
        findViewById<View>(id).apply {
            findViewById<ImageView>(R.id.linkIcon).setImageResource(icon)
            findViewById<TextView>(R.id.linkTitle).text = title
            setOnClickListener { onClick() }
        }
    }

    private fun renderRules() = Pickers.renderRules(this, findViewById<LinearLayout>(R.id.rulesList), settings)

    private fun renderCategories() {
        Pickers.renderCategoryRules(this, findViewById<LinearLayout>(R.id.categoriesList), settings)
        val d = WalletCache.get(this)
        linkText(R.id.walletDataRow).text = if (d.rulesAt == 0L) {
            "Nothing yet. Tap to load your Wallet rules and past records."
        } else {
            val ago = android.text.format.DateUtils.getRelativeTimeSpanString(maxOf(d.rulesAt, d.historyAt))
            "${d.rules.size} Wallet rule(s), ${d.history.size} past record(s) · updated $ago. Tap to refresh."
        }
        val n = settings.categoryMemory().size
        linkText(R.id.memoryRow).text =
            if (n == 0) "None yet. Change a transaction's category to teach it." else "$n merchant(s). Tap to forget them."
    }

    private fun linkText(row: Int) = findViewById<View>(row).findViewById<TextView>(R.id.linkText)

    private fun refreshWalletData() {
        val token = settings.token
        if (token.isEmpty()) return toast("Connect to Wallet first")
        linkText(R.id.walletDataRow).text = "Loading from Wallet…"
        Thread {
            val ok = WalletCache.refresh(this, token, force = true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderCategories()
                if (!ok) toast("Couldn't load everything from Wallet. Try again later.")
            }
        }.start()
    }

    private fun forgetChoices() {
        if (settings.categoryMemory().isEmpty()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Forget remembered choices?")
            .setMessage("New transactions from those merchants will be categorised by your rules and past records instead.")
            .setPositiveButton("Forget") { _, _ ->
                settings.forgetCategories()
                renderCategories()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The connection card always says where things stand. With a token saved, the field stays hidden
     * behind "Change token"; with none, it's shown straight away.
     */
    private fun renderToken() {
        val hasToken = settings.token.isNotEmpty()
        val editing = editingToken || !hasToken
        val problem = !hasToken || settings.authError
        findViewById<TextView>(R.id.tokenStatus).text = when {
            !hasToken -> "Not connected. Paste your Wallet API token to start sending transactions."
            settings.authError -> "Wallet rejected the saved token. Transactions are waiting until you change it."
            settings.tokenVerified -> "Connected. Your token is saved and working."
            else -> "Token saved but not checked yet."
        }
        findViewById<ImageView>(R.id.tokenIcon).let {
            when {
                problem -> it.badge(R.drawable.ic_error, Tone.BAD)
                settings.tokenVerified -> it.badge(R.drawable.ic_check_circle, Tone.GOOD)
                else -> it.badge(R.drawable.ic_wallet, Tone.NEUTRAL)
            }
        }
        findViewById<View>(R.id.tokenEdit).visibility = if (editing) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.tokenCheck).text = if (editing) "Save and check" else "Check connection"
        findViewById<Button>(R.id.tokenChange).apply {
            visibility = if (hasToken) View.VISIBLE else View.GONE
            text = if (editing) "Cancel" else "Change token"
        }
    }

    /**
     * Checks the token being typed, or the saved one. A new token is saved only once Wallet accepts
     * it, so a typo can't break a working connection.
     */
    private fun checkToken() {
        val editing = editingToken || settings.token.isEmpty()
        val token = if (editing) tokenInput.text.toString().trim() else settings.token
        if (token.isEmpty()) return toast("Paste your token first")
        val button = findViewById<Button>(R.id.tokenCheck).apply { isEnabled = false }
        val status = findViewById<TextView>(R.id.tokenStatus)
        status.text = "Checking…"
        Pickers.withAccounts(this, token, onError = {
            button.isEnabled = true
            renderToken()
            status.text = if (editing && settings.token.isNotEmpty()) "$it. Your saved token hasn't changed." else it
        }) { accounts ->
            button.isEnabled = true
            if (editing) settings.token = token
            settings.tokenVerified = true
            settings.authError = false
            editingToken = false
            tokenInput.text = null
            if (store.counts()[Store.PENDING] > 0) Uploader.schedule(this, 0, replace = true)
            renderToken()
            status.text = "Connected. Found ${accounts.size} Wallet account(s)."
            refreshWalletData()
        }
    }

    private fun toast(msg: String) = Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_SHORT).show()
}
