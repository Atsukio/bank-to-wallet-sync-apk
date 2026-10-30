package app.banksync

import android.app.KeyguardManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.text.HtmlCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as M

/** First-run walkthrough. Every step can be skipped, and it can be reopened from the main screen's menu. */
class OnboardingActivity : LockedActivity() {
    private lateinit var settings: Settings
    private val steps = intArrayOf(R.id.step0, R.id.step1, R.id.step2, R.id.step3, R.id.step4, R.id.step5, R.id.step6)
    private var index = 0

    private val tokenField get() = findViewById<EditText>(R.id.obToken)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        applyInsets(findViewById(R.id.root), padTop = true)
        settings = Settings(this)
        index = savedInstanceState?.getInt("index") ?: settings.onboardingStep

        findViewById<TextView>(R.id.obWalletText).apply {
            text = HtmlCompat.fromHtml(
                "The app adds records through Wallet's REST API, which needs Wallet Premium and a personal API token." +
                    "<br><br>1. Open the <a href=\"$WEB_APP\">Wallet web app</a> and sign in." +
                    "<br>2. Go to Settings and look for the API token (REST API) option. " +
                    "<a href=\"$API_INFO\">More about the REST API</a>." +
                    "<br>3. Generate a token, copy it and paste it below." +
                    "<br><br>Treat the token like a password: it gives access to your Wallet data.",
                HtmlCompat.FROM_HTML_MODE_COMPACT
            )
            movementMethod = LinkMovementMethod.getInstance()
        }
        tokenField.doAfterTextChanged { updateNext() }
        findViewById<Button>(R.id.skipButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.backButton).setOnClickListener { show(index - 1) }
        findViewById<Button>(R.id.nextButton).setOnClickListener { next() }
        findViewById<Button>(R.id.obCheck).setOnClickListener { checkToken() }
        findViewById<Button>(R.id.obDemo).setOnClickListener {
            Demo.start(this)
            finish()
        }
        findViewById<Button>(R.id.obAccessButton).setOnClickListener { Permissions.askAccess(this) }
        findViewById<Button>(R.id.obBatteryButton).setOnClickListener { Permissions.openBatterySettings(this) }
        findViewById<Button>(R.id.obAddApp).setOnClickListener {
            Pickers.addRule(this, token(), it as Button, onAdded = { renderRules() }, onError = ::toast)
        }
        preferenceSwitches()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (index > 0) show(index - 1) else finish()
            }
        })
        show(index)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Leaving by any route (finish, skip, back) means the guide has been seen. */
    override fun finish() {
        settings.onboardingDone = true
        settings.onboardingStep = 0
        super.finish()
    }

    private fun show(i: Int) {
        index = i.coerceIn(0, steps.lastIndex)
        steps.forEachIndexed { n, id ->
            findViewById<View>(id).visibility = if (n == index) View.VISIBLE else View.GONE
        }
        findViewById<LinearProgressIndicator>(R.id.progress).setProgressCompat((index + 1) * 100 / steps.size, true)
        findViewById<TextView>(R.id.stepText).text = "Step ${index + 1} of ${steps.size}"
        findViewById<Button>(R.id.backButton).visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        findViewById<Button>(R.id.skipButton).visibility = if (index < steps.lastIndex) View.VISIBLE else View.INVISIBLE
        findViewById<Button>(R.id.nextButton).text = if (index == steps.lastIndex) "Finish" else "Next"
        settings.onboardingStep = index
        if (index == 4) renderRules()
        refreshStatus()
        updateNext()
    }

    /** The token step can't be passed until the connection check has succeeded for the token in use. */
    private fun updateNext() {
        val blocked = index == 1 && !(tokenField.text.isNullOrBlank() && settings.tokenVerified)
        findViewById<Button>(R.id.nextButton).isEnabled = !blocked
        if (index == 1 && tokenField.text.isNullOrBlank()) renderSavedToken()
    }

    /** With a token already saved, the empty field is for replacing it, so say what's saved instead of looking unset. */
    private fun renderSavedToken() {
        val saved = settings.token.isNotEmpty()
        findViewById<TextInputLayout>(R.id.obTokenLayout).hint = if (saved) "New API token (only to replace it)" else "API token"
        when {
            !saved -> tokenStatus("Paste your token, then tap Check connection.", TokenState.TODO)
            settings.authError ->
                tokenStatus("Wallet rejected the saved token. Paste a new one, then tap Check connection.", TokenState.BAD)
            settings.tokenVerified -> tokenStatus("Connected ✓ Your token is saved and working.", TokenState.GOOD)
            else -> tokenStatus("Token saved. Tap Check connection to make sure it works.", TokenState.TODO)
        }
    }

    private enum class TokenState { GOOD, TODO, BAD }

    /** Green once connected, amber while something is still to do, red when Wallet said no. */
    private fun tokenStatus(text: String, state: TokenState) {
        findViewById<TextView>(R.id.obTokenStatus).status(
            text,
            when (state) {
                TokenState.GOOD -> Status.GOOD
                TokenState.TODO -> Status.WARN
                TokenState.BAD -> Status.BAD
            }
        )
    }

    private fun next() {
        if (index == steps.lastIndex) finish() else show(index + 1)
    }

    /** What was just typed wins over the saved token, so the check works before moving on. */
    private fun token() = tokenField.text.toString().trim().ifEmpty { settings.token }

    private fun checkToken() {
        val token = token()
        if (token.isEmpty()) return tokenStatus("Paste your token first.", TokenState.TODO)
        tokenStatus("Checking…", TokenState.TODO)
        val button = findViewById<Button>(R.id.obCheck).apply { isEnabled = false }
        Pickers.withAccounts(this, token, onError = { button.isEnabled = true; tokenStatus(it, TokenState.BAD) }) { accounts ->
            button.isEnabled = true
            settings.token = token
            settings.tokenVerified = true
            tokenField.text = null
            // So the first alerts already get categories from the user's Wallet rules and history.
            Thread { WalletCache.refresh(applicationContext, token, force = true) }.start()
            updateNext()
            tokenStatus(
                "Connected ✓ Found ${accounts.size} account(s): " + accounts.joinToString(", ") { it.name },
                TokenState.GOOD
            )
        }
    }

    /** The few choices worth making at setup; they save straight away, the same as in Settings. */
    private fun preferenceSwitches() {
        switch(R.id.obReviewSwitch, settings.reviewMode) {
            settings.reviewMode = it
            if (it) askForNotificationPermission()
        }
        switch(R.id.obAskNoCategorySwitch, settings.askNoCategory) {
            settings.askNoCategory = it
            if (it) askForNotificationPermission()
        }
        switch(R.id.obCapturedSwitch, settings.capturedOn) {
            settings.capturedOn = it
            if (it) askForNotificationPermission()
        }
        switch(R.id.obSummarySwitch, settings.dailySummary) {
            settings.dailySummary = it
            Summary.schedule(this)
            if (it) askForNotificationPermission()
        }
        switch(R.id.obWifiSwitch, settings.wifiOnly) {
            settings.wifiOnly = it
            if (Store.get(this).counts()[Store.PENDING] > 0) Uploader.schedule(this, replace = true)
        }
        findViewById<MaterialSwitch>(R.id.obLockSwitch).apply {
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
    }

    private fun switch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    private fun renderRules() =
        Pickers.renderRules(this, findViewById<LinearLayout>(R.id.obRules), settings, onMessage = ::toast, onUndo = ::undoable)

    private fun refreshStatus() {
        val access = Permissions.accessEnabled(this)
        val battery = Permissions.batteryExempt(this)
        findViewById<TextView>(R.id.obAccessStatus).apply {
            if (access) status("✓ Allowed", Status.GOOD) else status("Not allowed yet", Status.BAD)
        }
        findViewById<TextView>(R.id.obBatteryStatus).apply {
            if (battery) status("✓ Unrestricted", Status.GOOD) else status("Not changed", Status.WARN)
        }
        if (index == steps.lastIndex) {
            val apps = settings.rules().map { it.pkg }.distinct().size
            // Each line's value takes the colour of how it stands; the label stays plain.
            val lines = listOf(
                Triple("Wallet token", if (settings.token.isEmpty()) "not set yet" else "saved ✓",
                    if (settings.token.isEmpty()) Status.BAD else Status.GOOD),
                Triple("Notification access", if (access) "allowed ✓" else "not allowed yet",
                    if (access) Status.GOOD else Status.BAD),
                Triple("Battery", if (battery) "unrestricted ✓" else "left as is",
                    if (battery) Status.GOOD else Status.WARN),
                Triple("Bank apps", if (apps == 0) "none yet" else "$apps added ✓",
                    if (apps == 0) Status.WARN else Status.GOOD),
                Triple("Sending", (if (settings.reviewMode) "after you tap Send" else "automatic") +
                    (if (settings.wifiOnly) ", on Wi-Fi only" else ""), null),
                Triple("App lock", if (settings.appLock) "on" else "off", null),
            )
            findViewById<TextView>(R.id.obSummary).text = SpannableStringBuilder().apply {
                for ((i, line) in lines.withIndex()) {
                    if (i > 0) append("\n")
                    append("${line.first}: ")
                    val start = length
                    append(line.second)
                    line.third?.let {
                        setSpan(ForegroundColorSpan(getColor(it.color)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }
    }

    private fun toast(msg: String) = Snackbar.make(findViewById(R.id.nextButton), msg, Snackbar.LENGTH_SHORT).show()

    private fun undoable(msg: String, undo: () -> Unit) =
        Snackbar.make(findViewById(R.id.nextButton), msg, Snackbar.LENGTH_LONG).setAction("Undo") { undo() }.show()
}

internal const val WEB_APP = "https://web.budgetbakers.com"
internal const val API_INFO = "https://budgetbakers.com/en/products/wallet/integrations/rest-api/"
