package app.banksync

import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
        findViewById<Button>(R.id.scanButton).apply {
            setOnClickListener { scan() }
            TooltipCompat.setTooltipText(this, "Reads bank alerts still in your notification shade that weren't picked up")
        }
        findViewById<Button>(R.id.clearButton).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            val n = store.retryFailed()
            if (n > 0 && !Demo.active) Uploader.schedule(this, 0, replace = true)
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

    override fun onResume() {
        super.onResume()
        Summary.schedule(this)
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
        findViewById<MaterialToolbar>(R.id.toolbar).menu.findItem(R.id.menuDemo)?.title =
            if (Demo.active) "Exit demo" else "Try with demo data"
        val c = store.counts()
        renderHero(c)
        renderChecks()
        renderCounts(c)
        renderRecent()
        renderSync()
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
        // Nothing to send means nothing to press; otherwise say how many it will send.
        val waiting = store.counts()[Store.PENDING]
        button.isEnabled = !busy && waiting > 0
        button.text = when {
            Uploader.running -> "Sending…"
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
        // Blue while something waits, green once something is sent; a zero stays plain, its icon muted.
        val waitColor = if (c[Store.PENDING] > 0) getColor(R.color.mark_wait) else null
        findViewById<TextView>(R.id.countWaiting).apply {
            countTo(c[Store.PENDING])
            setTextColor(waitColor ?: themeColor(M.attr.colorOnSurface))
        }
        findViewById<ImageView>(R.id.iconWaiting).apply {
            imageTintList = ColorStateList.valueOf(waitColor ?: themeColor(M.attr.colorOnSurfaceVariant))
        }
        val sentColor = if (c[Store.SYNCED] > 0) getColor(R.color.amount_in) else null
        findViewById<TextView>(R.id.countSynced).apply {
            countTo(c[Store.SYNCED])
            setTextColor(sentColor ?: themeColor(M.attr.colorOnSurface))
        }
        findViewById<ImageView>(R.id.iconSynced).apply {
            imageTintList = ColorStateList.valueOf(sentColor ?: themeColor(M.attr.colorOnSurfaceVariant))
        }
        findViewById<TextView>(R.id.countAttention).countTo(attention)
        // The tile only turns red when there's something to do, and fades there.
        val tile = findViewById<MaterialCardView>(R.id.tileAttention)
        val tone = if (attention > 0) Tone.BAD else null
        val fg = tile.themeColor(tone?.onContainer ?: M.attr.colorOnSurface)
        val colors = intArrayOf(
            tile.themeColor(tone?.container ?: M.attr.colorSurfaceContainerLow), fg,
            if (tone == null) tile.themeColor(M.attr.colorOnSurfaceVariant) else fg,
        )
        fun paint(c: IntArray) {
            tile.setCardBackgroundColor(c[0])
            findViewById<TextView>(R.id.countAttention).setTextColor(c[1])
            findViewById<TextView>(R.id.labelAttention).setTextColor(c[2])
            findViewById<ImageView>(R.id.iconAttention).imageTintList = ColorStateList.valueOf(c[2])
        }
        val before = attentionColors
        attentionColors = colors
        if (before != null && !before.contentEquals(colors)) fadeColors(before, colors, ::paint) else paint(colors)
        findViewById<View>(R.id.retryButton).visibility = if (c[Store.FAILED] > 0) View.VISIBLE else View.GONE
        // Clearing only makes sense with something sent or not understood to clear.
        findViewById<View>(R.id.clearButton).visibility = if (c[Store.SYNCED] + c[Store.UNPARSED] > 0) View.VISIBLE else View.GONE
    }

    private fun renderRecent() {
        val hide = settings.hideDetails
        findViewById<MaterialButton>(R.id.hideButton).apply {
            setIconResource(if (hide) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
            contentDescription = if (hide) "Show details" else "Hide details"
        }
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
        val recs = store.recent(25)
        val states = HashMap<Long, String>()
        if (recs.isEmpty()) return Pickers.addEmpty(this, list, "Nothing captured yet. New bank alerts show up here.")

        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val accounts = Demo.rules(settings).associate { it.accountId to it.accountName }
        val currencies = Demo.rules(settings).associate { it.accountId to it.accountCurrency }
        val inflater = LayoutInflater.from(this)
        // A transfer is two records. When both are here, its outgoing half stands for the pair and the
        // incoming half is left out; one half alone (the other is older than the list) shows as itself.
        val byId = recs.associateBy { it.id }
        fun otherHalf(r: Rec): Rec? = r.pairId?.let { byId[it] }?.takeIf { (it.amount ?: 0.0) * (r.amount ?: 0.0) < 0 }
        val shown = recs.filterNot { r -> (r.amount ?: 0.0) > 0 && otherHalf(r) != null }
        fun urgency(s: Int) = when (s) { Store.FAILED -> 3; Store.REVIEW -> 2; Store.PENDING -> 1; else -> 0 }
        for ((index, r) in shown.withIndex()) {
            val row = inflater.inflate(R.layout.item_record, list, false)
            val whenText = iso.parse(r.date)?.let { fmt.format(it) } ?: r.date
            val income = (r.amount ?: 0.0) > 0
            val partner = otherHalf(r)
            // One sent to another account by itself is a transfer too, with no second record here.
            val isTransfer = r.pairId != null || r.transferTo != null
            // The half that decides what the row says: the one with the most pressing status.
            val src = if (partner != null && urgency(partner.status) > urgency(r.status)) partner else r
            val status = src.status
            // Blue clock: waiting. Amber: needs you. Dark red: failed. Purple: transfer. Green arrow in, red arrow out.
            val (icon, mark, state) = when (status) {
                Store.UNPARSED -> Triple(R.drawable.ic_help, Mark.ATTENTION, (src.error ?: "Not understood") + " · tap to fix")
                Store.REVIEW -> Triple(R.drawable.ic_schedule, Mark.ATTENTION, "Tap to approve")
                Store.FAILED -> Triple(R.drawable.ic_error, Mark.FAILED, src.error ?: "Couldn't be sent")
                Store.PENDING -> Triple(
                    if (isTransfer) R.drawable.ic_swap else R.drawable.ic_schedule,
                    if (isTransfer) Mark.TRANSFER else Mark.WAIT,
                    "Waiting to send · tap to change"
                )
                else -> Triple(
                    when {
                        isTransfer -> R.drawable.ic_swap
                        income -> R.drawable.ic_arrow_in
                        else -> R.drawable.ic_arrow_out
                    },
                    when {
                        isTransfer -> Mark.TRANSFER
                        income -> Mark.IN
                        else -> Mark.OUT
                    },
                    if (partner != null) "Sent" else r.error ?: accounts[r.account] ?: "Sent"
                )
            }
            // The bank's own icon when its app is installed, with the status as a small badge on its corner.
            val bankIcon = runCatching { packageManager.getApplicationIcon(r.pkg) }.getOrNull()
            val iconView = row.findViewById<ImageView>(R.id.recIcon)
            if (bankIcon != null) {
                iconView.setImageDrawable(bankIcon)
                iconView.background = null
                iconView.imageTintList = null
                iconView.setPadding(0, 0, 0, 0)
                row.findViewById<ImageView>(R.id.recStatus).apply {
                    mark(icon, mark)
                    visibility = View.VISIBLE
                }
            } else {
                iconView.mark(icon, mark)
            }
            // Hidden, a row keeps only what kind of thing it is, when, and what it needs.
            row.findViewById<TextView>(R.id.recTitle).text = when {
                r.status == Store.UNPARSED -> if (hide) "Bank alert" else r.note.orEmpty().ifEmpty { "Bank alert" }
                partner != null || r.transferTo != null -> "Transfer"
                !hide && !r.payee.isNullOrBlank() -> r.payee
                income -> "Money in"
                else -> "Payment"
            }
            // The note Wallet gets (or the one the user wrote), so the title reads as the payee/payer and this as the note.
            row.findViewById<TextView>(R.id.recNote).apply {
                val note = if (hide || isTransfer || r.status == Store.UNPARSED) null else {
                    r.noteEdit ?: Parser.walletText(r.note.orEmpty(), r.payee, currencies[r.account].orEmpty(), settings.includeNote).note
                }?.takeIf { it.isNotBlank() }
                visibility = if (note == null) View.GONE else View.VISIBLE
                text = note?.let { "Note: $it" }
            }
            row.findViewById<TextView>(R.id.recSub).text = if (status == Store.UNPARSED || status == Store.REVIEW) {
                // Waiting on the user: the action part stands out in red and bold.
                val red = row.themeColor(M.attr.colorError)
                SpannableString("$whenText · $state").apply {
                    val from = whenText.length + 3
                    setSpan(ForegroundColorSpan(red), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                "$whenText · $state"
            }
            row.findViewById<TextView>(R.id.recAmount).apply {
                // A transfer moves your own money, so it has no sign; otherwise green in, red out.
                text = r.amount?.let {
                    when {
                        hide -> "••••"
                        partner != null || r.transferTo != null -> String.format(Locale.US, "%,.2f", kotlin.math.abs(it))
                        else -> String.format(Locale.US, "%s%,.2f", if (it > 0) "+" else "−", kotlin.math.abs(it))
                    }
                }
                setTextColor(
                    context.getColor(
                        when {
                            isTransfer -> R.color.mark_transfer
                            income -> R.color.amount_in
                            else -> R.color.amount_out
                        }
                    )
                )
            }
            // What Wallet will file it under; a waiting record with none is flagged, as it lands as "Unknown".
            row.findViewById<TextView>(R.id.recCategory).apply {
                val waiting = r.status == Store.PENDING || r.status == Store.REVIEW
                when {
                    hide -> return@apply
                    r.transferTo != null -> {
                        // An account no bank app is linked to is named from what Wallet last told us, else looked up once.
                        val there = accounts[r.transferTo] ?: settings.accountName(r.transferTo)
                        if (there == null) resolveAccountNames()
                        text = "⇄ ${accounts[r.account] ?: "this account"} → ${there ?: "another account"}"
                        setTextColor(themeColor(M.attr.colorTertiary))
                    }
                    r.pairId != null -> {
                        // Wallet files a transfer itself; show where the money moved instead.
                        val here = accounts[r.account] ?: "this account"
                        val there = (partner ?: store.get(r.pairId))?.account?.let { accounts[it] } ?: "another account"
                        text = "⇄ " + if (income) "$there → $here" else "$here → $there"
                        setTextColor(themeColor(M.attr.colorTertiary))
                    }
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
            row.setOnLongClickListener { confirmRemove(r, partner); true }
            if (partner != null) {
                // Tapping a transfer shows its two sides underneath; tap a side that isn't sent to open it.
                val halves = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (r.id in openTransfers) View.VISIBLE else View.GONE
                }
                for (half in listOf(r, partner)) halves.addView(transferHalf(inflater, halves, half, accounts, fmt, iso, hide))
                val chevron = row.findViewById<ImageView>(R.id.recChevron).apply {
                    visibility = View.VISIBLE
                    rotation = if (r.id in openTransfers) 180f else 0f
                }
                row.setOnClickListener { Dialogs.showDetails(this, r, partner, ::openRecord) }
                chevron.setOnClickListener {
                    val opening = openTransfers.add(r.id)
                    if (!opening) openTransfers.remove(r.id)
                    animateLayout(list)
                    halves.visibility = if (opening) View.VISIBLE else View.GONE
                    chevron.animate().rotation(if (opening) 180f else 0f).setDuration(200).start()
                }
                list.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(row)
                    addView(halves)
                })
            } else {
                row.setOnClickListener { Dialogs.showDetails(this, r, null, ::openRecord) }
                list.addView(row)
            }

            // New rows drop in; a row whose status changed (waiting to sent, say) flashes and its badge pops.
            val key = "${r.status}|${partner?.status}|${r.pairId}|${r.transferTo}"
            states[r.id] = key
            if (animateRows) {
                val before = rowStates[r.id]
                if (before == null) {
                    row.dropIn(index)
                } else if (before != key) {
                    row.flash()
                    row.findViewById<View>(if (bankIcon != null) R.id.recStatus else R.id.recIcon).pop()
                }
            }
        }
        rowStates = states
    }

    /** One side of a transfer, as a small row under the combined one; an unsent side opens like any record. */
    private fun transferHalf(
        inflater: LayoutInflater, parent: ViewGroup, half: Rec, accounts: Map<String, String>,
        fmt: SimpleDateFormat, iso: SimpleDateFormat, hide: Boolean
    ): View {
        val row = inflater.inflate(R.layout.item_transfer_half, parent, false)
        val out = (half.amount ?: 0.0) < 0
        val account = accounts[half.account] ?: "an account"
        row.findViewById<TextView>(R.id.halfTitle).text = if (out) "Out of $account" else "Into $account"
        val whenText = iso.parse(half.date)?.let { fmt.format(it) } ?: half.date
        val state = when (half.status) {
            Store.SYNCED -> "Sent"
            Store.REVIEW -> "Tap to approve"
            Store.FAILED -> half.error ?: "Couldn't be sent"
            Store.UNPARSED -> "Not understood"
            else -> "Waiting to send · tap to change"
        }
        row.findViewById<TextView>(R.id.halfSub).text = "$whenText · $state"
        row.findViewById<TextView>(R.id.halfAmount).apply {
            text = half.amount?.let {
                if (hide) "••••" else String.format(Locale.US, "%s%,.2f", if (it > 0) "+" else "−", kotlin.math.abs(it))
            }
            setTextColor(context.getColor(if (out) R.color.amount_out else R.color.amount_in))
        }
        row.setOnClickListener { Dialogs.showDetails(this, half, null, ::openRecord) }
        return row
    }

    /** Asks the bound listener to read what's still in the shade; dismissed alerts can't be recovered. */
    private fun scan() {
        if (Demo.active) return toast("Not available in the demo")
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
        val c = store.counts()
        val n = c[Store.SYNCED] + c[Store.UNPARSED]
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear $n item${if (n == 1) "" else "s"}?")
            .setMessage(
                "Removes the $n sent or not-understood item${if (n == 1) "" else "s"} from this list. " +
                    "Nothing in Wallet changes, and anything waiting, failed or needing you stays.\n\n" +
                    "If the bank's alert is still in your notification shade, \"Scan notifications\" would pick it up again and send it twice."
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
     * Long press: forget one record on this phone only. With it gone, a scan can capture the bank's
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
    }
}
