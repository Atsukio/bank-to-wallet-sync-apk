package app.banksync

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Bound by the system only while notifications are flowing. Everything here is cheap and bails
 * out on the package check first, so unrelated apps cost one map lookup.
 */
class BankNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        // Also covers reboot: the system rebinds us, and any queued records get rescheduled.
        val store = Store.get(this)
        store.purge(Settings(this).keepDays)
        if (store.counts()[Store.PENDING] > 0) Uploader.schedule(this)
        Uploader.releaseTransfers(this)
        Summary.schedule(this)
        // Alerts that arrived while we weren't bound may still be in the shade.
        Thread { catchUp() }.start()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn, catchUp = false)
    }

    /**
     * Looks at the notifications still in the shade and captures any this app hasn't seen. Alerts
     * that were dismissed are gone and can't be recovered. Returns how many new records were kept.
     */
    fun catchUp(): Int {
        val settings = Settings(this)
        if (settings.paused) return 0
        val active = try {
            activeNotifications
        } catch (e: Exception) {
            return 0 // not bound (yet)
        } ?: return 0
        val cutoff = CatchUp.cutoff(System.currentTimeMillis(), settings.catchUpSince, settings.catchUpHours, settings.keepDays)
        return active.filter { it.postTime >= cutoff }.sortedBy { it.postTime }.count { handle(it, catchUp = true) }
    }

    /**
     * Returns true when a new record was stored. One alert at a time: a catch-up scan runs on its own
     * thread, and the "seen it?" check and the insert must not interleave with a live alert.
     */
    private fun handle(sbn: StatusBarNotification, catchUp: Boolean): Boolean = synchronized(lock) { handleLocked(sbn, catchUp) }

    private fun handleLocked(sbn: StatusBarNotification, catchUp: Boolean): Boolean {
        val settings = Settings(this)
        settings.rulesFor(sbn.packageName) ?: return false
        if (settings.paused) return false
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (catchUp && n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val text = "$title $body".trim()
        if (text.isEmpty()) return false
        if (IgnoreWords.matches(settings.ignoreWords, text)) return false
        // Banks also post logins, ads and reminders; those aren't kept or shown as "needs you".
        if (settings.skipNotices && Parser.isNotice(text)) return false

        val store = Store.get(this)
        val pkg = sbn.packageName
        val hash = "$pkg|$text".hashCode().toString()
        // Banks re-post the same notification on update; identical text within 2 minutes is one
        // transaction. A catch-up scan checks everything still in history, since the alert may have
        // been captured live hours ago.
        val seen = if (catchUp) store.hasSimilar(hash, sbn.postTime) else store.isDuplicate(hash)
        if (seen) return false
        return capture(this, pkg, text, hash, sbn.postTime)
    }

    companion object {
        /** The bound service, so the app screen can ask for a scan; null while access is off. */
        @Volatile
        var instance: BankNotificationListener? = null

        private val lock = Any()

        /**
         * Runs alerts held because no rule gave them an account through capture again, now that a rule
         * might (a new default or link). Ones still without an account are left as they are. Works
         * whether or not the listener is bound. Returns how many found an account.
         */
        fun recheck(context: Context, pkg: String): Int = synchronized(lock) {
            val rules = Settings(context).rulesFor(pkg)?.filterNot(GoPlus::isGoPlus) ?: return 0
            val store = Store.get(context)
            val found = store.heldWithoutAccount(pkg).filter { RuleMatcher.pick(rules, it.text) != null }
            // Same hash and posted time as before, so a catch-up scan still knows these alerts.
            found.forEach {
                store.delete(it.id)
                capture(context, pkg, it.text, it.hash, it.posted)
            }
            if (found.isNotEmpty() && store.counts()[Store.UNPARSED] == 0) Notifier.cancelUnparsed(context)
            found.size
        }

        /** Everything after "is this a new alert from a linked app?": account, amount, transfer, category, then the queue. */
        private fun capture(context: Context, pkg: String, text: String, hash: String, posted: Long): Boolean {
            val settings = Settings(context)
            val linked = settings.rulesFor(pkg) ?: return false
            // A GO+ account takes Cash ins only; it never picks up the app's ordinary alerts.
            val rules = linked.filterNot(GoPlus::isGoPlus)
            val goPlus = linked.filter(GoPlus::isGoPlus)
            val store = Store.get(context)

            // Alerts can arrive late; prefer the time written in the alert so the record lands when the
            // transaction happened.
            val whenMs = Parser.recordTime(text, posted, ZoneId.systemDefault())
            val date = isoUtc(whenMs)
            val note = text.take(255)

            // Anything we can't send with confidence is kept, with the reason, so the user can fix it in the app.
            fun hold(account: String, reason: String): Boolean {
                store.insert(hash, pkg, account, null, null, note, date, Store.UNPARSED, error = reason, posted = posted)
                Notifier.unparsed(context, store.counts()[Store.UNPARSED], reason)
                return true
            }

            // A Cash in to GO+ moves money from the app's main account into its GO+ account.
            val cashIn = Parser.cashIn(text)
            if (cashIn != null && settings.cashInIgnored) return false

            val rule = RuleMatcher.pick(rules, text) ?: return hold(
                "", RuleMatcher.endings(text).firstOrNull()?.let { "Account ending $it isn't linked to a Wallet account" }
                    ?: "No bank-app rule matched this alert"
            )
            val parsed = cashIn ?: Parser.parse(text) ?: return hold(rule.accountId, "Couldn't read the amount or direction")
            val goPlusRule = if (cashIn != null) goPlus.firstOrNull() else null
            if (cashIn != null && goPlusRule == null) {
                // Nowhere to put it: keep it, and ask whether Cash ins should be ignored from now on.
                val id = store.insert(
                    hash, pkg, rule.accountId, null, null, note, date, Store.UNPARSED,
                    error = "Cash in to GO+, but no GO+ account is linked", posted = posted
                )
                Notifier.unparsed(context, store.counts()[Store.UNPARSED], "Cash in to GO+, but no GO+ account is linked")
                Notifier.askIgnoreCashIn(context, id)
                return true
            }

            // Wallet rejects a record whose currency differs from its account's, and converting would be a guess.
            if (parsed.currency != null && rule.accountCurrency.isNotEmpty() && parsed.currency != rule.accountCurrency) {
                return hold(rule.accountId, "Alert is in ${parsed.currency} but ${rule.accountName} is ${rule.accountCurrency}")
            }
            if (parsed.currency != null && goPlusRule != null && goPlusRule.accountCurrency.isNotEmpty() && parsed.currency != goPlusRule.accountCurrency) {
                return hold(rule.accountId, "Alert is in ${parsed.currency} but ${goPlusRule.accountName} is ${goPlusRule.accountCurrency}")
            }

            // Money out of one linked account and the same amount into another moments later is a transfer.
            val partner = if (cashIn != null) null else transferPartner(store, settings, parsed.amount, rule, whenMs)
            val transfer = goPlusRule?.let { "${rule.accountName} → ${it.accountName}" } ?: partner?.let {
                val other = settings.rules().firstOrNull { r -> r.accountId == it.account }?.accountName ?: "another account"
                if (parsed.amount < 0) "${rule.accountName} → $other" else "$other → ${rule.accountName}"
            }

            // From what's already on the phone, so capturing never waits on the network. A transfer has no category.
            val isTransfer = partner != null || goPlusRule != null
            val category = if (isTransfer) null else WalletCache.pick(context, text, parsed.payee, parsed.amount, rule.accountId)
            val ask = settings.reviewMode || (!isTransfer && settings.askNoCategory && category == null)
            val status = if (ask) Store.REVIEW else Store.PENDING
            val id = store.insert(
                hash, pkg, rule.accountId, parsed.amount, parsed.payee, note, date, status,
                category = category, posted = posted, transferTo = goPlusRule?.accountId
            )
            if (partner != null) {
                store.pair(id, partner.id)
                // The earlier side may be waiting only because it had no category; a transfer needs none,
                // and the pair can't go until both sides may.
                if (partner.status == Store.REVIEW && !settings.reviewMode && store.approve(partner.id)) {
                    Notifier.cancelReview(context, partner.id)
                }
            }

            if (status == Store.REVIEW) {
                val what = String.format(Locale.US, "%+.2f", parsed.amount) +
                    (parsed.payee?.let { " $it" } ?: "") + " → " + rule.accountName + "\n" +
                    (transfer?.let { "Transfer: $it" } ?: ("Category: " + (category?.name ?: "none (Wallet will show Unknown)")))
                Notifier.review(context, id, what)
            } else {
                Uploader.schedule(context)
                Uploader.changed()
                Notifier.captured(context, id, if (settings.hideDetails) "From ${rule.label}, going to Wallet." else {
                    String.format(Locale.US, "%+.2f", parsed.amount) + " " + rule.accountCurrency +
                        (parsed.payee?.let { " · $it" } ?: "") + " → " + rule.accountName + "\n" +
                        (transfer?.let { "Matched as a transfer: $it" }
                            ?: category?.name?.let { "Category: $it" } ?: "No category yet: tap to choose one")
                })
            }
            return true
        }

        /** The other side of a transfer among records captured within [Transfers.WINDOW_MS] of [whenMs], if any. */
        private fun transferPartner(store: Store, settings: Settings, amount: Double, rule: Rule, whenMs: Long): Rec? {
            val currencies = settings.rules().associate { it.accountId to it.accountCurrency }
            val recs = store.transferCandidates(isoUtc(whenMs - Transfers.WINDOW_MS), isoUtc(whenMs + Transfers.WINDOW_MS))
            val candidates = recs.mapNotNull { r ->
                val t = runCatching { Instant.parse(r.date).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                Transfers.Candidate(r.id, r.account, r.amount ?: return@mapNotNull null, t, currencies[r.account].orEmpty())
            }
            val hit = Transfers.match(candidates, amount, rule.accountId, whenMs, rule.accountCurrency) ?: return null
            return recs.firstOrNull { it.id == hit.id }
        }

        private fun isoUtc(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(ms)
    }
}
