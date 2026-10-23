package app.banksync

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ParserTest {
    // The listener passes "<title> <body>" to the parser.

    @Test
    fun rytMoneyReceived() {
        val p = Parser.parse("Ryt Bank Your money is in! You've received RM42.00 from Sample Person on 1 Jan, 10:30 AM")
        assertNotNull(p)
        assertEquals(42.00, p!!.amount, 0.0)
        assertEquals("Sample Person", p.payee)
        assertEquals("MYR", p.currency)
    }

    @Test
    fun maeTransferOutHasNoVerbButSaysTo() {
        val p = Parser.parse(
            "Maybank2u: Transfer DuitNow Transfer of RM 10.00 to Sample Person. " +
                "Mobile number: ****1234. REF: ABC123"
        )
        assertNotNull(p)
        assertEquals(-10.00, p!!.amount, 0.0)
        assertEquals("Sample Person", p.payee)
        assertEquals("MYR", p.currency)
    }

    @Test
    fun amountToYourAccountIsNotMoneyOut() {
        // "to your account" is the user's own side, and "received" appears first anyway.
        assertEquals(5.00, Parser.parse("You've received RM5.00 to your account")!!.amount, 0.0)
        assertNull(Parser.parse("RM5.00 to your account"))
    }

    @Test
    fun cimbTransferOutToMobileNumberHasNoPayee() {
        val text = "Transaction Alert Successful DuitNow Transfer to Mobile/ID RM 10.00 to ****1234(Mobile Number) " +
            "on 24-Oct 2026, 00:56:45. Call the no at the back of your card for queries."
        val p = Parser.parse(text)
        assertNotNull(p)
        assertEquals(-10.00, p!!.amount, 0.0)
        assertEquals("MYR", p.currency)
        assertNull(p.payee) // only a masked number, and "Mobile/ID RM 10" must not leak in as a name
        assertEquals(Instant.parse("2026-10-23T16:56:45Z"), Parser.timeOf(text, ZoneOffset.ofHours(8)))
    }

    @Test
    fun cimbFundsReceived() {
        val p = Parser.parse(
            "CIMB OCTO MY You've received RM10.00 in your account ending 1234 on 24-OCT-2026, 12:14:16. " +
                "If you need assistance, please call the number on the back of your card."
        )
        assertNotNull(p)
        assertEquals(10.00, p!!.amount, 0.0)
        assertEquals("MYR", p.currency)
        assertNull(p.payee) // the alert doesn't name the sender
    }

    @Test
    fun alertTimeIsReadFromCimbText() {
        val t = Parser.timeOf("received RM10.00 on 24-OCT-2026, 12:14:16.", ZoneOffset.ofHours(8))
        assertEquals(Instant.parse("2026-10-24T04:14:16Z"), t)
    }

    @Test
    fun alertTimeIsReadFromRytTextWithItsOwnOffset() {
        // 12:14 AM in GMT+8 is 16:14 UTC the day before, whatever zone the phone is in.
        val t = Parser.timeOf("sent RM10.00 on 24/10/2026, 12:14 AM (GMT+8) using", ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-10-23T16:14:00Z"), t)
    }

    @Test
    fun noTimeInTheTextMeansNoTime() {
        assertNull(Parser.timeOf("You spent RM5.00 at Sample Shop", ZoneOffset.UTC))
    }

    @Test
    fun delayedAlertUsesItsOwnTimeNotWhenItWasPosted() {
        val zone = ZoneOffset.ofHours(8)
        val alert = Instant.parse("2026-10-24T04:14:16Z").toEpochMilli()
        val posted = alert + 20 * 60_000 // arrived 20 minutes late
        assertEquals(alert, Parser.recordTime("received on 24-OCT-2026, 12:14:16.", posted, zone))
    }

    @Test
    fun implausibleAlertTimeFallsBackToPostedTime() {
        val zone = ZoneOffset.ofHours(8)
        val alert = Instant.parse("2026-10-24T04:14:16Z").toEpochMilli()
        // Alert time later than the post time (e.g. read in the wrong zone), or older than 3 days.
        assertEquals(alert - 3_600_000, Parser.recordTime("received on 24-OCT-2026, 12:14:16.", alert - 3_600_000, zone))
        val late = alert + 4 * 86_400_000L
        assertEquals(late, Parser.recordTime("received on 24-OCT-2026, 12:14:16.", late, zone))
    }

    @Test
    fun tngMoneyReceived() {
        val p = Parser.parse(
            "TNG eWallet You've received money! Sample Person has transferred RM 10.00 to you. " +
                "Tap here to check the transaction details."
        )
        assertNotNull(p)
        assertEquals(10.00, p!!.amount, 0.0)
        assertEquals("Sample Person", p.payee) // not "you"
        assertEquals("MYR", p.currency)
    }

    @Test
    fun toYouIsNeverThePayee() {
        assertNull(Parser.parse("You received RM5.00 to you.")!!.payee)
    }

    @Test
    fun rytTransferSent() {
        val p = Parser.parse(
            "Ryt Bank Nice! Transfer settled! You've sent RM10.00 to Sample Person on 24/10/2026, " +
                "12:14 AM (GMT+8) using your Main Account"
        )
        assertNotNull(p)
        assertEquals(-10.00, p!!.amount, 0.0)
        assertEquals("Sample Person", p.payee)
        assertEquals("MYR", p.currency)
    }

    @Test
    fun maeCardSpend() {
        val p = Parser.parse(
            "Maybank2u: Card Transaction You've just spent RM 110.25 at SAMPLE SHOP with your " +
                "Maybank Shopee Visa Platinum ending 1234. View your receipt now."
        )
        assertNotNull(p)
        assertEquals(-110.25, p!!.amount, 0.0)
        assertEquals("SAMPLE SHOP", p.payee)
        assertEquals("MYR", p.currency)
    }

    @Test
    fun maeForeignCardSpendDropsPhoneNumberFromPayee() {
        val p = Parser.parse(
            "Maybank2u: Card Transaction You've just spent USD 25.00 at GOogle Play 650-2530000 US with your Maybank Shopee Vi"
        )
        assertEquals(-25.00, p!!.amount, 0.0)
        assertEquals("USD", p.currency)
        assertEquals("GOogle Play", p.payee)
    }

    @Test
    fun maeFundsReceived() {
        val p = Parser.parse(
            "Maybank2u: Funds Received You've received RM 10.00 in your account ending ****1234. REF : ABC123"
        )
        assertNotNull(p)
        assertEquals(10.00, p!!.amount, 0.0)
        assertEquals("MYR", p.currency)
        assertNull(p.payee) // this alert doesn't name the sender
    }

    @Test
    fun amountWithThousandsSeparator() {
        val p = Parser.parse("You've received RM1,234.50 from Sample Person on 1 Jan")
        assertEquals(1234.50, p!!.amount, 0.0)
    }

    @Test
    fun spentIsNegative() {
        val p = Parser.parse("You spent RM12.30 at Sample Cafe on 1 Jan")
        assertEquals(-12.30, p!!.amount, 0.0)
        assertEquals("Sample Cafe", p.payee)
    }

    @Test
    fun foreignCurrencyIsReported() {
        assertEquals("USD", Parser.parse("You spent USD 10.00 at Sample Store")!!.currency)
    }

    @Test
    fun bareDollarSignHasNoCurrency() {
        assertNull(Parser.parse("You spent $10.00 at Sample Store")!!.currency)
    }

    @Test
    fun transferRuleForcesDirection() {
        // "Top up" has no income/expense wording, so the rule supplies the direction.
        assertNull(Parser.parse("Top up of RM50.00 successful"))
        assertEquals(50.00, Parser.parse("Top up of RM50.00 successful", direction = 1)!!.amount, 0.0)
        assertEquals(-50.00, Parser.parse("Top up of RM50.00 successful", direction = -1)!!.amount, 0.0)
    }

    @Test
    fun amountOfFindsTheFirstAmount() {
        assertEquals(12.30, Parser.amountOf("Something odd RM12.30 happened")!!, 0.0)
        assertNull(Parser.amountOf("Your statement is ready"))
    }

    @Test
    fun noDirectionIsNotGuessed() {
        assertNull(Parser.parse("Sample Bank RM10.00 DuitNow 1 Jan"))
    }

    @Test
    fun noAmountIsIgnored() {
        assertNull(Parser.parse("Ryt Bank Your statement is ready"))
    }
}
