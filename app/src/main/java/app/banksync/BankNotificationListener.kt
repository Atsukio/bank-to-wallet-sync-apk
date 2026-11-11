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
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) != 0
        if (catchUp && n.flags and Notification.FLAG_ONGOING_EVENT != 0) return false

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val text = "$title $body".trim()

        // Clears a Money+ notification from the shade if the user chose that. Only once it has been
        // dealt with: skipped, or saved in the queue.
        val clear = settings.dismissMoneyPlus && !ongoing && MoneyPlus.matches(text) &&
            settings.rulesFor(sbn.packageName).orEmpty().any { MoneyPlus.isShopee(it.label) }
        fun done(kept: Boolean): Boolean {
            if (clear) dismiss(sbn)
            return kept
        }

        if (text.isEmpty()) return done(false)
        if (IgnoreWords.matches(settings.ignoreWords, text)) return done(false)
        // Banks also post logins, ads and reminders; those aren't kept or shown as "needs you".
        if (settings.skipNotices && Parser.isNotice(text)) return done(false)

        val store = Store.get(this)
        val pkg = sbn.packageName
        val hash = "$pkg|$text".hashCode().toString()
        // Banks re-post the same notification on update; identical text within 2 minutes is one
        // transaction. A catch-up scan checks everything still in history, since the alert may have
        // been captured live hours ago.
        val seen = if (catchUp) store.hasSimilar(hash, sbn.postTime) else store.isDuplicate(hash)
        if (seen) return done(false)
        return done(capture(this, pkg, text, hash, sbn.postTime))
    }

    /**
     * Removes [sbn] from the shade, and its group's summary once nothing else is left under it, so an
     * empty "Shopee" heading doesn't linger. Never throws: a failed dismissal only leaves it showing.
     */
    private fun dismiss(sbn: StatusBarNotification) {
        runCatching { cancelNotification(sbn.key) }
        val group = sbn.notification.group ?: return
        val rest = runCatching { activeNotifications }.getOrNull() ?: return
        val same = rest.filter { it.packageName == sbn.packageName && it.notification.group == group && it.key != sbn.key }
        if (same.isNotEmpty() && same.all { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }) {
            same.forEach { runCatching { cancelNotification(it.key) } }
        }
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
            val linked = Settings(context).rulesFor(pkg) ?: return 0
            val rules = linked.filterNot(GoPlus::isGoPlus).filterNot(SpayLater::isRule)
            val hasSpayLater = linked.any(SpayLater::isRule)
            val store = Store.get(context)
            val fromShopee = linked.any { MoneyPlus.isShopee(it.label) }
            val found = store.heldWithoutAccount(pkg).filter {
                when {
                    fromShopee && SpayLater.isAlert(it.text) -> hasSpayLater
                    fromShopee && SpayLater.mentions(it.text) -> false // not a payment: no account takes it
                    else -> RuleMatcher.pick(rules, it.text) != null
                }
            }
            // Same hash and posted time as before, so a catch-up scan still knows these alerts.
            found.forEach {
                store.delete(it.id)
                // The question it raised ("Ignore SPayLater payments?", "Ignore Cash in alerts?") is answered by the link.
                Notifier.cancelReview(context, it.id)
                capture(context, pkg, it.text, it.hash, it.posted)
            }
            if (found.isNotEmpty() && store.counts()[Store.UNPARSED] == 0) Notifier.cancelUnparsed(context)
            found.size
        }

        /** Everything after "is this a new alert from a linked app?": account, amount, transfer, category, then the queue. */
        private fun capture(context: Context, pkg: String, text: String, hash: String, posted: Long): Boolean {
            val settings = Settings(context)
            val linked = settings.rulesFor(pkg) ?: return false
            // A GO+ account takes Cash ins only, and an SPayLater account takes SPayLater payments only; neither
            // picks up the app's ordinary alerts.
            val rules = linked.filterNot(GoPlus::isGoPlus).filterNot(SpayLater::isRule)
            val goPlus = linked.filter(GoPlus::isGoPlus)
            val spayRules = linked.filter(SpayLater::isRule)
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

            // Shopee's "Payment" activity repeats an SPayLater payment, kept or ignored; the copy is dropped.
            // Only Shopee and ShopeePay raise SPayLater payments; a bank's own alert naming it is an ordinary alert.
            val fromShopee = linked.any { MoneyPlus.isShopee(it.label) }
            val mentionsSpay = fromShopee && SpayLater.mentions(text)
            val spay = fromShopee && SpayLater.isAlert(text)
            if (!mentionsSpay) {
                val seen = SpayLater.payments(store.postedNotesSince(pkg, posted - SpayLater.HOUR)) + settings.ignoredSpayLater
                if (SpayLater.isPaymentCopy(text, posted, seen)) return false
            }
            // Or the copy came first and is still waiting to go: it goes instead of reaching the ordinary account.
            val spayPaid = if (spay) Parser.spayLater(text) else null
            if (spayPaid != null) dropEarlierCopies(context, store, pkg, posted, spayPaid.amount)

            // A Cash in to GO+ moves money from the app's main account into its GO+ account.
            val cashIn = Parser.cashIn(text)
            if (cashIn != null && settings.cashInIgnored) return false
            if (mentionsSpay && settings.spayLaterIgnored) {
                // Remembered for an hour, so the "Payment" activity repeating it isn't sent in its place.
                if (spayPaid != null) settings.ignoredSpayLater = SpayLater.remember(settings.ignoredSpayLater, posted, spayPaid.amount)
                return false
            }
            // A bill, offer or refund naming SPayLater: never the app's ordinary account, and not a payment to ask about.
            if (mentionsSpay && !spay) return hold("", "Mentions SPayLater but isn't a payment")

            // An SPayLater payment never falls back to the app's ordinary account: with none linked it's asked about.
            val spayRule = if (spay) spayRules.firstOrNull() else null
            if (spay && spayRule == null) {
                val reason = "SPayLater payment, but no SPayLater account is linked"
                val id = store.insert(hash, pkg, "", null, null, note, date, Store.UNPARSED, error = reason, posted = posted)
                Notifier.unparsed(context, store.counts()[Store.UNPARSED], reason)
                Notifier.askIgnoreSpayLater(context, id)
                return true
            }

            val rule = spayRule ?: RuleMatcher.pick(rules, text) ?: return hold(
                "", RuleMatcher.endings(text).firstOrNull()?.let { "Account ending $it isn't linked to a Wallet account" }
                    ?: "No bank-app rule matched this alert"
            )
            val parsed = cashIn ?: (if (spay) Parser.spayLater(text) else Parser.parse(text))
                ?: return hold(rule.accountId, "Couldn't read the amount or direction")
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
            // Marking a planned payment paid in Wallet makes its own record, so one this matches waits for that instead.
            val plan = if (isTransfer) null else WalletCache.plannedMatch(context, parsed.amount, rule.accountId, parsed.currency ?: rule.accountCurrency, whenMs, parsed.payee)
            val ask = settings.reviewMode || (!isTransfer && settings.askNoCategory && category == null)
            val status = when {
                plan != null -> Store.PLANNED
                ask -> Store.REVIEW
                else -> Store.PENDING
            }
            val id = store.insert(
                hash, pkg, rule.accountId, parsed.amount, parsed.payee, note, date, status,
                category = category, posted = posted, transferTo = goPlusRule?.accountId, plan = plan
            )
            if (plan != null) {
                val due = SimpleDateFormat("d MMM", Locale.getDefault()).format(plan.dueMs)
                Notifier.planned(context, id, if (settings.hideDetails) "From ${rule.label}: matches a planned payment in Wallet." else {
                    String.format(Locale.US, "%+.2f", parsed.amount) + " " + rule.accountCurrency +
                        (parsed.payee?.let { " · $it" } ?: "") + " → " + rule.accountName + "\n" +
                        "Matches \"${plan.name}\", due $due."
                })
                Uploader.changed()
                return true
            }
            if (partner != null) {
                store.pair(id, partner.id)
                // The earlier side may be waiting only because it had no category; a transfer needs none,
                // and the pair can't go until both sides may.
                if (partner.status == Store.REVIEW && !settings.reviewMode && store.approve(partner.id)) {
                    Notifier.cancelReview(context, partner.id)
                }
            }

            // An SPayLater alert names no currency, so the amount is in the account's: say so where it's shown.
            val currencyNote = if (spay && parsed.currency == null) SpayLater.currencyNote(rule.accountCurrency) else ""

            if (status == Store.REVIEW) {
                val what = String.format(Locale.US, "%+.2f", parsed.amount) +
                    (parsed.payee?.let { " $it" } ?: "") + " → " + rule.accountName + "\n" + currencyNote +
                    (transfer?.let { "Transfer: $it" } ?: ("Category: " + (category?.name ?: "none (Wallet will show Unknown)")))
                Notifier.review(context, id, what)
            } else {
                Uploader.schedule(context)
                Uploader.changed()
                Notifier.captured(context, id, if (settings.hideDetails) "From ${rule.label}, going to Wallet." else {
                    String.format(Locale.US, "%+.2f", parsed.amount) + " " + rule.accountCurrency +
                        (parsed.payee?.let { " · $it" } ?: "") + " → " + rule.accountName + "\n" + currencyNote +
                        (transfer?.let { "Matched as a transfer: $it" }
                            ?: category?.name?.let { "Category: $it" } ?: "No category yet: tap to choose one")
                })
            }
            return true
        }

        /**
         * Removes Shopee "Payment" activity captured before the SPayLater payment of [amount] it repeats, while it
         * still hasn't gone to Wallet, with its notifications. One already sent stays.
         */
        private fun dropEarlierCopies(context: Context, store: Store, pkg: String, posted: Long, amount: Double) {
            val gone = SpayLater.earlierCopies(store.waitingNotesSince(pkg, posted - SpayLater.HOUR), posted, amount)
                .filter(store::discardCopy)
            if (gone.isEmpty()) return
            gone.forEach {
                Notifier.cancelReview(context, it)
                Notifier.cancelCapturedFor(context, it)
            }
            if (store.counts()[Store.UNPARSED] == 0) Notifier.cancelUnparsed(context)
            Uploader.changed()
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
