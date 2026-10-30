package app.banksync

import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.widget.NestedScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import com.google.android.material.R as M

/** Home: whether everything works, what's queued, and recent activity. Bank apps and options live in [SettingsActivity]. */
class MainActivity : LockedActivity() {
    private lateinit var settings: Settings
    // Looked up each time: the demo swaps in its own queue.
    private val store get() = Store.shown(this)
    private var askedAccessThisLaunch = false

    // The setup guide covers the permission prompts, so don't stack the same dialogs on top of it.
    private var guideShowing = false
    private var justLeftGuide = false

    /** The needs-you tile's colours as last drawn, so it fades to the next ones. */
    private var attentionColors: IntArray? = null

    /** Each recent row's status as last drawn (by record id), so only new and changed rows animate. */
    private var rowStates: Map<Long, String> = emptyMap()
    /** Sent count when "Sync now" was tapped, to say how many it sent. */
    private var syncBase = 0
    /** Transfers whose two sides are showing, by the id of the row standing for them. */
    private val openTransfers = HashSet<Long>()
    private var rowsDrawn = false
    private var rowsDemo = false

    /** The wallpaper-colours setting this screen was drawn with; a change in Settings redraws it on return. */
    private var drawnWithWallpaper = true

    /** What a count tile narrowed Recent activity to; null shows everything. */
    private var filter: Filter? = null

    /** The sheet a tapped or held row opened, and the record it is for. */
    private var sheet: ActionSheet? = null
    private var sheetId = -1L

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

    private val recentList get() = findViewById<LinearLayout>(R.id.recentList)

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

    private fun refresh() {
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

    /** What a count tile can narrow Recent activity to: the heading it shows and what is left out. */
    private enum class Filter(val title: String, val empty: String) {
        WAITING("Waiting to send", "Nothing is waiting to be sent."),
        SENT("Sent to Wallet", "Nothing sent recently."),
        ATTENTION("Needs you", "Nothing needs you right now.");

        /** Whether a row whose deciding record is [r] belongs here, as the tiles count them. */
        fun has(r: Rec) = when (this) {
            WAITING -> r.status == Store.PENDING
            SENT -> r.status == Store.SYNCED
            ATTENTION -> r.status == Store.FAILED || r.status == Store.REVIEW || r.status == Store.UNPARSED
        }
    }

    /** How one Recent activity row (or the sheet's header) shows a record; see [describe]. */
    private class Look(
        val title: String, val sub: String, val note: String?, val amount: String?, val amountColor: Int,
        val chip: String?, val chipKind: Chip, val icon: Drawable?, val symbol: Int?, val monogram: String,
    )

    /** The record's time of day as the phone shows times (12 or 24 hour). */
    private fun timeOf(r: Rec): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return iso.parse(r.date)?.let { DateFormat.getTimeFormat(this).format(it) } ?: r.date
    }

