package app.banksync

import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.NestedScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.R as M

/** Home: whether everything works, what's queued, and recent activity. Bank apps and options live in [SettingsActivity]. */
class MainActivity : LockedActivity() {
    internal lateinit var settings: Settings
    // Looked up each time: the demo swaps in its own queue.
    internal val store get() = Store.shown(this)
    private var askedAccessThisLaunch = false

    // The setup guide covers the permission prompts, so don't stack the same dialogs on top of it.
    private var guideShowing = false
    private var justLeftGuide = false

    /** The needs-you tile's colours as last drawn, so it fades to the next ones. */
    private var attentionColors: IntArray? = null

    /** Each recent row's status as last drawn (by record id), so only new and changed rows animate. */
    internal var rowStates: Map<Long, String> = emptyMap()
    /** Sent count when "Sync now" was tapped, to say how many it sent. */
    internal var syncBase = 0
    /** Transfers whose two sides are showing, by the id of the row standing for them. */
    internal val openTransfers = HashSet<Long>()
    internal var rowsDrawn = false
    internal var rowsDemo = false

    /** The wallpaper-colours setting this screen was drawn with; a change in Settings redraws it on return. */
    private var drawnWithWallpaper = true

    /** What a count tile narrowed Recent activity to; null shows everything. */
    internal var filter: Filter? = null

    /** The sheet a tapped or held row opened, and the record it is for. */
    internal var sheet: ActionSheet? = null
    internal var sheetId = -1L

    /** The set of checks showing last, so they fade in and out only when it changes. */
    private var checksKey: String? = null

    /** What the status card showed last, so it animates only when that changes. */
    private var heroKey: String? = null
    private var heroFill: Int? = null
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

