package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentEditTest {
    private val before = SentEdit.Fields(-12.5, "Sample Mart", "cat-groceries", "Ref 000123")

    private fun rec(status: Int = Store.SYNCED, walletId: String? = "w-1", pairId: Long? = null, transferTo: String? = null) =
        Rec(1, "com.example.bank", "acc-1", -12.5, "Sample Mart", null, "2026-10-30T01:00:00Z", status, 0, null, null, false,
            walletId = walletId, pairId = pairId, transferTo = transferTo)

    @Test
    fun onlySentNonTransferRecordsWithAWalletIdAreEditable() {
        assertTrue(SentEdit.editable(rec()))
        assertFalse(SentEdit.editable(rec(status = Store.PENDING)))
        assertFalse(SentEdit.editable(rec(walletId = null)))
        assertFalse(SentEdit.editable(rec(pairId = 2)))
        assertFalse(SentEdit.editable(rec(transferTo = "acc-2")))
    }

    @Test
    fun aSentRecordThatCantBeChangedSaysWhy() {
        assertEquals(null, SentEdit.whyNot(rec()))
        assertEquals(null, SentEdit.whyNot(rec(status = Store.PENDING, walletId = null)))
        assertTrue(SentEdit.whyNot(rec(walletId = null))!!.contains("before version 1.4"))
        assertTrue(SentEdit.whyNot(rec(pairId = 2))!!.contains("transfers"))
        assertTrue(SentEdit.whyNot(rec(transferTo = "acc-2", walletId = null))!!.contains("transfers"))
    }

    @Test
    fun nothingChangedSendsNothing() {
        assertTrue(SentEdit.changes(before, SentEdit.Fields(-12.5, " Sample Mart ", "cat-groceries", "Ref 000123")).isEmpty())
    }

    @Test
    fun onlyChangedFieldsAreSent() {
        val after = SentEdit.Fields(-13.0, "Sample Mart", "cat-dining", "Ref 000123")
        assertEquals(mapOf("amount" to -13.0, "categoryId" to "cat-dining"), SentEdit.changes(before, after))
    }

    @Test
    fun blankingAFieldClearsItInWallet() {
        val after = SentEdit.Fields(-12.5, "  ", null, "")
        assertEquals(mapOf("counterParty" to null, "categoryId" to null, "note" to null), SentEdit.changes(before, after))
    }

    @Test
    fun newTextIsTrimmed() {
        val after = SentEdit.Fields(-12.5, "Sample Mart Express ", "cat-groceries", " Lunch ")
        assertEquals(mapOf("counterParty" to "Sample Mart Express", "note" to "Lunch"), SentEdit.changes(before, after))
    }

    @Test
    fun aNoteCanBeAddedWhereThereWasNone() {
        val none = SentEdit.Fields(25.0, "A. Sender", null, null)
        assertEquals(mapOf("note" to "Rent share"), SentEdit.changes(none, SentEdit.Fields(25.0, "A. Sender", null, "Rent share")))
    }

    @Test
    fun aNewAccountMovesTheRecord() {
        val from = SentEdit.Fields(-12.5, "Sample Mart", "cat-groceries", "Ref 000123", "acc-1")
        assertEquals(mapOf("accountId" to "acc-2"), SentEdit.changes(from, SentEdit.Fields(-12.5, "Sample Mart", "cat-groceries", "Ref 000123", "acc-2")))
        assertTrue(SentEdit.changes(from, SentEdit.Fields(-12.5, "Sample Mart", "cat-groceries", "Ref 000123", "acc-1")).isEmpty())
        assertTrue(SentEdit.changes(from, SentEdit.Fields(-12.5, "Sample Mart", "cat-groceries", "Ref 000123")).isEmpty())
    }

    @Test
    fun aRecordAlreadyDeletedInWalletCountsAsRemoved() {
        assertTrue(SentEdit.gone("Record not found"))
        assertTrue(SentEdit.gone("{\"code\":\"NOT_FOUND\"}"))
        assertTrue(SentEdit.gone("HTTP 404"))
        assertFalse(SentEdit.gone("Account is bank-synced"))
        assertFalse(SentEdit.gone(null))
    }
}
