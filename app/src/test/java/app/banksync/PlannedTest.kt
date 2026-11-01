package app.banksync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Made-up planned payments in the shape Wallet's standing orders API describes. */
class PlannedTest {
    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private fun ms(iso: String) = Planned.time(iso)!!

    private fun orderJson(id: String = "so-1", amount: Double = 900.0, type: String? = "expense", due: String? = "2026-11-03T00:00:00Z") =
        JSONObject().put("id", id).put("name", "Sample Rent").put("accountId", "acc-1").put("amount", amount)
            .put("currencyCode", "MYR").apply {
                type?.let { put("type", it) }
                due?.let { put("dueDate", it) }
            }

    private fun itemJson(orderId: String = "so-1", date: String = "2026-11-03T00:00:00Z", paid: String? = null, records: List<String> = emptyList(), dismissed: Boolean = false) =
        JSONObject().put("standingOrderId", orderId).put("originalDate", date).put("alignedDate", date)
            .put("dismissed", dismissed).put("recordIds", JSONArray(records)).apply { paid?.let { put("paidDate", it) } }

    private fun dues(vararg items: JSONObject, order: JSONObject = orderJson()) =
        Planned.upcoming(listOfNotNull(Planned.order(order)), items.mapNotNull { Planned.item(it) })

    @Test
    fun theTypeDecidesTheDirection() {
        assertEquals(-900.0, Planned.order(orderJson(amount = 900.0))!!.amount, 0.0)
        assertEquals(-900.0, Planned.order(orderJson(amount = -900.0))!!.amount, 0.0)
        assertEquals(2500.0, Planned.order(orderJson(amount = -2500.0, type = "income"))!!.amount, 0.0)
        assertEquals(-12.0, Planned.order(orderJson(amount = -12.0, type = null))!!.amount, 0.0)
    }

    @Test
    fun anOrderWithoutAccountOrAmountIsSkipped() {
        assertNull(Planned.order(orderJson().put("accountId", "")))
        assertNull(Planned.order(orderJson().put("amount", 0)))
    }

    @Test
    fun matchesWithinThreeDaysOfTheDueDay() {
        val d = dues()
        assertNotNull(Planned.match(d, -900.0, "acc-1", "MYR", ms("2026-11-06T10:00:00+08:00"), zone, emptySet()))
        assertNotNull(Planned.match(d, -900.0, "acc-1", "MYR", ms("2026-10-31T09:00:00+08:00"), zone, emptySet()))
        assertNull(Planned.match(d, -900.0, "acc-1", "MYR", ms("2026-11-07T09:00:00+08:00"), zone, emptySet()))
    }

    @Test
    fun needsTheSameAccountAmountDirectionAndCurrency() {
        val d = dues()
        val t = ms("2026-11-03T09:00:00+08:00")
        assertNull(Planned.match(d, -900.0, "acc-2", "MYR", t, zone, emptySet()))
        assertNull(Planned.match(d, -899.0, "acc-1", "MYR", t, zone, emptySet()))
        assertNull(Planned.match(d, 900.0, "acc-1", "MYR", t, zone, emptySet()))
        assertNull(Planned.match(d, -900.0, "acc-1", "USD", t, zone, emptySet()))
        assertNotNull(Planned.match(d, -900.0, "acc-1", "", t, zone, emptySet()))
    }

    @Test
    fun anOccurrenceHeldForOneAlertIsNotMatchedAgain() {
        val d = dues()
        val t = ms("2026-11-03T09:00:00+08:00")
        val first = Planned.match(d, -900.0, "acc-1", "MYR", t, zone, emptySet())!!
        assertNull(Planned.match(d, -900.0, "acc-1", "MYR", t, zone, setOf(first.key)))
    }

    @Test
    fun theClosestDueDayWins() {
        val d = dues(itemJson(date = "2026-11-01T00:00:00Z"), itemJson(date = "2026-11-04T00:00:00Z"), order = orderJson(due = null))
        val hit = Planned.match(d, -900.0, "acc-1", "MYR", ms("2026-11-03T20:00:00+08:00"), zone, emptySet())!!
        assertEquals(ms("2026-11-04T00:00:00Z"), hit.dueMs)
    }

