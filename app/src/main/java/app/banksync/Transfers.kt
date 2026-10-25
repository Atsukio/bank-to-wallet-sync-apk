package app.banksync

import kotlin.math.abs

/**
 * Money moving between two of the user's own accounts shows up as two alerts: money out from one
 * bank, money in at the other a few minutes later. Pairing them makes one Wallet transfer instead
 * of an expense plus an income. Pure, unit-tested.
 */
object Transfers {
    /** How far apart the two alerts' own times may be. Short, so a payment and an unrelated receipt rarely pair. */
    const val WINDOW_MS = 5 * 60_000L

    /** A captured record that could be the other side: [amount] signed, [timeMs] the alert's own time. */
    class Candidate(val id: Long, val account: String, val amount: Double, val timeMs: Long, val currency: String)

    /**
     * The other side of a new alert ([amount] on [account] at [timeMs]), or null: the same amount the
     * other way, on a different account in the same currency, within [WINDOW_MS]. The closest in time wins.
     */
    fun match(candidates: List<Candidate>, amount: Double, account: String, timeMs: Long, currency: String): Candidate? =
        candidates
            .filter {
                it.account != account &&
                    abs(it.amount + amount) < 0.005 && (it.amount > 0) != (amount > 0) &&
                    abs(it.timeMs - timeMs) <= WINDOW_MS &&
                    (it.currency.isEmpty() || currency.isEmpty() || it.currency == currency)
            }
            .minByOrNull { abs(it.timeMs - timeMs) }

    /** How a record with a matched partner goes to Wallet; see [plan]. */
    sealed class Send {
        /** Bind to the partner already in Wallet (`pairingMode: existing`). */
        class Existing(val walletId: String) : Send()

        /**
         * Send this one as the whole transfer; Wallet creates the partner on [toAccount] (`pairingMode: new`).
         * [partnerId] is null for a record the user turned into a transfer, which has no partner here.
         */
        class New(val toAccount: String, val partnerId: Long? = null) : Send()

        /** The partner sends the transfer (or still waits for approval); this one isn't sent itself. */
        object Wait : Send()

        /** The partner is gone, failed or can't be bound to: send as a normal record. */
        object Alone : Send()
    }

    /**
     * What to do with record [id] whose partner is [partnerId] with [partnerStatus] (null when it was
     * deleted) and [partnerWalletId]. Of two unsent sides, the older one sends the transfer.
     */
    fun plan(id: Long, partnerId: Long, partnerStatus: Int?, partnerWalletId: String?, partnerAccount: String): Send =
        when (partnerStatus) {
            Store.SYNCED -> if (partnerWalletId != null) Send.Existing(partnerWalletId) else Send.Alone
            Store.REVIEW -> Send.Wait
            Store.PENDING -> if (id < partnerId) Send.New(partnerAccount, partnerId) else Send.Wait
            else -> Send.Alone
        }
}
