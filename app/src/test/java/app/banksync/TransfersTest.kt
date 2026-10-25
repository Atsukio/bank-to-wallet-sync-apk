package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up accounts, amounts and times. */
class TransfersTest {
    private val min = 60_000L
    private val t0 = 1_000_000_000_000L
    private fun c(id: Long, account: String, amount: Double, at: Long, currency: String = "MYR") =
        Transfers.Candidate(id, account, amount, at, currency)

    @Test
    fun moneyOutThenInOnAnotherAccountPairs() {
        val out = c(1, "acc-ryt", -13.0, t0)
        assertEquals(1L, Transfers.match(listOf(out), 13.0, "acc-cimb", t0 + 4 * min, "MYR")?.id)
    }

    @Test
    fun outsideFiveMinutesDoesNotPair() {
        assertNull(Transfers.match(listOf(c(1, "acc-ryt", -13.0, t0)), 13.0, "acc-cimb", t0 + 6 * min, "MYR"))
    }

    @Test
    fun sameAccountSameDirectionOrOtherAmountDoesNotPair() {
        assertNull(Transfers.match(listOf(c(1, "acc-cimb", -13.0, t0)), 13.0, "acc-cimb", t0, "MYR"))
        assertNull(Transfers.match(listOf(c(1, "acc-ryt", 13.0, t0)), 13.0, "acc-cimb", t0, "MYR"))
        assertNull(Transfers.match(listOf(c(1, "acc-ryt", -13.5, t0)), 13.0, "acc-cimb", t0, "MYR"))
        assertNull(Transfers.match(listOf(c(1, "acc-ryt", -13.0, t0, "USD")), 13.0, "acc-cimb", t0, "MYR"))
    }

    @Test
    fun closestInTimeWins() {
        val far = c(1, "acc-ryt", -13.0, t0 - 4 * min)
        val near = c(2, "acc-tng", -13.0, t0 - 1 * min)
        assertEquals(2L, Transfers.match(listOf(far, near), 13.0, "acc-cimb", t0, "MYR")?.id)
    }

    @Test
    fun planBindsToSentPartnerAndOlderUnsentSideSends() {
        val bind = Transfers.plan(5, 4, Store.SYNCED, "w-1", "acc-ryt")
        assertTrue(bind is Transfers.Send.Existing && bind.walletId == "w-1")
        assertSame(Transfers.Send.Alone, Transfers.plan(5, 4, Store.SYNCED, null, "acc-ryt"))
        val send = Transfers.plan(4, 5, Store.PENDING, null, "acc-cimb")
        assertTrue(send is Transfers.Send.New && send.toAccount == "acc-cimb" && send.partnerId == 5L)
        assertSame(Transfers.Send.Wait, Transfers.plan(5, 4, Store.PENDING, null, "acc-ryt"))
        assertSame(Transfers.Send.Wait, Transfers.plan(4, 5, Store.REVIEW, null, "acc-cimb"))
        assertSame(Transfers.Send.Alone, Transfers.plan(4, 5, null, null, "acc-cimb"))
        assertSame(Transfers.Send.Alone, Transfers.plan(4, 5, Store.FAILED, null, "acc-cimb"))
    }
}
