package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All names and amounts are made up. */
class CategorizerTest {
    private val food = WalletRule(listOf("coffee"), "c-food", "Food", listOf("l-1"))
    private val longer = WalletRule(listOf("coffee house"), "c-cafe", "Cafes", emptyList())

    private fun pick(
        text: String, payee: String?, amount: Double = -5.0,
        memory: Map<String, Pair<String, String>> = emptyMap(),
        appRules: List<CategoryRule> = emptyList(),
        walletRules: List<WalletRule> = emptyList(),
        history: List<PastRecord> = emptyList(),
        accountId: String = "a-1",
    ) = Categorizer.pick(text, payee, amount, accountId, memory, appRules, walletRules, history)

    @Test
    fun merchantKeyDropsCodesAndDigits() {
        assertEquals("grab ride", Merchant.merchantKey("GRAB*RIDE 8812"))
        assertEquals(Merchant.merchantKey("SAMPLE MART 0001 CITY"), Merchant.merchantKey("Sample Mart 0937"))
        assertEquals("", Merchant.memoryKey("ABC"))
    }

    @Test
    fun sameDescriptionAcceptsNotesWithExtraDetail() {
        assertTrue(Merchant.sameDescription("Lunch: SAMPLE CAFE CENTRAL", "Sample Cafe Central"))
        assertTrue(Merchant.sameDescription("SAMPLE CAFE CEN", "Sample Cafe Central"))
        assertFalse(Merchant.sameDescription("Shop", "Sample Cafe Central"))
    }

    @Test
    fun rememberedChoiceWinsOverEverything() {
        val p = pick(
            "You spent RM 5.00 at Coffee House One", "Coffee House One",
            memory = mapOf("coffee house" to ("c-mine" to "Treats")),
            walletRules = listOf(food),
        )
        assertEquals("c-mine", p?.id)
        assertEquals(CategorySource.REMEMBERED, p?.source)
    }

    @Test
    fun appRulesComeBeforeWalletRules() {
        val p = pick("Coffee at Sample Place", "Sample Place", appRules = listOf(CategoryRule("sample", "c-app", "App")), walletRules = listOf(food))
        assertEquals("c-app", p?.id)
    }

    @Test
    fun longestWalletKeywordWinsAndKeepsLabels() {
        assertEquals("c-cafe", pick("Coffee House One", null, walletRules = listOf(food, longer))?.id)
        val p = pick("Coffee Corner", null, walletRules = listOf(food, longer))
        assertEquals("c-food", p?.id)
        assertEquals(listOf("l-1"), p?.labelIds)
    }

    @Test
    fun walletRuleForAnotherAccountIsSkipped() {
        val other = WalletRule(listOf("coffee"), "c-x", "X", emptyList(), accountId = "a-2")
        assertNull(pick("Coffee Corner", null, walletRules = listOf(other)))
    }

    @Test
    fun historyGuessUsesMostCommonCategoryOfSameType() {
        val history = listOf(
            PastRecord(listOf("Sample Grocer Main"), "c-groc", "Groceries", income = false),
            PastRecord(listOf("Sample Grocer Main"), "c-groc", "Groceries", income = false),
            PastRecord(listOf("Sample Grocer Main"), "c-misc", "Misc", income = false),
            PastRecord(listOf("Sample Grocer Main"), "c-refund", "Refunds", income = true),
        )
        val p = pick("You spent at Sample Grocer Main", "Sample Grocer Main", history = history)
        assertEquals("c-groc", p?.id)
        assertEquals(CategorySource.HISTORY, p?.source)
        assertEquals("c-refund", pick("Refund from Sample Grocer Main", "Sample Grocer Main", amount = 5.0, history = history)?.id)
    }

    @Test
    fun historyFallsBackToSameMerchant() {
        val history = listOf(PastRecord(listOf("SAMPLE BAKERY 0042"), "c-bake", "Bakery", income = false))
        assertEquals("c-bake", pick("You spent at Sample Bakery 7781", "Sample Bakery 7781", history = history)?.id)
    }

    @Test
    fun refusedCategoryIsSkippedForTheNextBest() {
        val transfer = WalletRule(listOf("sample person"), "c-transfer", "Transfer", emptyList())
        val history = listOf(PastRecord(listOf("Sample Person"), "c-gifts", "Gifts", false))
        val p = pick("You've sent RM10.00 to Sample Person", "Sample Person", walletRules = listOf(transfer), history = history)
        assertEquals("c-transfer", p!!.id)
        val q = Categorizer.pick(
            "You've sent RM10.00 to Sample Person", "Sample Person", -10.0, "a-1",
            mapOf("sample person" to ("c-transfer" to "Transfer")), emptyList(), listOf(transfer), history, setOf("c-transfer"),
        )
        assertEquals("c-gifts", q!!.id)
    }

    @Test
    fun refusedCategoryIdIsReadFromWalletsError() {
        val err = "category 'abc-123' is a system category and cannot be assigned directly; mark the record as a transfer instead"
        assertEquals("abc-123", Categorizer.refusedCategory(err))
        assertNull(Categorizer.refusedCategory("amount must not be zero"))
    }

    @Test
    fun nothingKnownGivesNoCategory() {
        assertNull(pick("You spent RM 5.00", null))
    }
}
