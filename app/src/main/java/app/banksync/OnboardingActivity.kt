package app.banksync

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar

/** First-run walkthrough. Every step can be skipped, and it can be reopened from the main screen's menu. */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private val steps = intArrayOf(R.id.step0, R.id.step1, R.id.step2, R.id.step3, R.id.step4, R.id.step5)
    private var index = 0

    private val tokenField get() = findViewById<EditText>(R.id.obToken)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
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
        findViewById<Button>(R.id.obAccessButton).setOnClickListener { Permissions.openAccessSettings(this) }
        findViewById<Button>(R.id.obBatteryButton).setOnClickListener { Permissions.requestBattery(this) }
        findViewById<Button>(R.id.obAddApp).setOnClickListener {
            Pickers.addRule(this, token(), it as Button, onAdded = { renderRules() }, onError = ::toast)
        }
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
        if (index == 1 && blocked && tokenField.text.isNullOrBlank()) {
            findViewById<TextView>(R.id.obTokenStatus).text = "Paste your token, then tap Check connection."
        }
    }

    private fun next() {
        if (index == steps.lastIndex) finish() else show(index + 1)
    }

    /** What was just typed wins over the saved token, so the check works before moving on. */
    private fun token() = tokenField.text.toString().trim().ifEmpty { settings.token }

    private fun checkToken() {
        val status = findViewById<TextView>(R.id.obTokenStatus)
        val token = token()
        if (token.isEmpty()) return run { status.text = "Paste your token first." }
        status.text = "Checking…"
        val button = findViewById<Button>(R.id.obCheck).apply { isEnabled = false }
        Pickers.withAccounts(this, token, onError = { button.isEnabled = true; status.text = it }) { accounts ->
            button.isEnabled = true
            settings.token = token
            settings.tokenVerified = true
            tokenField.text = null
            updateNext()
            status.text = "Connected ✓ Found ${accounts.size} account(s): " +
                accounts.joinToString(", ") { it.name }
        }
    }

    private fun renderRules() =
        Pickers.renderRules(this, findViewById<LinearLayout>(R.id.obRules), settings)

    private fun refreshStatus() {
        val access = Permissions.accessEnabled(this)
        val battery = Permissions.batteryExempt(this)
        findViewById<TextView>(R.id.obAccessStatus).text = if (access) "✓ Allowed" else "Not allowed yet"
        findViewById<TextView>(R.id.obBatteryStatus).text = if (battery) "✓ Unrestricted" else "Not changed"
        if (index == steps.lastIndex) {
            val apps = settings.rules().map { it.pkg }.distinct().size
            findViewById<TextView>(R.id.obSummary).text =
                "Wallet token: ${if (settings.token.isEmpty()) "not set yet" else "saved ✓"}\n" +
                    "Notification access: ${if (access) "allowed ✓" else "not allowed yet"}\n" +
                    "Battery: ${if (battery) "unrestricted ✓" else "left as is"}\n" +
                    "Bank apps: ${if (apps == 0) "none yet" else "$apps added ✓"}"
        }
    }

    private fun toast(msg: String) = Snackbar.make(findViewById(R.id.nextButton), msg, Snackbar.LENGTH_SHORT).show()
}

private const val WEB_APP = "https://web.budgetbakers.com"
private const val API_INFO = "https://budgetbakers.com/en/products/wallet/integrations/rest-api/"