    @Test
    fun thePayeeOnlyBreaksATie() {
        val tuition = Planned.order(orderJson(id = "so-2", due = "2026-11-03T00:00:00Z").put("name", "Tuition").put("counterParty", "Tutor Demo"))!!
        val loan = Planned.order(orderJson(id = "so-3", due = "2026-11-05T00:00:00Z").put("name", "Uncle Sample"))!!
        val d = Planned.upcoming(listOf(tuition, loan), emptyList())
        val t = ms("2026-11-03T09:00:00+08:00")
        // A word in common beats a closer due day; none in common falls back to the closest day.
        assertEquals("so-3", Planned.match(d, -900.0, "acc-1", "MYR", t, zone, emptySet(), "SAMPLE PERSON")!!.orderId)
        assertEquals("so-2", Planned.match(d, -900.0, "acc-1", "MYR", t, zone, emptySet(), "DEMO TUTOR")!!.orderId)
        assertEquals("so-2", Planned.match(d, -900.0, "acc-1", "MYR", t, zone, emptySet(), "EXAMPLE TRADING SDN BHD")!!.orderId)
        assertEquals("so-2", Planned.match(d, -900.0, "acc-1", "MYR", t, zone, emptySet(), null)!!.orderId)
        // A different name never stops the only candidate matching.
        assertNotNull(Planned.match(listOf(d[1]), -900.0, "acc-1", "MYR", t, zone, emptySet(), "OTHER PERSON"))
    }

    @Test
    fun paidOrDismissedOccurrencesAreNotDue() {
        val paid = dues(itemJson(paid = "2026-11-03T00:00:00Z", records = listOf("rec-1")), order = orderJson(due = "2026-12-03T00:00:00Z"))
        assertEquals(listOf(ms("2026-12-03T00:00:00Z")), paid.map { it.dueMs })
        val dismissed = dues(itemJson(dismissed = true), order = orderJson(due = "2026-12-03T00:00:00Z"))
        assertEquals(listOf(ms("2026-12-03T00:00:00Z")), dismissed.map { it.dueMs })
    }

    @Test
    fun theNextDueDateCountsOnceWhenAnOccurrenceIsAlsoListed() {
        assertEquals(1, dues(itemJson()).size)
        assertEquals(1, dues().size)
    }

    @Test
    fun aHeldRowClosesOnceWalletLinksARecord() {
        val due = ms("2026-11-03T00:00:00Z")
        val open = listOf(Planned.item(itemJson())!!)
        assertNull(Planned.paidRecord(open, "so-1", due))
        val paidNoRecord = listOf(Planned.item(itemJson(paid = "2026-11-03T05:00:00Z"))!!)
        assertNull(Planned.paidRecord(paidNoRecord, "so-1", due))
        val linked = listOf(Planned.item(itemJson(paid = "2026-11-03T05:00:00Z", records = listOf("rec-9")))!!)
        assertEquals("rec-9", Planned.paidRecord(linked, "so-1", due))
        assertNull(Planned.paidRecord(linked, "so-2", due))
    }

    @Test
    fun anOrderHasMovedOnOnceItsNextDueDayIsLater() {
        val due = ms("2026-11-03T00:00:00Z")
        val still = listOf(Planned.order(orderJson(due = "2026-11-03T00:00:00Z"))!!)
        assertTrue(!Planned.movedOn(still, "so-1", due))
        // Wallet's midnight in another zone is still the same day.
        assertTrue(!Planned.movedOn(listOf(Planned.order(orderJson(due = "2026-11-03T16:00:00Z"))!!), "so-1", due))
        assertTrue(Planned.movedOn(listOf(Planned.order(orderJson(due = "2026-12-03T00:00:00Z"))!!), "so-1", due))
        assertTrue(Planned.movedOn(listOf(Planned.order(orderJson(due = null))!!), "so-1", due))
        assertTrue(Planned.movedOn(emptyList(), "so-1", due))
    }

