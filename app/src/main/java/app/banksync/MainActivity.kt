package app.banksync

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.google.android.material.R as M

/** Home: whether everything works, what's queued, and recent activity. Bank apps and options live in [SettingsActivity]. */
class MainActivity : LockedActivity() {
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

    private val recentList get() = findViewById<LinearLayout>(R.id.recentList)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        store = Store.get(this)
        applyInsets(findViewById(R.id.root), findViewById(R.id.scroll))

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.main)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menuSettings -> openSettings()
                    R.id.menuScan -> scan()
                    R.id.menuClear -> confirmClear()
                    R.id.menuGuide -> openGuide()
                    R.id.menuTest -> Dialogs.showTest(this@MainActivity)
                    R.id.menuExport -> exportSettings.launch("bank-to-wallet-settings.json")
                    R.id.menuImport -> importSettings.launch(arrayOf("*/*"))
                }
                true
            }
        }

        findViewById<Button>(R.id.syncButton).setOnClickListener {
            if (store.counts()[Store.PENDING] == 0) return@setOnClickListener toast("Nothing waiting to send")
            Uploader.schedule(this, 0, replace = true)
            toast("Sending now")
        }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            val n = store.retryFailed()
            if (n > 0) Uploader.schedule(this, 0, replace = true)
            toast(if (n > 0) "Retrying $n record(s)" else "Nothing to retry")
            refresh()
        }
        findViewById<Button>(R.id.hideButton).setOnClickListener {
            settings.hideDetails = !settings.hideDetails
            renderRecent()
        }
        // "Need you" jumps to the list where those records can be opened.
        findViewById<View>(R.id.tileAttention).setOnClickListener {
            findViewById<NestedScrollView>(R.id.scroll).smoothScrollTo(0, (recentList.parent as View).top)
        }

        if (!settings.onboardingDone) {
            // A new install starts on the setup guide, not on a list of changes.
            settings.seenVersion = Changelog.installed(this).first
            openGuide()
        }
    }

    override fun onResume() {
        super.onResume()
        // Binding takes a moment, so look again shortly after asking for a rebind.
        if (Permissions.reconnectIfStopped(this)) window.decorView.postDelayed({ if (!isFinishing) refresh() }, 2_000)
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

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun addApp(trigger: Button?) =
        Pickers.addRule(this, settings.token, trigger, onAdded = { refresh() }, onError = ::toast)

    /**
     * Returning users get a nudge on launch. Access is asked on every launch until granted (nothing
     * works without it); the battery exemption and the alerts permission are each asked once.
     */
    private fun onboard() {
        if (showWhatsNew()) return
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

    /**
     * Once after each update, the changes since the version last opened. Installs from before this
     * existed have no record, so they're treated as having seen 1.0.
     */
    private fun showWhatsNew(): Boolean {
        val installed = Changelog.installed(this).first
        val seen = settings.seenVersion.takeIf { it > 0 } ?: 1
        if (installed <= settings.seenVersion) return false
        settings.seenVersion = installed
        val news = Changelog.since(seen, installed)
        if (news.isEmpty()) return false
        Dialogs.showChangelog(this, "What's new", news)
        return true
    }

    private fun refresh() {
        val c = store.counts()
        renderHero(c)
        renderChecks()
        renderCounts(c)
        renderRecent()
    }

    /** The one thing that matters most right now, worst problem first, with the button that fixes it. */
    private fun renderHero(c: IntArray) {
        val apps = settings.rules().map { it.pkg }.distinct().size
        val waiting = c[Store.PENDING]
        class Hero(val tone: Tone, val icon: Int, val title: String, val text: String, val action: String? = null, val onAction: (Button) -> Unit = {})
        val hero = when {
            !Permissions.accessEnabled(this) -> Hero(
                Tone.BAD, R.drawable.ic_error, "Not reading alerts",
                "Turn on notification access so the app can see your bank's alerts.", "Turn on"
            ) { Permissions.openAccessSettings(this) }
            BankNotificationListener.instance == null -> Hero(
                Tone.WARN, R.drawable.ic_sync, "Reconnecting…",
                "Notification access is on, but Android hasn't connected the app yet. This usually takes a moment."
            )
            settings.token.isEmpty() -> Hero(
                Tone.BAD, R.drawable.ic_wallet, "Wallet isn't connected",
                "Add your Wallet API token so transactions can be sent.", "Connect"
            ) { openSettings() }
            settings.authError -> Hero(
                Tone.BAD, R.drawable.ic_error, "Wallet rejected your token",
                "$waiting transaction(s) waiting. Change the token to send them.", "Change token"
            ) { openSettings() }
            apps == 0 -> Hero(
                Tone.WARN, R.drawable.ic_apps, "Choose a bank app",
                "Pick the bank apps to watch and the Wallet account each one feeds.", "Add bank app"
            ) { addApp(it) }
            settings.paused -> Hero(
                Tone.WARN, R.drawable.ic_pause_circle, "Paused",
                "Bank alerts are being ignored until you resume.", "Resume"
            ) {
                settings.paused = false
                refresh()
            }
            else -> Hero(
                Tone.GOOD, R.drawable.ic_check_circle, "All set",
                "Watching $apps bank app${if (apps == 1) "" else "s"}." +
                    if (settings.reviewMode) " You'll be asked before each transaction is sent." else ""
            )
        }
        val fg = window.decorView.themeColor(hero.tone.onContainer)
        findViewById<MaterialCardView>(R.id.heroCard).setCardBackgroundColor(window.decorView.themeColor(hero.tone.container))
        findViewById<ImageView>(R.id.heroIcon).apply {
            setImageResource(hero.icon)
            imageTintList = android.content.res.ColorStateList.valueOf(fg)
        }
        findViewById<TextView>(R.id.heroTitle).apply { text = hero.title; setTextColor(fg) }
        findViewById<TextView>(R.id.heroText).apply { text = hero.text; setTextColor(fg) }
        findViewById<MaterialButton>(R.id.heroAction).apply {
            visibility = if (hero.action == null) View.GONE else View.VISIBLE
            text = hero.action
            setOnClickListener { hero.onAction(this) }
        }
    }

    /** Smaller things worth fixing that don't stop the app working. */
    private fun renderChecks() {
        val list = findViewById<LinearLayout>(R.id.checksList)
        list.removeAllViews()
        fun add(icon: Int, title: String, text: String, action: String, onClick: () -> Unit) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_check, list, false)
            card.findViewById<ImageView>(R.id.checkIcon).badge(icon, Tone.WARN)
            card.findViewById<TextView>(R.id.checkTitle).text = title
            card.findViewById<TextView>(R.id.checkText).text = text
            card.findViewById<Button>(R.id.checkAction).apply {
                this.text = action
                setOnClickListener { onClick() }
            }
            list.addView(card)
        }
        if (Permissions.accessEnabled(this) && !Permissions.batteryExempt(this)) {
            add(R.drawable.ic_battery, "Battery saver may stop alerts", "Some phones stop the app to save battery.", "Fix") {
                Permissions.requestBattery(this)
            }
        }
        if ((settings.reviewMode || settings.alertsOn) && !Notifier.canPost(this)) {
            val what = if (settings.reviewMode) "Approval requests" else "Alerts"
            add(R.drawable.ic_notifications, "Notifications are off", "$what can't be shown.", "Turn on") {
                openNotificationSettings()
            }
        }
        if (settings.token.isNotEmpty() && !settings.tokenVerified && !settings.authError) {
            add(R.drawable.ic_wallet, "Wallet token not checked", "Check it works before transactions arrive.", "Check") {
                openSettings()
            }
        }
    }

    private fun renderCounts(c: IntArray) {
        val attention = c[Store.FAILED] + c[Store.REVIEW] + c[Store.UNPARSED]
        findViewById<TextView>(R.id.countWaiting).text = c[Store.PENDING].toString()
        findViewById<TextView>(R.id.countSynced).text = c[Store.SYNCED].toString()
        findViewById<TextView>(R.id.countAttention).text = attention.toString()
        // The tile only turns red when there's something to do.
        val tile = findViewById<MaterialCardView>(R.id.tileAttention)
        val tone = if (attention > 0) Tone.BAD else null
        tile.setCardBackgroundColor(tile.themeColor(tone?.container ?: M.attr.colorSurfaceContainerLow))
        val fg = tile.themeColor(tone?.onContainer ?: M.attr.colorOnSurface)
        findViewById<TextView>(R.id.countAttention).setTextColor(fg)
        findViewById<TextView>(R.id.labelAttention).setTextColor(
            if (tone == null) tile.themeColor(M.attr.colorOnSurfaceVariant) else fg
        )
        findViewById<View>(R.id.retryButton).visibility = if (c[Store.FAILED] > 0) View.VISIBLE else View.GONE
    }

    private fun renderRecent() {
        val hide = settings.hideDetails
        findViewById<MaterialButton>(R.id.hideButton).apply {
            setIconResource(if (hide) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
            contentDescription = if (hide) "Show details" else "Hide details"
        }
        val list = recentList
        list.removeAllViews()
        val recs = store.recent(25)
        if (recs.isEmpty()) return Pickers.addEmpty(this, list, "Nothing captured yet. New bank alerts show up here.")

        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val accounts = settings.rules().associate { it.accountId to it.accountName }
        val inflater = LayoutInflater.from(this)
        for (r in recs) {
            val row = inflater.inflate(R.layout.item_record, list, false)
            val whenText = iso.parse(r.date)?.let { fmt.format(it) } ?: r.date
            val income = (r.amount ?: 0.0) > 0
            val (icon, tone, state) = when (r.status) {
                Store.UNPARSED -> Triple(R.drawable.ic_help, Tone.WARN, "Not understood · tap to enter it")
                Store.REVIEW -> Triple(R.drawable.ic_schedule, Tone.WARN, "Tap to approve")
                Store.FAILED -> Triple(R.drawable.ic_error, Tone.BAD, r.error ?: "Couldn't be sent")
                Store.PENDING -> Triple(R.drawable.ic_schedule, Tone.NEUTRAL, "Waiting to send · tap to change")
                else -> Triple(
                    if (income) R.drawable.ic_arrow_in else R.drawable.ic_arrow_out,
                    if (income) Tone.GOOD else Tone.NEUTRAL,
                    r.error ?: accounts[r.account] ?: "Sent"
                )
            }
            row.findViewById<ImageView>(R.id.recIcon).badge(icon, tone)
            // Hidden, a row keeps only what kind of thing it is, when, and what it needs.
            row.findViewById<TextView>(R.id.recTitle).text = when {
                r.status == Store.UNPARSED -> if (hide) "Bank alert" else r.note.orEmpty().ifEmpty { "Bank alert" }
                !hide && !r.payee.isNullOrBlank() -> r.payee
                income -> "Money in"
                else -> "Payment"
            }
            row.findViewById<TextView>(R.id.recSub).text = "$whenText · $state"
            row.findViewById<TextView>(R.id.recAmount).apply {
                text = r.amount?.let {
                    if (hide) "••••" else String.format(Locale.US, "%s%,.2f", if (it > 0) "+" else "−", kotlin.math.abs(it))
                }
                setTextColor(themeColor(if (income) M.attr.colorPrimary else M.attr.colorOnSurface))
            }
            // What Wallet will file it under; a waiting record with none is flagged, as it lands as "Unknown".
            row.findViewById<TextView>(R.id.recCategory).apply {
                val waiting = r.status == Store.PENDING || r.status == Store.REVIEW
                when {
                    hide -> return@apply
                    r.categoryName != null -> {
                        text = "● ${r.categoryName}"
                        setTextColor(themeColor(M.attr.colorTertiary))
                    }
                    waiting && r.amount != null -> {
                        text = "● No category: tap to choose one"
                        setTextColor(themeColor(M.attr.colorError))
                    }
                    else -> return@apply
                }
                visibility = View.VISIBLE
            }
            if (r.status != Store.SYNCED) {
                row.setOnClickListener { openRecord(r) }
            } else {
                row.background = null
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

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear sent history?")
            .setMessage("Removes sent and not-understood items from this phone. Nothing in Wallet changes, and anything still waiting stays.")
            .setPositiveButton("Clear") { _, _ ->
                store.clearHistory()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openRecord(r: Rec) {
        when (r.status) {
            Store.UNPARSED -> Dialogs.showFix(this, r) { refresh() }
            Store.REVIEW, Store.PENDING -> Dialogs.showRecord(this, r) { refresh() }
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

    private fun toast(msg: String) = Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_SHORT).show()
}
