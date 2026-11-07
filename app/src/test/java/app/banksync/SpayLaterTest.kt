package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpayLaterTest {
    private val alert = "Sample Shopee: Your SPayLater payment is successful " +
        "We have successfully deducted 17.63 from your SPayLater account as a payment to a linked partner Sample Merchant."

    @Test
    fun readsTheAmountAndPartnerWithNoCurrency() {
        val p = Parser.spayLater(alert)!!
        assertEquals(-17.63, p.amount, 0.001)
        assertEquals("Sample Merchant", p.payee)
        assertNull(p.currency)
        // The generic reader needs a currency sign, so it leaves this alert to the SPayLater reader.
        assertNull(Parser.parse(alert))
    }

    @Test
    fun aPaymentWordWithNoDeductionIsNotAnSpayLaterPayment() {
        assertNull(Parser.spayLater("Sample Shopee Your SPayLater bill is due on 10/11/2026."))
    }

    @Test
    fun spayLaterAlertsAreNotNotices() {
        assertFalse(Parser.isNotice(alert))
    }

    @Test
    fun decimalsReadsEveryAmountWithCents() {
        assertEquals(listOf(1204.5, 17.63), Parser.decimals("Sample total RM1,204.50 and 17.63 paid").take(2))
    }

    @Test
    fun aPaymentActivityCopyingAKeptAlertIsRecognised() {
        val posted = 1_000_000_000_000L
        val copy = "Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."
        assertTrue(SpayLater.isPaymentCopy(copy, posted + 5 * 60_000, listOf(posted to alert)))
    }

    @Test
    fun aCopyNeedsTheSameAmountWithinAnHour() {
        val posted = 1_000_000_000_000L
        val other = "Sample Shopee Payment successful You paid RM17.64 to Sample Merchant."
        assertFalse(SpayLater.isPaymentCopy(other, posted, listOf(posted to alert)))
        val same = "Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."
        assertFalse(SpayLater.isPaymentCopy(same, posted + 2 * 3_600_000L, listOf(posted to alert)))
    }

    @Test
    fun onlyPaymentActivityIsACopy() {
        val posted = 1_000_000_000_000L
        // Not a payment word, and the SPayLater alert itself is never its own copy.
        assertFalse(SpayLater.isPaymentCopy("Sample Shopee Your order of RM17.63 has shipped.", posted, listOf(posted to alert)))
        assertFalse(SpayLater.isPaymentCopy(alert, posted, listOf(posted to alert)))
    }

    @Test
    fun theCurrencyNoteNamesTheAccountsCurrency() {
        assertEquals("Amount in MYR: the alert names no currency.\n", SpayLater.currencyNote("MYR"))
        assertEquals("Amount in the account's currency: the alert names no currency.\n", SpayLater.currencyNote(""))
    }

    @Test
    fun anSpayLaterAccountUnderShopeeIsItsOwnRule() {
        assertTrue(SpayLater.isAccount("SPayLater"))
        assertTrue(SpayLater.isAccount("SPay Later"))
        assertFalse(SpayLater.isAccount("Sample Bank"))
        assertTrue(SpayLater.isRule(Rule("sample.shopee", "Sample Shopee", "", "a1", "SPayLater", "MYR")))
        // Another app's account with the same name is an ordinary rule.
        assertFalse(SpayLater.isRule(Rule("sample.bank", "Sample Bank", "", "a2", "SPayLater", "MYR")))
        // And the Shopee app's ordinary account is not an SPayLater one.
        assertFalse(SpayLater.isRule(Rule("sample.shopee", "Sample Shopee", "", "a3", "ShopeePay", "MYR")))
    }
}