    /** The day a record happened on, in the phone's time zone, for the day headings. */
    private fun dayOf(r: Rec): LocalDate =
        runCatching { Instant.parse(r.date).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrDefault(LocalDate.now())

    /**
     * What a row says about [r] (the outgoing half of a transfer, [partner] the other): who, then category
     * (or where the money moved) and time, the amount (plain out, green in, no sign for a transfer), and a
     * status pill only when it isn't simply sent. [hide] keeps only what kind of thing it is and when.
     */
    private fun describe(r: Rec, partner: Rec?, hide: Boolean): Look {
        val rules = Demo.rules(settings)
        val accounts = rules.associate { it.accountId to it.accountName }
        val currencies = rules.associate { it.accountId to it.accountCurrency }
        val income = (r.amount ?: 0.0) > 0
        val isTransfer = r.pairId != null || r.transferTo != null
        // The half that decides what the row says: the one with the most pressing status.
        val src = if (partner != null && urgency(partner.status) > urgency(r.status)) partner else r
        val unread = r.status == Store.UNPARSED

        val title = when {
            unread -> if (hide) "Bank alert" else r.note.orEmpty().ifEmpty { "Bank alert" }
            isTransfer -> "Transfer"
            !hide && !r.payee.isNullOrBlank() -> r.payee
            income -> "Money in"
            else -> "Payment"
        }
        val what = when {
            unread -> rules.firstOrNull { it.pkg == r.pkg }?.label
            hide -> null
            r.transferTo != null -> {
                // An account no bank app is linked to is named from what Wallet last told us, else looked up once.
                val there = accounts[r.transferTo] ?: settings.accountName(r.transferTo)
                if (there == null) resolveAccountNames()
                "${accounts[r.account] ?: "This account"} → ${there ?: "another account"}"
            }
            r.pairId != null -> {
                // Wallet files a transfer itself; say where the money moved instead.
                val here = accounts[r.account] ?: "this account"
                val there = (partner ?: store.get(r.pairId))?.account?.let { accounts[it] } ?: "another account"
                if (income) "$there → $here" else "$here → $there"
            }
            else -> r.categoryName
        }
        val note = if (hide || isTransfer || unread) null else {
            r.noteEdit ?: Parser.walletText(r.note.orEmpty(), r.payee, currencies[r.account].orEmpty(), settings.includeNote).note
        }?.takeIf { it.isNotBlank() }

        val held = !Demo.active && Uploader.held(this)
        val (chip, kind) = when (src.status) {
            Store.UNPARSED -> "Couldn't read" to Chip.YOU
            Store.REVIEW -> "Approve?" to Chip.YOU
            Store.FAILED -> "Failed" to Chip.BAD
            // Landing in Wallet as "Unknown" is worth a look before it goes.
            Store.PENDING -> when {
                !isTransfer && src.categoryName == null && src.amount != null -> "No category" to Chip.YOU
                held -> "Paused" to Chip.WAIT
                else -> "Waiting" to Chip.WAIT
            }
            else -> when {
                src.patch != null && src.error != null -> "Wallet refused" to Chip.BAD
                SentEdit.deleting(src.patch) -> "Removing" to Chip.WAIT
                src.patch != null -> "Updating" to Chip.WAIT
                else -> null to Chip.WAIT
            }
        }
        val amount = r.amount?.let {
            when {
                hide -> "••••"
                isTransfer -> String.format(Locale.US, "%,.2f", kotlin.math.abs(it))
                else -> String.format(Locale.US, "%s%,.2f", if (it > 0) "+" else "−", kotlin.math.abs(it))
            }
        }
        val color = when {
            isTransfer -> window.decorView.themeColor(M.attr.colorOnSurfaceVariant)
            income -> getColor(R.color.amount_in)
            else -> window.decorView.themeColor(M.attr.colorOnSurface)
        }
        val bankIcon = runCatching { packageManager.getApplicationIcon(r.pkg) }.getOrNull()
        // Without the bank's icon: a symbol for what isn't a purchase, else the payee's initial.
        val symbol = when {
            unread -> R.drawable.ic_help
            isTransfer -> R.drawable.ic_swap
            hide || r.payee.isNullOrBlank() -> if (income) R.drawable.ic_arrow_in else R.drawable.ic_arrow_out
            else -> null
        }
        val monogram = r.payee?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
        return Look(title, listOfNotNull(what, timeOf(r)).joinToString(" · "), note, amount, color, chip, kind, bankIcon, symbol, monogram)
    }

    /** How pressing a record's status is: the more pressing half of a transfer decides what its row says and offers. */
    private fun urgency(status: Int) = when (status) {
        Store.UNPARSED -> 4
        Store.FAILED -> 3
        Store.REVIEW -> 2
        Store.PENDING -> 1
        else -> 0
    }

    private fun renderRecent() {
        val hide = settings.hideDetails
        val list = recentList
        // Switching to or from the demo is a whole new list, not rows changing.
        if (rowsDemo != Demo.active) {
            rowsDemo = Demo.active
            rowStates = emptyMap()
            rowsDrawn = false
        }
        val animateRows = rowsDrawn
        rowsDrawn = true
        list.removeAllViews()
        val f = filter
        findViewById<TextView>(R.id.recentTitle).text = f?.title ?: "Recent activity"
        findViewById<View>(R.id.filterClear).visibility = if (f == null) View.GONE else View.VISIBLE
        // A filtered list looks further back, so a tile's count and its list agree as far as they can.
        val recs = store.recent(if (f == null) 25 else 200)
        val states = HashMap<Long, String>()

        val inflater = LayoutInflater.from(this)
        // A transfer is two records. When both are here, its outgoing half stands for the pair and the
        // incoming half is left out; one half alone (the other is older than the list) shows as itself.
        val byId = recs.associateBy { it.id }
        fun otherHalf(r: Rec): Rec? = r.pairId?.let { byId[it] }?.takeIf { (it.amount ?: 0.0) * (r.amount ?: 0.0) < 0 }
        val shown = recs.filterNot { r -> (r.amount ?: 0.0) > 0 && otherHalf(r) != null }.filter { r ->
            val partner = otherHalf(r)
            f == null || f.has(if (partner != null && urgency(partner.status) > urgency(r.status)) partner else r)
        }
        if (shown.isEmpty()) {
            return Pickers.addEmpty(this, list, f?.empty ?: "Nothing captured yet. New bank alerts show up here.")
        }

        val today = LocalDate.now()
        var day: LocalDate? = null
        var group: LinearLayout? = null
        for ((index, r) in shown.withIndex()) {
            // Each day gets its heading, and its rows a card of their own.
            val d = dayOf(r)
            if (d != day || group == null) {
                day = d
                val heading = inflater.inflate(R.layout.item_day, list, false) as TextView
                heading.text = Days.label(d, today)
                ViewCompat.setAccessibilityHeading(heading, true)
                // The first heading sits close under the section title.
                if (list.childCount == 0) heading.setPadding(heading.paddingLeft, 0, heading.paddingRight, heading.paddingBottom)
                list.addView(heading)
                group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                list.addView(MaterialCardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    addView(group)
                })
            }
            val partner = otherHalf(r)
            val look = describe(r, partner, hide)
            val row = inflater.inflate(R.layout.item_record, group, false)
            val iconView = row.findViewById<ImageView>(R.id.recIcon)
            when {
                look.icon != null -> {
                    // The bank's own icon as it is, without the tinted circle.
                    iconView.setImageDrawable(look.icon)
                    iconView.background = null
                    iconView.imageTintList = null
                    iconView.setPadding(0, 0, 0, 0)
                }
                look.symbol != null -> iconView.setImageResource(look.symbol)
                else -> {
                    iconView.visibility = View.INVISIBLE
                    row.findViewById<TextView>(R.id.recMonogram).apply {
                        visibility = View.VISIBLE
                        text = look.monogram
                    }
                }
            }
            row.findViewById<TextView>(R.id.recTitle).text = look.title
            row.findViewById<TextView>(R.id.recSub).text = look.sub
            row.findViewById<TextView>(R.id.recNote).apply {
                visibility = if (look.note == null) View.GONE else View.VISIBLE
                text = look.note?.let { "Note: $it" }
            }
            row.findViewById<TextView>(R.id.recAmount).apply {
                text = look.amount
                setTextColor(look.amountColor)
            }
            val chip = row.findViewById<TextView>(R.id.recChip)
            chip.chip(look.chip, look.chipKind)
            row.setOnClickListener { showActions(r.id) }
            row.setOnLongClickListener { showActions(r.id); true }
            ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "show details and actions", null)
            if (partner != null) {
                // The chevron shows a transfer's two sides underneath; tapping a side opens the transfer's sheet.
                val halves = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (r.id in openTransfers) View.VISIBLE else View.GONE
                }
                for (half in listOf(r, partner)) halves.addView(transferHalf(inflater, halves, half, r.id, hide))
                val chevron = row.findViewById<ImageView>(R.id.recChevron).apply {
                    visibility = View.VISIBLE
                    rotation = if (r.id in openTransfers) 180f else 0f
                }
                chevron.setOnClickListener {
                    val opening = openTransfers.add(r.id)
                    if (!opening) openTransfers.remove(r.id)
                    animateLayout(list)
                    halves.visibility = if (opening) View.VISIBLE else View.GONE
                    chevron.animate().rotation(if (opening) 180f else 0f).setDuration(200).start()
                }
                group.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(row)
                    addView(halves)
                })
            } else {
                group.addView(row)
            }

            // New rows drop in; a row whose status changed (waiting to sent, say) flashes and its pill pops.
            val key = "${r.status}|${partner?.status}|${r.pairId}|${r.transferTo}|${look.chip}"
            states[r.id] = key
            if (animateRows) {
                val before = rowStates[r.id]
                if (before == null) {
                    row.dropIn(index)
                } else if (before != key) {
                    row.flash()
                    if (look.chip != null) chip.pop()
                }
            }
        }
        rowStates = states
    }

    /** One side of a transfer, as a small row under the combined one; tapping it opens the transfer's sheet ([rowId]). */
    private fun transferHalf(inflater: LayoutInflater, parent: ViewGroup, half: Rec, rowId: Long, hide: Boolean): View {
        val row = inflater.inflate(R.layout.item_transfer_half, parent, false)
        val out = (half.amount ?: 0.0) < 0
        val account = Demo.rules(settings).firstOrNull { it.accountId == half.account }?.accountName ?: "an account"
        row.findViewById<TextView>(R.id.halfTitle).text = if (out) "Out of $account" else "Into $account"
        val state = when (half.status) {
            Store.SYNCED -> "Sent"
            Store.REVIEW -> "Waiting for approval"
            Store.FAILED -> half.error ?: "Couldn't be sent"
            Store.UNPARSED -> "Not understood"
            else -> if (!Demo.active && Uploader.held(this)) "Paused" else "Waiting to send"
        }
        row.findViewById<TextView>(R.id.halfSub).text = "${timeOf(half)} · $state"
        row.findViewById<TextView>(R.id.halfAmount).apply {
            text = half.amount?.let {
                if (hide) "••••" else String.format(Locale.US, "%s%,.2f", if (it > 0) "+" else "−", kotlin.math.abs(it))
            }
            setTextColor(if (out) themeColor(M.attr.colorOnSurface) else context.getColor(R.color.amount_in))
        }
        row.setOnClickListener { showActions(rowId) }
        return row
    }

    /**
     * Asks the bound listener to read what's still in the shade; dismissed alerts can't be recovered.
     * [onDone] runs when it has finished or couldn't start (pulling down waits on it).
     */
    private fun scan(onDone: () -> Unit = {}) {
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

    /**
     * Tapping or holding a row: a sheet with its record's details and what can be done with it. While it's
     * open, nothing is sent, so the record can't go before the user has decided; see [Uploader.hold].
     */
    private fun showActions(id: Long) {
        sheet?.dismiss()
        sheetId = id
        val s = ActionSheet(this) {
            sheet = null
            if (!Demo.active) Uploader.release(this)
            if (!isFinishing && !isDestroyed) refresh()
        }
        s.onTap = ::holdIfWaiting
        sheet = s
        holdIfWaiting()
        renderSheet()
        s.show()
    }

    /** Holds uploads while the open sheet's record could still change; each tap in the sheet counts as activity. */
    private fun holdIfWaiting() {
        if (Demo.active) return
        val r = store.get(sheetId) ?: return
        val halves = listOfNotNull(r, r.pairId?.let { store.get(it) })
        if (halves.any { it.status == Store.PENDING || it.status == Store.REVIEW }) Uploader.hold(this)
    }

    /** Fills the open sheet for its record as it stands now; closes it once the record is gone. */
    private fun renderSheet() {
        val s = sheet ?: return
        val r = store.get(sheetId) ?: return s.dismiss()
        val partner = r.pairId?.let { store.get(it) }
        // The half that needs something decides what can be done, as it decides what the row says.
        val src = if (partner != null && urgency(partner.status) > urgency(r.status)) partner else r
        val sent = r.status == Store.SYNCED && (partner == null || partner.status == Store.SYNCED)

        val actions = ArrayList<ActionSheet.Action>()
        fun add(icon: Int, text: String, hint: String? = null, danger: Boolean = false, close: Boolean = true, run: () -> Unit) {
            actions += ActionSheet.Action(icon, text, hint, danger, close, run)
        }
        val notTransfer = { add(R.drawable.ic_swap, "Not a transfer", "Both sides go to Wallet as separate records", close = false) { notTransfer(r) } }
        when (src.status) {
            Store.PENDING -> {
                add(R.drawable.ic_send, "Send now") { sendNow(src, partner) }
                add(R.drawable.ic_edit, "Change account, category or note", close = false) { Dialogs.showRecord(this, src) { holdIfWaiting(); refresh() } }
                // A transfer side waiting for approval would hold its pair back, so only a lone record waits.
                if (r.pairId != null) notTransfer()
                else add(R.drawable.ic_pause_circle, "Don't send yet", "It waits for you to approve it", close = false) { holdBack(src) }
            }
            Store.REVIEW -> {
                add(R.drawable.ic_send, "Send now") { sendNow(src, partner) }
                add(R.drawable.ic_edit, "Review and change", close = false) { Dialogs.showRecord(this, src) { holdIfWaiting(); refresh() } }
                if (r.pairId != null) notTransfer()
                add(R.drawable.ic_delete, "Discard", "It won't be sent to Wallet", danger = true) { discard(src) }
            }
            Store.FAILED -> {
                add(R.drawable.ic_sync, "Retry now", src.error) { retry(src) }
                add(R.drawable.ic_delete, "Discard", "It won't be sent to Wallet", danger = true) {
                    store.delete(src.id)
                    refresh()
                }
            }
            Store.UNPARSED -> {
                add(R.drawable.ic_edit, "Fix", "Enter the amount and account yourself", close = false) { openRecord(src) }
                add(R.drawable.ic_delete, "Delete", danger = true) { deleteUnread(src) }
            }
        }
        // A sent record Wallet gave an id for can be changed or removed there; a transfer is Wallet's to file.
        if (src.status == Store.SYNCED && src.patch != null) {
            val deleting = SentEdit.deleting(src.patch)
            if (src.error != null) {
                add(R.drawable.ic_sync, "Try again", src.error) { retryEdit(src) }
            }
            if (deleting) {
                add(R.drawable.ic_wallet, "Keep it in Wallet", "Cancels the removal if it hasn't gone yet") { dropEdit(src) }
            } else if (src.error != null) {
                add(R.drawable.ic_wallet, "Forget my change", "Wallet keeps what it has; this phone keeps showing your change") { dropEdit(src) }
            }
        }
        if (SentEdit.editable(src) && !SentEdit.deleting(src.patch)) {
            add(R.drawable.ic_edit, "Edit in Wallet", "Amount, payee, account, category or note. Replaces what Wallet has", close = false) {
                Dialogs.showEditSent(this, src) { refresh() }
            }
            add(R.drawable.ic_delete, "Remove from Wallet…", "Deletes the record in Wallet too", danger = true, close = false) {
                confirmDeleteSent(src)
            }
        }
        // A not-understood alert's Delete above is the same thing.
        if (src.status != Store.UNPARSED) {
            add(R.drawable.ic_smartphone, "Remove from this phone…", if (sent) "It stays in Wallet" else null, danger = true, close = false) {
                confirmRemove(r, partner)
            }
        }

        val paused = if (!Demo.active && Uploader.held(this)) {
            val min = Uploader.holdMs(settings) / 60_000
            "Sending is paused while this is open. It carries on when you close it, or after " +
                "$min minute${if (min == 1L) "" else "s"} without a tap."
        } else null
        // The header is the row in full: the user opened this one, so hidden details show here.
        val look = describe(r, partner?.takeIf { (it.amount ?: 0.0) * (r.amount ?: 0.0) < 0 }, hide = false)
        val header = ActionSheet.Header(look.icon, look.symbol, look.monogram, look.title, look.sub, look.amount, look.amountColor)
        val facts = Dialogs.facts(this, r, partner)
        // Paused matters more while it lasts; otherwise a sent record that can't be changed says why.
        val cant = SentEdit.whyNot(src).takeIf { paused == null }
        if (cant != null) s.fill(header, cant, facts, actions, R.drawable.ic_info) else s.fill(header, paused, facts, actions)
    }

    /** The sheet's Send now: approves a record waiting for approval (both sides of a transfer) and sends straight away. */
    private fun sendNow(src: Rec, partner: Rec?) {
        for (h in listOfNotNull(src, partner).filter { it.status == Store.REVIEW }) {
            if (!store.approve(h.id)) return toast("Too late: it was already sent or removed")
            Notifier.cancelReview(this, h.id)
        }
        if (Demo.active) {
            listOfNotNull(src, partner).forEach { Demo.markSent(store, store.get(it.id) ?: it) }
            toast("Demo: marked as sent. Nothing went to Wallet.")
            return refresh()
        }
        syncBase = store.counts()[Store.SYNCED]
        Uploader.requested = true
        Uploader.release(this, now = true)
        refresh()
    }

    /** "Don't send yet": the record waits for approval, as in "ask before sending" mode. */
    private fun holdBack(r: Rec) {
        if (!store.holdBack(r.id)) toast("Too late: it was already sent")
        refresh()
    }

    private fun discard(r: Rec) {
        if (!store.discardWaiting(r.id)) toast("Too late: it was already sent")
        Notifier.cancelReview(this, r.id)
        refresh()
    }

    private fun retry(r: Rec) {
        store.setStatus(r.id, Store.PENDING)
        if (!Demo.active) Uploader.release(this, now = true)
        refresh()
    }

    /** Splits a transfer back into two normal records; the hold keeps them until the sheet closes. */
    private fun notTransfer(r: Rec) {
        store.unpair(r.id)
        if (!Demo.active) Uploader.schedule(this, 0, replace = true)
        toast("Both sides will go to Wallet as separate records")
        refresh()
    }

    /**
     * From a row's actions: forget one record on this phone only. With it gone, a scan can capture the bank's
     * alert again if it's still in the notification shade, which is how to redo one by hand.
     */
    private fun confirmRemove(r: Rec, other: Rec? = null) {
        val sent = r.status == Store.SYNCED && (other == null || other.status == Store.SYNCED)
        MaterialAlertDialogBuilder(this)
            .setTitle(if (other != null) "Remove this transfer?" else "Remove from this list?")
            .setMessage(
                (if (sent) "It stays in Wallet; only this phone forgets it. " else "It won't be sent to Wallet. ") +
                    (if (other != null) "Both sides are removed. " else "") +
                    "If the bank's notification is still in your notification shade, Scan notifications picks it up again as new."
            )
            .setPositiveButton("Remove") { _, _ ->
                // A transfer is removed with both sides; a lone half leaves its partner as a normal record.
                store.unpair(r.id)
                store.delete(r.id)
                Notifier.cancelReview(this, r.id)
                if (other != null) {
                    store.delete(other.id)
                    Notifier.cancelReview(this, other.id)
                }
                refresh()
                if (Demo.active) return@setPositiveButton
                Snackbar.make(findViewById(R.id.root), "Removed", Snackbar.LENGTH_LONG).setAction("Scan now") { scan() }.show()
            }
            .setNegativeButton("Cancel", null)
            .show()
            .danger(DialogInterface.BUTTON_POSITIVE)
    }

    /** The sheet's "Remove from Wallet": after asking, queues the record's deletion in Wallet; the row goes once Wallet has done it. */
    private fun confirmDeleteSent(r: Rec) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove from Wallet?")
            .setMessage(
                "The record is deleted in Wallet with the next upload, then from this phone. " +
                    "Until it goes, you can keep it from the same menu."
            )
            .setPositiveButton("Remove") { _, _ ->
                sheet?.dismiss()
                if (Demo.active) {
                    // Nothing goes to Wallet in the demo; the record just goes.
                    store.delete(r.id)
                } else {
                    if (!store.deleteSent(r.id)) return@setPositiveButton toast("This record can't be removed from here")
                    Uploader.schedule(this)
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
            .danger(DialogInterface.BUTTON_POSITIVE)
    }

    /** Sends a change or removal Wallet refused once more. */
    private fun retryEdit(r: Rec) {
        // It goes with the next upload, as an edit does; a job already waiting takes it along.
        if (store.retryEdit(r.id) && !Demo.active) Uploader.schedule(this)
        refresh()
    }

    /** Forgets a change or removal that hasn't reached Wallet. */
    private fun dropEdit(r: Rec) {
        store.dropEdit(r.id)
        refresh()
    }

    /** The details dialog's Delete: an alert that wasn't understood goes without asking, as from the Fix dialog. */
    private fun deleteUnread(r: Rec) {
        store.delete(r.id)
        refresh()
        if (Demo.active) return
        Snackbar.make(findViewById(R.id.root), "Deleted", Snackbar.LENGTH_LONG).setAction("Scan now") { scan() }.show()
    }

    private var namesRequested = false

    /** Asks Wallet once for account names, so a transfer to an account no bank app is linked to can say where. */
    private fun resolveAccountNames() {
        if (namesRequested || Demo.active || settings.token.isEmpty()) return
        namesRequested = true
        Thread {
            val all = runCatching { WalletApi.accounts(settings.token) }.getOrNull() ?: return@Thread
            settings.rememberAccountNames(all)
            runOnUiThread { if (!isFinishing && !isDestroyed) refresh() }
        }.start()
    }

    private fun openRecord(r: Rec) {
        when (r.status) {
            Store.UNPARSED ->
                if (!Demo.active && Parser.isCashIn(r.note.orEmpty())) Dialogs.showCashIn(this, r) { refresh() }
                else Dialogs.showFix(this, r) { refresh() }
            Store.REVIEW, Store.PENDING -> Dialogs.showRecord(this, r) { refresh() }
            Store.SYNCED -> if (SentEdit.editable(r)) Dialogs.showEditSent(this, r) { refresh() }
            Store.FAILED -> MaterialAlertDialogBuilder(this)
                .setTitle("Couldn't be sent")
                .setMessage(r.error ?: "Unknown error")
                .setPositiveButton("Retry") { _, _ ->
                    store.setStatus(r.id, Store.PENDING)
                    if (!Demo.active) Uploader.schedule(this, 0, replace = true)
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

    companion object {
        /** The record a "picked up" notification's Change button opens. */
        const val EXTRA_RECORD = "app.banksync.RECORD"

        /** Debug builds open straight into the demo with this set (`am start ... --ez demo true`); release builds ignore it. */
        const val EXTRA_DEMO = "demo"
    }
}
