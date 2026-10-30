package app.banksync

import android.content.Context
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Made-up bank apps, accounts, categories and alerts for trying the app without a bank or a Wallet
 * account (and for Play reviewers and store screenshots). Only in memory: real settings and the real
 * queue are never read or changed, nothing is sent to Wallet, and restarting the app ends it. Capture
 * and upload keep using the real queue while it runs, so no real alert is lost.
 */
object Demo {
    @Volatile
    var active = false
        private set

    val rules = listOf(
        Rule("demo.bank", "Sample Bank", "", "demo-everyday", "Everyday Account", "MYR"),
        Rule("demo.card", "Sample Card", "", "demo-card", "Credit Card", "MYR"),
    )

    val categories = listOf(
        WalletApi.Category("demo-food", "Food & Drinks"),
        WalletApi.Category("demo-groceries", "Groceries"),
        WalletApi.Category("demo-transport", "Transport"),
        WalletApi.Category("demo-bills", "Bills & Utilities"),
        WalletApi.Category("demo-shopping", "Shopping"),
        WalletApi.Category("demo-rent", "Rent"),
        WalletApi.Category("demo-salary", "Salary"),
    )

    /** One alert as the bank app would post it, [minutesAgo], and where it has got to. */
    class Alert(val pkg: String, val text: String, val minutesAgo: Int, val status: Int, val categoryId: String? = null, val source: String = CategorySource.HISTORY)

    /** Oldest first, like real arrivals. All names, amounts and references are invented. */
    val alerts = listOf(
        Alert("demo.bank", "You've received RM2,500.00 from Example Company. Ref SAL1026", 60 * 30, Store.SYNCED, "demo-salary", CategorySource.WALLET_RULE),
        Alert("demo.card", "You spent RM45.80 at Sample Grocer", 60 * 22, Store.SYNCED, "demo-groceries"),
        Alert("demo.bank", "You sent RM900.00 to Sample Person. Ref RENT1026", 60 * 20, Store.SYNCED, "demo-rent", CategorySource.REMEMBERED),
        Alert("demo.card", "You spent RM12.30 at Corner Cafe", 60 * 6, Store.SYNCED, "demo-food"),
        Alert("demo.card", "You spent RM28.00 at City Ride", 95, Store.PENDING, "demo-transport", CategorySource.APP_RULE),
        Alert("demo.bank", "You paid RM89.90 to Sample Electric. Ref BILL7781", 50, Store.REVIEW, "demo-bills", CategorySource.WALLET_RULE),
        Alert("demo.card", "You spent RM64.50 at Online Store", 20, Store.PENDING),
        Alert("demo.bank", "Sample Bank: RM30.00 processed for your account", 8, Store.UNPARSED),
    )

    /** Fills a fresh in-memory queue with [alerts], read by the real parser, and switches the screens to it. */
    fun start(context: Context) {
        val store = Store.newDemo(context)
        val now = System.currentTimeMillis()
        for (a in alerts) {
            val posted = now - a.minutesAgo * 60_000L
            val date = Instant.ofEpochMilli(posted).truncatedTo(ChronoUnit.SECONDS).toString()
            val rule = rules.first { it.pkg == a.pkg }
            val hash = "${a.pkg}|${a.text}".hashCode().toString()
            val parsed = Parser.parse(a.text)
            if (parsed == null || a.status == Store.UNPARSED) {
                store.insert(hash, a.pkg, rule.accountId, null, null, a.text, date, Store.UNPARSED,
                    error = "Couldn't read the amount or direction", posted = posted)
                continue
            }
            val category = a.categoryId?.let { id ->
                CategoryPick(id, categories.first { it.id == id }.label, emptyList(), a.source)
            }
            val status = if (a.status == Store.SYNCED) Store.PENDING else a.status
            val id = store.insert(hash, a.pkg, rule.accountId, parsed.amount, parsed.payee, a.text, date, status,
                category = category, posted = posted)
            if (a.status == Store.SYNCED) markSent(store, store.get(id) ?: continue)
        }
        active = true
    }

    fun stop() {
        active = false
        Store.dropDemo()
    }

    /** "Sends" everything waiting in the demo queue; nothing leaves the phone. Returns how many. */
    fun sendAll(): Int {
        val store = Store.demoOrNull() ?: return 0
        val sent = store.pending(50)
        sent.forEach { markSent(store, it) }
        return sent.size
    }

    /**
     * Marks a demo record sent as an upload would: with a made-up Wallet id and the note Wallet would
     * have got, so Edit in Wallet and Remove from Wallet can be tried on it.
     */
    fun markSent(store: Store, r: Rec) {
        val currency = rules.firstOrNull { it.accountId == r.account }?.accountCurrency.orEmpty()
        val note = r.noteEdit ?: Parser.walletText(r.note.orEmpty(), r.payee, currency, true).note
        store.markSynced(r.id, "demo-${r.id}", note.orEmpty())
    }

    /** The bank-app rules the screens should show: the demo's while it runs, otherwise the user's. */
    fun rules(settings: Settings): List<Rule> = if (active) rules else settings.rules()
}