    @Test
    fun walletCountsItPaidOncePaymentCountReachesThatOccurrence() {
        fun monthly(start: String, count: Int, rule: String = "RRULE:FREQ=MONTHLY") =
            listOf(Planned.order(orderJson().put("generateFromDate", start).put("paymentCount", count).put("recurrenceRule", rule))!!)
        val due = ms("2026-11-03T00:00:00Z")
        // Started 3 months before: August to November is 4 payments.
        assertTrue(!Planned.countedPaid(monthly("2026-08-03T00:00:00Z", 3), "so-1", due))
        assertTrue(Planned.countedPaid(monthly("2026-08-03T00:00:00Z", 4), "so-1", due))
        // Midnight in Malaysia, written in UTC, is still that day.
        assertTrue(Planned.countedPaid(monthly("2026-08-02T16:00:00Z", 4), "so-1", ms("2026-11-02T16:00:00Z")))
        // Every 2 months from May: May, July, September, November.
        assertTrue(!Planned.countedPaid(monthly("2026-05-03T00:00:00Z", 3, "FREQ=MONTHLY;INTERVAL=2"), "so-1", due))
        assertTrue(Planned.countedPaid(monthly("2026-05-03T00:00:00Z", 4, "FREQ=MONTHLY;INTERVAL=2"), "so-1", due))
        // Not enough to go on: no rule, no count.
        assertTrue(!Planned.countedPaid(monthly("2026-08-03T00:00:00Z", 9, "something else"), "so-1", due))
        assertTrue(!Planned.countedPaid(listOf(Planned.order(orderJson().put("generateFromDate", "2026-08-03T00:00:00Z").put("recurrenceRule", "FREQ=MONTHLY"))!!), "so-1", due))
        assertEquals(java.time.temporal.ChronoUnit.WEEKS to 2L, Planned.every("FREQ=WEEKLY;INTERVAL=2"))
    }

    private fun record(id: String, value: Double, date: String) =
        JSONObject().put("id", id).put("amount", JSONObject().put("value", value)).put("recordDate", date)

    @Test
    fun theRecordThatPaidItHasTheAmountAndADateNearTheDueDay() {
        val due = ms("2026-11-03T00:00:00Z")
        val records = listOf(
            record("rec-old", -900.0, "2026-10-03T09:00:00Z"),
            record("rec-other", -45.5, "2026-11-03T09:00:00Z"),
            record("rec-paid", -900.0, "2026-11-04T02:00:00Z"),
        )
        assertEquals("rec-paid", Planned.paidBy(records, -900.0, due, emptySet())?.optString("id"))
        // Skipped in Wallet: no record of that amount, so it stays held.
        assertNull(Planned.paidBy(records.take(2), -900.0, due, emptySet()))
        // One this app sent, or one that closed another, doesn't count.
        assertNull(Planned.paidBy(records, -900.0, due, setOf("rec-paid")))
        // The direction matters.
        assertNull(Planned.paidBy(records, 900.0, due, emptySet()))
    }

    @Test
    fun aClosedRowTakesWhatWalletHasForThePayment() {
        val names = mapOf("cat-home" to "Housing", "cat-rent" to "Rent")
        val fromRecord = Planned.paid(
            record("rec-paid", -900.0, "2026-11-04T02:00:00Z").put("counterParty", "Sample Landlord")
                .put("note", "November").put("category", JSONObject().put("id", "cat-rent").put("name", "Rent")),
            names,
        )
        assertEquals(listOf("rec-paid", "Sample Landlord", "November", "cat-rent", "Rent"),
            listOf(fromRecord.recordId, fromRecord.payee, fromRecord.note, fromRecord.categoryId, fromRecord.categoryName))
        // Nulls read as nothing, not "null"; a category given by id only is named from the cached list.
        val bare = Planned.paid(record("rec-2", -900.0, "2026-11-04T02:00:00Z").put("note", JSONObject.NULL).put("categoryId", "cat-home"), names)
        assertEquals(listOf("", "", "cat-home", "Housing"), listOf(bare.payee, bare.note, bare.categoryId, bare.categoryName))
        // Only the record's id known: the planned payment's own details.
        val order = Planned.order(orderJson().put("counterParty", "Sample Landlord").put("note", "Flat 1").put("categoryId", "cat-home"))
        val fromOrder = Planned.paid("rec-3", order, names)
        assertEquals(listOf("rec-3", "Sample Landlord", "Flat 1", "Housing"),
            listOf(fromOrder.recordId, fromOrder.payee, fromOrder.note, fromOrder.categoryName))
    }

