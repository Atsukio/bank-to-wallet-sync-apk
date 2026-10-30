package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowStatusTest {
    private fun rec(status: Int, category: String? = "Groceries", amount: Double? = -12.5, patch: String? = null, error: String? = null) =
        Rec(1, "com.example.bank", "acc-1", amount, "Sample Mart", null, "2026-10-30T01:00:00Z", status, 0, error, category, false,
            categoryName = category, patch = patch)

    private fun pill(r: Rec, transfer: Boolean = false, held: Boolean = false) = RowStatus.pill(r, transfer, held)

    @Test
    fun aPlainSentRecordHasNoPill() {
        assertNull(pill(rec(Store.SYNCED)))
    }

    @Test
    fun whatNeedsTheUserIsAmber() {
        assertEquals("Couldn't read" to Chip.YOU, pill(rec(Store.UNPARSED, amount = null)))
        assertEquals("Approve?" to Chip.YOU, pill(rec(Store.REVIEW)))
        assertEquals("No category" to Chip.YOU, pill(rec(Store.PENDING, category = null)))
    }

    @Test
    fun aTransferWithoutCategoryIsJustWaiting() {
        assertEquals("Waiting" to Chip.WAIT, pill(rec(Store.PENDING, category = null), transfer = true))
    }

    @Test
    fun waitingSaysPausedWhileASheetIsOpen() {
        assertEquals("Waiting" to Chip.WAIT, pill(rec(Store.PENDING)))
        assertEquals("Paused" to Chip.WAIT, pill(rec(Store.PENDING), held = true))
    }

    @Test
    fun sentChangesShowWhereTheyStand() {
        assertEquals("Updating" to Chip.WAIT, pill(rec(Store.SYNCED, patch = "{\"note\":\"Lunch\"}")))
        assertEquals("Removing" to Chip.WAIT, pill(rec(Store.SYNCED, patch = SentEdit.deletePatch())))
        assertEquals("Wallet refused" to Chip.BAD, pill(rec(Store.SYNCED, patch = "{\"note\":\"Lunch\"}", error = "Wallet refused the change: x")))
    }

    @Test
    fun failedIsRed() {
        assertEquals("Failed" to Chip.BAD, pill(rec(Store.FAILED, error = "HTTP 500")))
    }
}
