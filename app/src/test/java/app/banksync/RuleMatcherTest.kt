package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {
    private val any = Rule("app.mae", "MAE", "", "acc-savings", "Savings")
    private val card = Rule("app.mae", "MAE", "1234", "acc-card", "Visa")

    @Test
    fun specificMatchBeatsAppWideRule() {
        val r = RuleMatcher.pick(listOf(any, card), "You spent RM 5.00 at X with your Visa ending 1234.")
        assertEquals("acc-card", r!!.accountId)
    }

    @Test
    fun fallsBackToAppWideRule() {
        val r = RuleMatcher.pick(listOf(any, card), "You spent RM 5.00 at X with your Visa ending 9999.")
        assertEquals("acc-savings", r!!.accountId)
    }

    @Test
    fun noAppWideRuleAndNoMatchMeansNoAccount() {
        assertNull(RuleMatcher.pick(listOf(card), "You spent RM 5.00 at X with your Visa ending 9999."))
    }

    @Test
    fun categoryRuleMatchesCaseInsensitively() {
        val rules = listOf(CategoryRule("grab", "cat-transport", "Transport"))
        assertEquals("cat-transport", CategoryMatcher.pick(rules, "You spent RM 9.00 at GRAB*RIDE")!!.categoryId)
        assertNull(CategoryMatcher.pick(rules, "You spent RM 9.00 at Sample Cafe"))
    }

    private val hour = 3_600_000L

    @Test
    fun catchUpLooksBackByTheWindow() {
        val now = 1_000 * hour
        assertEquals(now - 24 * hour, CatchUp.cutoff(now, since = 0, hours = 24, keepDays = 7))
    }

    @Test
    fun catchUpNeverReachesBeforeHistoryIsKept() {
        val now = 1_000 * hour
        // 7 days of history can't back a 10-day scan, or captured alerts would be re-added.
        assertEquals(now - 7 * 24 * hour, CatchUp.cutoff(now, since = 0, hours = 240, keepDays = 7))
    }

    @Test
    fun catchUpNeverImportsAlertsFromBeforeSetup() {
        val now = 1_000 * hour
        val since = now - 2 * hour
        assertEquals(since, CatchUp.cutoff(now, since = since, hours = 24, keepDays = 7))
    }

    @Test
    fun blankCategoryRuleNeverMatches() {
        assertNull(CategoryMatcher.pick(listOf(CategoryRule(" ", "x", "X")), "anything"))
    }
}