    internal val recentList get() = findViewById<LinearLayout>(R.id.recentList)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        drawnWithWallpaper = settings.wallpaperColors
        applyInsets(findViewById(R.id.root), findViewById(R.id.scroll))

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.main)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menuSettings -> openSettings()
                    R.id.menuGuide -> openGuide()
                    R.id.menuTest -> Dialogs.showTest(this@MainActivity)
                    R.id.menuExport -> exportSettings.launch("bank-to-wallet-settings.json")
                    R.id.menuImport -> importSettings.launch(arrayOf("*/*"))
                    R.id.menuDemo -> if (Demo.active) stopDemo() else startDemo()
                    R.id.menuScan -> scan()
                    R.id.menuHide -> {
                        settings.hideDetails = !settings.hideDetails
                        refresh()
                    }
                    R.id.menuClear -> confirmClear()
                }
                true
            }
        }

        findViewById<Button>(R.id.syncButton).setOnClickListener {
            if (Demo.active) {
                toast("Demo: ${Demo.sendAll()} marked as sent. Nothing went to Wallet.")
                return@setOnClickListener refresh()
            }
            syncBase = store.counts()[Store.SYNCED]
            Uploader.requested = true
            Uploader.schedule(this, 0, replace = true)
            renderSync()
        }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            val n = store.retryFailed()
            if (n > 0 && !Demo.active) Uploader.schedule(this, 0, replace = true)
            toast(if (n > 0) "Retrying $n record(s)" else "Nothing to retry")
            refresh()
        }
        // Each count tile narrows the list to what it counts; tapping it again, or Show all, undoes it.
        for ((tile, f) in listOf(R.id.tileWaiting to Filter.WAITING, R.id.tileSynced to Filter.SENT, R.id.tileAttention to Filter.ATTENTION)) {
            findViewById<View>(tile).setOnClickListener { showOnly(if (filter == f) null else f) }
        }
        findViewById<View>(R.id.filterClear).setOnClickListener { showOnly(null) }
        // Pulling down scans the shade for missed alerts, as "Scan for missed alerts" in the menu does.
        findViewById<SwipeRefreshLayout>(R.id.pull).apply {
            setColorSchemeColors(themeColor(M.attr.colorPrimary))
            setProgressBackgroundColorSchemeColor(themeColor(M.attr.colorSurfaceContainerHigh))
            setOnRefreshListener { scan { isRefreshing = false } }
        }

        if (intent.getBooleanExtra(EXTRA_DEMO, false) && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds only, for tools/screenshots.ps1: straight into the demo, with no guide or prompts.
            Demo.start(this)
        } else if (!settings.onboardingDone) {
            // A new install starts on the setup guide, not on a list of changes.
            settings.seenVersion = Changelog.installed(this).first
            openGuide()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onUnlocked() = openFromNotification()

    /**
     * "Change" on a "picked up" notification: opens that record, once. Only a real record still
     * waiting can be opened, so a stale or forged id just lands on the home screen.
     */
    private fun openFromNotification() {
        val id = intent.getLongExtra(EXTRA_RECORD, -1)
        if (id < 0 || isLocked) return
        intent.removeExtra(EXTRA_RECORD)
        if (Demo.active) return
        val rec = Store.get(this).get(id)
        if (rec == null || (rec.status != Store.PENDING && rec.status != Store.REVIEW)) {
            return toast("Too late to change: it was already sent")
        }
        Dialogs.showRecord(this, rec) { refresh() }
    }

    override fun onStart() {
        super.onStart()
        // Uploads and new alerts happen off this screen; redraw when they land.
        Uploader.onChange = { runOnUiThread { if (!isFinishing && !isDestroyed) refresh() } }
    }

    override fun onStop() {
        Uploader.onChange = null
        super.onStop()
    }

    override fun onDestroy() {
        // A sheet lost with the screen (rotation, say) shouldn't keep uploads held for its full length.
        sheet?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (settings.wallpaperColors != drawnWithWallpaper) return recreate()
        Summary.schedule(this)
        if (!Demo.active) Uploader.releaseTransfers(this)
        openFromNotification()
        // Binding takes a moment, so look again shortly after asking for a rebind.
        if (Permissions.reconnectIfStopped(this)) window.decorView.postDelayed({ if (!isFinishing) refresh() }, 2_000)
        refresh()
        when {
            // The demo needs no permissions, so don't ask for them over it.
            guideShowing || lockPending || Demo.active -> {}
            justLeftGuide -> justLeftGuide = false
            else -> onboard()
        }
    }

    private fun openGuide() {
        guideShowing = true
        guide.launch(Intent(this, OnboardingActivity::class.java))
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun startDemo() {
        Demo.start(this)
        refresh()
        toast("Demo data: made-up alerts. Nothing is sent to Wallet.")
    }

    private fun stopDemo() {
        Demo.stop()
        refresh()
    }

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
            Permissions.askAccess(this)
        } else if (!Permissions.batteryExempt(this) && !settings.batteryAsked) {
            settings.batteryAsked = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Keep it running reliably")
                .setMessage(
                    "Some phones stop notification listeners to save battery, which means missed " +
                        "transactions. The app does no background work of its own, so allowing " +
                        "unrestricted battery use costs almost nothing.\n\n" +
                        "On the next screen, tap Battery (or App battery usage) and choose Unrestricted."
                )
                .setPositiveButton("Open settings") { _, _ -> Permissions.openBatterySettings(this) }
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

    internal fun refresh() {
        val c = store.counts()
        findViewById<MaterialToolbar>(R.id.toolbar).menu.apply {
            findItem(R.id.menuDemo)?.title = if (Demo.active) "Exit demo" else "Try with demo data"
            findItem(R.id.menuHide)?.isChecked = settings.hideDetails
            // Clearing only makes sense with something sent or not understood to clear.
            findItem(R.id.menuClear)?.isVisible = c[Store.SYNCED] + c[Store.UNPARSED] > 0
        }
        renderHero(c)
        renderChecks()
        renderCounts(c)
        renderRecent()
        renderSync()
        renderSheet()
    }

    /** Sync now: shows waiting for a connection, then sending, and says what happened when the job ends. */
    private fun renderSync() {
        val button = findViewById<MaterialButton>(R.id.syncButton)
        val bar = findViewById<View>(R.id.syncProgress)
        if (Demo.active) Uploader.requested = false
        // A request with no job left has been answered, one way or another.
        if (Uploader.requested && !Uploader.running && !Uploader.waiting(this)) {
            Uploader.requested = false
            val sent = store.counts()[Store.SYNCED] - syncBase
            toast(
                when {
                    sent > 0 -> "Sent $sent record${if (sent == 1) "" else "s"} to Wallet"
                    settings.authError -> "Wallet rejected your token"
                    store.counts()[Store.FAILED] > 0 -> "Couldn't send. Tap a failed row to see why"
                    else -> "Nothing was sent. It will try again when it can"
                }
            )
        }
        val busy = Uploader.requested || Uploader.running
        // Nothing to send means no button at all; otherwise it says how many it will send.
        val waiting = store.counts()[Store.PENDING]
        val held = !Demo.active && Uploader.held(this)
        val show = busy || waiting > 0
        if ((button.visibility == View.VISIBLE) != show) animateLayout(button.parent.parent as ViewGroup)
        button.visibility = if (show) View.VISIBLE else View.GONE
        button.isEnabled = !busy && !held && waiting > 0
        button.text = when {
            Uploader.running -> "Sending…"
            held && waiting > 0 -> "Sending paused"
            Uploader.requested -> "Waiting for network…"
            waiting > 0 -> "Send $waiting waiting"
            else -> "Nothing to send"
        }
        bar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    /** The one thing that matters most right now, worst problem first, with the button that fixes it. */
    private fun renderHero(c: IntArray) {
        val apps = settings.rules().map { it.pkg }.distinct().size
        val waiting = c[Store.PENDING]
        /** [compact]: when all is well, the card shrinks to its icon and one line. */
        class Hero(
            val tone: Tone, val icon: Int, val title: String, val text: String, val action: String? = null,
            val compact: Boolean = false, val onAction: (Button) -> Unit = {},
        )
        val hero = when {
            Demo.active -> Hero(
                Tone.NEUTRAL, R.drawable.ic_visibility, "Demo mode",
                "Made-up bank apps and transactions. Tap a row to try it. Nothing is read from your " +
                    "notifications or sent to Wallet.", "Exit demo"
            ) { stopDemo() }
            !Permissions.accessEnabled(this) -> Hero(
                Tone.BAD, R.drawable.ic_error, "Not reading alerts",
                "Turn on notification access so the app can see your bank's alerts.", "Turn on"
            ) { Permissions.askAccess(this) }
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
                Tone.GOOD, R.drawable.ic_check_circle,
                "Watching $apps bank app${if (apps == 1) "" else "s"}" + if (settings.reviewMode) " · asks before sending" else "",
                "", compact = true
            )
        }
        val fg = window.decorView.themeColor(hero.tone.onContainer)
        val card = findViewById<MaterialCardView>(R.id.heroCard)
        val fill = window.decorView.themeColor(hero.tone.container)
        // Only a real change plays the animation, not every refresh; the first draw just appears.
        val key = "${hero.tone}|${hero.icon}|${hero.compact}|${hero.title}"
        val changed = heroKey != null && heroKey != key
        heroKey = key
        if (changed) {
            // The card changes shape and everything below it slides along, while old text fades out and new fades in.
            animateLayout(card.parent as ViewGroup)
            val from = heroFill
            if (from != null && from != fill) fadeColors(intArrayOf(from), intArrayOf(fill)) { card.setCardBackgroundColor(it[0]) }
        }
        if (!changed || heroFill == fill) card.setCardBackgroundColor(fill)
        heroFill = fill
        fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
        findViewById<LinearLayout>(R.id.heroBody).apply {
            orientation = if (hero.compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (hero.compact) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.NO_GRAVITY
            val pad = px(if (hero.compact) 14 else 20)
            setPadding(pad, pad, pad, pad)
        }
        findViewById<ImageView>(R.id.heroIcon).apply {
            setImageResource(hero.icon)
            imageTintList = android.content.res.ColorStateList.valueOf(fg)
            layoutParams = layoutParams.apply { width = px(if (hero.compact) 24 else 40); height = width }
            if (changed) pop()
        }
        findViewById<TextView>(R.id.heroTitle).apply {
            text = hero.title
            setTextColor(fg)
            val style = TypedValue().also { theme.resolveAttribute(if (hero.compact) M.attr.textAppearanceTitleMedium else M.attr.textAppearanceHeadlineSmall, it, true) }
            setTextAppearance(style.resourceId)
            setTextColor(fg)
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                width = if (hero.compact) 0 else LinearLayout.LayoutParams.MATCH_PARENT
                weight = if (hero.compact) 1f else 0f
                topMargin = if (hero.compact) 0 else px(12)
                marginStart = if (hero.compact) px(12) else 0
            }
        }
        findViewById<TextView>(R.id.heroText).apply {
            text = hero.text
            setTextColor(fg)
            visibility = if (hero.compact) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.heroAction).apply {
            visibility = if (hero.action == null) View.GONE else View.VISIBLE
            text = hero.action
            setOnClickListener { hero.onAction(this) }
        }
    }

    /** Smaller things worth fixing that don't stop the app working. */
    private fun renderChecks() {
        val list = findViewById<LinearLayout>(R.id.checksList)
        val battery = !Demo.active && Permissions.accessEnabled(this) && !Permissions.batteryExempt(this)
        val notifications = !Demo.active && (settings.reviewMode || settings.alertsOn || settings.capturedOn) && !Notifier.canPost(this)
        val token = !Demo.active && settings.token.isNotEmpty() && !settings.tokenVerified && !settings.authError
        val key = "$battery|$notifications|$token"
        if (checksKey != null && checksKey != key) animateLayout(list.parent as ViewGroup)
        checksKey = key
        list.removeAllViews()
        if (Demo.active) return
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
        if (battery) {
            add(R.drawable.ic_battery, "Battery saver may stop alerts", "Some phones stop the app to save battery. Set Battery to Unrestricted.", "Fix") {
                Permissions.openBatterySettings(this)
            }
        }
        if (notifications) {
            val what = if (settings.reviewMode) "Approval requests" else "Alerts"
            add(R.drawable.ic_notifications, "Notifications are off", "$what can't be shown.", "Turn on") {
                openNotificationSettings()
            }
        }
        if (token) {
            add(R.drawable.ic_wallet, "Wallet token not checked", "Check it works before transactions arrive.", "Check") {
                openSettings()
            }
        }
    }

    private fun renderCounts(c: IntArray) {
        val attention = c[Store.FAILED] + c[Store.REVIEW] + c[Store.UNPARSED]
        // Everything fixed: the "needs you" notification would only be stale now.
        if (!Demo.active && c[Store.UNPARSED] == 0) Notifier.cancelUnparsed(this)
        val muted = window.decorView.themeColor(M.attr.colorOnSurfaceVariant)
        val plain = window.decorView.themeColor(M.attr.colorOnSurface)
        /** One tile: its count in [color] while there is something to count; the one filtering the list is outlined. */
        fun tile(id: Int, count: TextView, icon: ImageView, n: Int, color: Int, f: Filter, what: String) {
            count.countTo(n)
            count.setTextColor(if (n > 0) color else plain)
            icon.imageTintList = ColorStateList.valueOf(if (n > 0) color else muted)
            findViewById<MaterialCardView>(id).apply {
                val on = filter == f
                isSelected = on
                strokeWidth = if (on) (2 * resources.displayMetrics.density).toInt() else 0
                strokeColor = themeColor(M.attr.colorPrimary)
                contentDescription = "$n $what. " + if (on) "Showing only these; tap to show everything" else "Tap to show only these"
            }
        }
        tile(R.id.tileWaiting, findViewById(R.id.countWaiting), findViewById(R.id.iconWaiting), c[Store.PENDING], getColor(R.color.status_wait), Filter.WAITING, "waiting")
        tile(R.id.tileSynced, findViewById(R.id.countSynced), findViewById(R.id.iconSynced), c[Store.SYNCED], getColor(R.color.amount_in), Filter.SENT, "sent")
        // Needs you: the count, icon and label turn the error colour; the tile itself stays calm.
        val error = window.decorView.themeColor(M.attr.colorError)
        tile(R.id.tileAttention, findViewById(R.id.countAttention), findViewById(R.id.iconAttention), attention, error, Filter.ATTENTION, "need you")
        val label = findViewById<TextView>(R.id.labelAttention)
        val colors = intArrayOf(if (attention > 0) error else muted)
        val before = attentionColors
        attentionColors = colors
        if (before != null && !before.contentEquals(colors)) fadeColors(before, colors) { label.setTextColor(it[0]) } else label.setTextColor(colors[0])

        val retry = findViewById<View>(R.id.retryButton)
        val failed = c[Store.FAILED] > 0
        if ((retry.visibility == View.VISIBLE) != failed) animateLayout(retry.parent.parent as ViewGroup)
        retry.visibility = if (failed) View.VISIBLE else View.GONE
    }

    /** Narrows Recent activity to what one count tile counts, or shows everything again ([f] null). */
    private fun showOnly(f: Filter?) {
        filter = f
        animateLayout(recentList.parent as ViewGroup)
        renderCounts(store.counts())
        renderRecent()
        if (f != null) findViewById<NestedScrollView>(R.id.scroll).smoothScrollTo(0, (recentList.parent as View).top + findViewById<View>(R.id.recentTitle).top)
    }

    /**
     * Asks the bound listener to read what's still in the shade; dismissed alerts can't be recovered.
     * [onDone] runs when it has finished or couldn't start (pulling down waits on it).
     */
    internal fun scan(onDone: () -> Unit = {}) {
        if (Demo.active) return toast("Not available in the demo").also { onDone() }
        val listener = BankNotificationListener.instance
            ?: return toast("Notification access isn't active. Turn it on first.").also { onDone() }
        Thread {
            val found = listener.catchUp()
            runOnUiThread {
                onDone()
                toast(if (found > 0) "Found $found missed alert${if (found == 1) "" else "s"}" else "No missed alerts found")
                refresh()
            }
        }.start()
    }

    private fun confirmClear() {
        val c = store.counts()
        val n = c[Store.SYNCED] + c[Store.UNPARSED]
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear $n item${if (n == 1) "" else "s"}?")
            .setMessage(
                "Removes the $n sent or not-understood item${if (n == 1) "" else "s"} from this list. " +
                    "Nothing in Wallet changes, and anything waiting, failed or needing you stays.\n\n" +
                    "If the bank's alert is still in your notification shade, a scan would pick it up again and send it twice."
            )
            .setPositiveButton("Clear") { _, _ ->
                store.clearHistory()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
            .danger(DialogInterface.BUTTON_POSITIVE)
    }

    private var namesRequested = false

    /** Asks Wallet once for account names, so a transfer to an account no bank app is linked to can say where. */
    internal fun resolveAccountNames() {
        if (namesRequested || Demo.active || settings.token.isEmpty()) return
        namesRequested = true
        Thread {
            val all = runCatching { WalletApi.accounts(settings.token) }.getOrNull() ?: return@Thread
            settings.rememberAccountNames(all)
            runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
        }.start()
    }

    internal fun toast(msg: String) = Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_SHORT).show()

    companion object {
        /** The record a "picked up" notification's Change button opens. */
        const val EXTRA_RECORD = "app.banksync.RECORD"

        /** Debug builds open straight into the demo with this set (`am start ... --ez demo true`); release builds ignore it. */
        const val EXTRA_DEMO = "demo"
    }
}
