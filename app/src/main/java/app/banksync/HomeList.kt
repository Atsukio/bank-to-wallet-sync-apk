package app.banksync

import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import com.google.android.material.R as M

// The home screen's Recent activity: rows grouped by day, how each one reads, and what the count tiles filter.

/** What a count tile can narrow Recent activity to: the heading it shows and what is left out. */
internal enum class Filter(val title: String, val empty: String) {
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
internal class Look(
    val title: String, val sub: String, val note: String?, val amount: String?, val amountColor: Int,
    val chip: String?, val chipKind: Chip, val icon: Drawable?, val symbol: Int?, val monogram: String,
)

/** The record's time of day as the phone shows times (12 or 24 hour). */
internal fun MainActivity.timeOf(r: Rec): String {
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return iso.parse(r.date)?.let { DateFormat.getTimeFormat(this).format(it) } ?: r.date
}

/** The day a record happened on, in the phone's time zone, for the day headings. */
internal fun MainActivity.dayOf(r: Rec): LocalDate =
    runCatching { Instant.parse(r.date).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrDefault(LocalDate.now())

/**
 * What a row says about [r] (the outgoing half of a transfer, [partner] the other): who, then category
 * (or where the money moved) and time, the amount (plain out, green in, no sign for a transfer), and a
 * status pill only when it isn't simply sent. [hide] keeps only what kind of thing it is and when.
 */
internal fun MainActivity.describe(r: Rec, partner: Rec?, hide: Boolean): Look {
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
    val pill = RowStatus.pill(src, isTransfer, held)
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
    return Look(title, listOfNotNull(what, timeOf(r)).joinToString(" · "), note, amount, color, pill?.first, pill?.second ?: Chip.WAIT, bankIcon, symbol, monogram)
}

/** How pressing a record's status is: the more pressing half of a transfer decides what its row says and offers. */
internal fun MainActivity.urgency(status: Int) = when (status) {
    Store.UNPARSED -> 4
    Store.FAILED -> 3
    Store.REVIEW -> 2
    Store.PENDING -> 1
    else -> 0
}

internal fun MainActivity.renderRecent() {
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
internal fun MainActivity.transferHalf(inflater: LayoutInflater, parent: ViewGroup, half: Rec, rowId: Long, hide: Boolean): View {
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
