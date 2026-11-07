package app.banksync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpayLaterTest {
    private val alert = "Sample Shopee: Your SPayLater payment is successful " +
        "We have successfully deducted 17.63 from your SPayLater account as a payment to a linked partner Sample Merchant."

    /** The (posted time, amount) the listener passes for [alert] kept at [posted]. */
    private fun paid(posted: Long) = SpayLater.payments(listOf(posted to alert))

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
        assertTrue(SpayLater.isPaymentCopy(copy, posted + 5 * 60_000, paid(posted)))
    }

    @Test
    fun aCopyNeedsTheSameAmountWithinAnHour() {
        val posted = 1_000_000_000_000L
        val other = "Sample Shopee Payment successful You paid RM17.64 to Sample Merchant."
        assertFalse(SpayLater.isPaymentCopy(other, posted, paid(posted)))
        val same = "Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."
        assertFalse(SpayLater.isPaymentCopy(same, posted + 2 * 3_600_000L, paid(posted)))
    }

    @Test
    fun onlyPaymentActivityIsACopy() {
        val posted = 1_000_000_000_000L
        // Not a payment word, and the SPayLater alert itself is never its own copy.
        assertFalse(SpayLater.isPaymentCopy("Sample Shopee Your order of RM17.63 has shipped.", posted, paid(posted)))
        assertFalse(SpayLater.isPaymentCopy(alert, posted, paid(posted)))
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

    @Test
    fun aCopyPostedBeforeTheAlertIsRecognisedToo() {
        val posted = 1_000_000_000_000L
        val copy = "Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."
        assertTrue(SpayLater.isPaymentCopy(copy, posted - 30_000, paid(posted)))
    }

    @Test
    fun anIgnoredPaymentStillMarksItsCopy() {
        // Ignored alerts aren't kept; their time and amount are remembered instead.
        val posted = 1_000_000_000_000L
        val remembered = SpayLater.remember(emptyList(), posted, -17.63)
        val copy = "Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."
        assertTrue(SpayLater.isPaymentCopy(copy, posted + 60_000, remembered))
    }

    @Test
    fun rememberKeepsAnHourAndAtMostTwenty() {
        val posted = 1_000_000_000_000L
        val old = listOf(posted - 2 * SpayLater.HOUR to -5.0, posted - 60_000 to -6.0)
        assertEquals(listOf(posted - 60_000 to -6.0, posted to -7.0), SpayLater.remember(old, posted, -7.0))
        val many = (1..30).map { posted + it to -it.toDouble() }
        assertEquals(20, SpayLater.remember(many, posted + 31, -31.0).size)
    }

    @Test
    fun earlierCopiesFindsOnlyMatchingPaymentActivity() {
        val posted = 1_000_000_000_000L
        val waiting = listOf(
            Triple(1L, posted - 20_000, "Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."),
            Triple(2L, posted - 20_000, "Sample Shopee Payment successful You paid RM9.99 to Sample Cafe."),
            Triple(3L, posted - 2 * SpayLater.HOUR, "Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."),
            Triple(4L, posted - 20_000, "Sample Shopee Payment refunded RM17.63 to your ShopeePay."),
        )
        assertEquals(listOf(1L), SpayLater.earlierCopies(waiting, posted, -17.63))
    }

    @Test
    fun onlyAPaymentNamingSpayLaterIsAnAlert() {
        assertTrue(SpayLater.isAlert(alert))
        assertTrue(SpayLater.mentions("Sample Shopee Enjoy 0% instalments with SPayLater, up to RM1,000 off"))
        assertFalse(SpayLater.isAlert("Sample Shopee Enjoy 0% instalments with SPayLater, up to RM1,000 off"))
        assertFalse(SpayLater.isAlert("Sample Shopee Your SPayLater refund of 17.63 has been deducted from your bill."))
        assertFalse(SpayLater.mentions("Sample Shopee Payment successful You paid RM17.63 to Sample Merchant."))
    }

    @Test
    fun paymentsSkipsAlertsThatArentSpayLaterPayments() {
        val notes = listOf(1L to alert, 2L to "Sample Shopee Your order has shipped.", 3L to "Sample Shopee SPayLater offer RM5 off")
        assertEquals(listOf(1L to -17.63), SpayLater.payments(notes))
    }
}