    @Test
    fun theHeldCheckGivesDayDifferencesAndFrequencyOnly() {
        val order = orderJson(due = "2026-10-03T00:00:00Z").put("generateFromDate", "2026-12-03T00:00:00Z")
            .put("paymentCount", 2).put("updatedAt", "2026-11-04T00:00:00Z").put("counterParty", "Sample Landlord")
            .put("recurrenceRule", "DTSTART:20261003T000000Z\nRRULE:FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=3")
        val text = Planned.heldCheck(listOf(order), listOf("so-1" to ms("2026-11-03T00:00:00Z"), "so-9" to 0L), ms("2026-11-04T05:00:00Z"))
        assertTrue(text, "dueDate -31 days, generateFromDate 30 days" in text)
        assertTrue(text, "paymentCount 2" in text && "changed 5 hours ago" in text)
        assertTrue(text, "FREQ=MONTHLY;INTERVAL=1" in text && "BYMONTHDAY" !in text && "20261003" !in text)
        assertTrue(text, "Held 2: planned payment not found" in text)
        assertTrue(text, "Sample" !in text && "900" !in text)
        val asObject = orderJson().put("recurrenceRule", JSONObject().put("frequency", "monthly").put("byMonthDay", 3))
        assertTrue(Planned.heldCheck(listOf(asObject), listOf("so-1" to 0L), 0L).contains("repeats frequency=monthly, byMonthDay"))
    }

    @Test
    fun aSingleRecordIdIsReadToo() {
        val i = Planned.item(itemJson().put("recordIds", JSONArray()).put("recordId", "rec-3"))!!
        assertEquals(listOf("rec-3"), i.recordIds)
    }

    @Test
    fun theCheckGivesCountsAndFieldNamesOnly() {
        val text = Planned.check(
            listOf(orderJson(amount = -900.0)),
            listOf(itemJson(date = "2026-12-03T00:00:00Z"), itemJson(paid = "2026-11-03T05:00:00Z", records = listOf("rec-1"))),
            ms("2026-11-04T00:00:00Z"),
        )
        assertTrue(text, "Expenses: 1 of 1 stored below zero, 0 above" in text)
        assertTrue(text, "Not paid yet and due later: 1 (upcoming ones are listed)" in text)
        assertTrue(text, "Paid: 1, with a Wallet record linked: 1" in text)
        assertTrue(text, "standingOrderId" in text)
        // Nothing that identifies a payment.
        assertTrue(text, "Sample Rent" !in text && "900" !in text && "rec-1" !in text)
    }

    @Test
    fun aHeldRowAsksOnceItIsAWeekPastDue() {
        val due = ms("2026-11-03T00:00:00Z")
        assertFalse(Planned.stale(due, ms("2026-11-10T00:00:00Z")))
        assertTrue(Planned.stale(due, ms("2026-11-10T00:01:00Z")))
        assertFalse(Planned.stale(null, ms("2027-01-01T00:00:00Z")))
    }

    @Test
    fun theCheckSaysHowManyClosed() {
        assertEquals("Couldn't reach Wallet. Try again later", Planned.result(false, 2, 2))
        assertEquals("None marked paid in Wallet yet", Planned.result(true, 2, 2))
        assertEquals("Paid in Wallet: it's done", Planned.result(true, 1, 0))
        assertEquals("Paid in Wallet: all 3 are done", Planned.result(true, 3, 0))
        assertEquals("1 of 3 paid in Wallet and done. 2 still waiting", Planned.result(true, 3, 2))
    }
}
