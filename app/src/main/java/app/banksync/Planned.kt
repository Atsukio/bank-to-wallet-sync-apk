package app.banksync

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Planned payments (Wallet's standing orders). An alert matching one that's due is held instead of
 * sent: marking the planned payment paid in Wallet creates its own record, so sending ours too would
 * put it in twice, and the API can't mark one paid. Once Wallet lists the occurrence as paid with a
 * record, or (as it may list none) the order has moved on to a later due date and its account has a
 * record of that amount, the held row is done. Pure, unit-tested.
 */
object Planned {
    /** How many days before or after its due day an alert may arrive and still be the planned payment. */
    const val WINDOW_DAYS = 3L

    /** How many days after its due day a held row stops waiting quietly and asks whether it will be marked paid. */
    const val STALE_DAYS = 7L

    /** Whether a row held for an occurrence due [dueMs] has waited past [STALE_DAYS], so Wallet may never mark it paid. */
    fun stale(dueMs: Long?, now: Long) = dueMs != null && now - dueMs > STALE_DAYS * 86_400_000L

    /** What a check of every held planned payment found: [before] held, [left] still held after it. */
    fun result(ok: Boolean, before: Int, left: Int): String {
        val closed = before - left
        return when {
            !ok -> "Couldn't reach Wallet. Try again later"
            closed <= 0 -> "None marked paid in Wallet yet"
            left == 0 -> "Paid in Wallet: ${if (closed == 1) "it's" else "all $closed are"} done"
            else -> "$closed of $before paid in Wallet and done. $left still waiting"
        }
    }

    /**
     * A standing order: [amount] signed like a record's (minus for money out), [payee] its counterParty.
     * [startMs] (generateFromDate) is its first payment, [every] how far apart they are, [paid] how many
     * Wallet has had (paymentCount); see [countedPaid]. [note] and [categoryId] are what its records get.
     */
    class Order(
        val id: String, val name: String, val account: String, val amount: Double, val currency: String, val dueMs: Long?,
        val payee: String = "", val startMs: Long? = null, val every: Pair<ChronoUnit, Long>? = null, val paid: Int? = null,
        val note: String = "", val categoryId: String = "",
    )

    /** What Wallet has for a paid planned payment, copied to the held row so it shows the same once done. */
    class Paid(val recordId: String, val payee: String, val note: String, val categoryId: String, val categoryName: String)

    /** One occurrence of an order. [dueMs] is when it's expected ([originalMs] moved to a working day, say). */
    class Item(
        val orderId: String, val dueMs: Long, val originalMs: Long,
        val paid: Boolean, val dismissed: Boolean, val recordIds: List<String>,
    ) {
        val open get() = !paid && !dismissed && recordIds.isEmpty()
    }

    /** An occurrence still to be paid, with what an alert must match. */
    class Due(val orderId: String, val name: String, val account: String, val amount: Double, val currency: String, val dueMs: Long, val payee: String = "") {
        /** Identifies the occurrence, so two alerts never hold the same one. */
        val key get() = key(orderId, dueMs)
    }

    fun key(orderId: String, dueMs: Long) = "$orderId|$dueMs"

    /** Reads a standing order from Wallet, or null without an account or amount to match on. */
    fun order(o: JSONObject): Order? {
        val id = o.optString("id").ifEmpty { return null }
        val account = o.optString("accountId").ifEmpty { return null }
        val raw = o.optDouble("amount", Double.NaN).takeUnless { it.isNaN() || it == 0.0 } ?: return null
        // The direction comes from its type when it has one, whichever way the amount is stored.
        val amount = when (o.optString("type").lowercase()) {
            "income" -> abs(raw)
            "expense" -> -abs(raw)
            else -> raw
        }
        val name = o.optString("name").ifBlank { o.optString("counterParty") }.ifBlank { "Planned payment" }
        return Order(
            id, name, account, amount, o.optString("currencyCode"), time(o.optString("dueDate")), o.optString("counterParty"),
            time(o.optString("generateFromDate")), every(o.optString("recurrenceRule")),
            o.optInt("paymentCount", -1).takeIf { it >= 0 && o.has("paymentCount") },
            o.text("note"), o.text("categoryId"),
        )
    }

    /** From Wallet's record of the payment: what it has now, with any change made while marking it paid. */
    fun paid(record: JSONObject, categories: Map<String, String>): Paid {
        val category = record.optJSONObject("category")
        val categoryId = category?.text("id") ?: record.text("categoryId")
        val name = category?.text("name").orEmpty().ifEmpty { categories[categoryId].orEmpty() }
        return Paid(record.text("id"), record.text("counterParty"), record.text("note"), categoryId, name)
    }

    /** When only the record's id is known: what the planned payment gives its records. [categories] names them by id. */
    fun paid(recordId: String, order: Order?, categories: Map<String, String>) = Paid(
        recordId, order?.payee.orEmpty(), order?.note.orEmpty(), order?.categoryId.orEmpty(),
        categories[order?.categoryId].orEmpty(),
    )

    /** How far apart a repeat rule's payments are ("FREQ=MONTHLY;INTERVAL=2"), or null for a rule this doesn't read. */
    fun every(rule: String?): Pair<ChronoUnit, Long>? {
        val parts = rule.orEmpty().split(';', '\n', ':').map { it.trim().uppercase() }
        val unit = when (parts.firstOrNull { it.startsWith("FREQ=") }?.substringAfter('=')) {
            "DAILY" -> ChronoUnit.DAYS
            "WEEKLY" -> ChronoUnit.WEEKS
            "MONTHLY" -> ChronoUnit.MONTHS
            "YEARLY" -> ChronoUnit.YEARS
            else -> return null
        }
        val interval = parts.firstOrNull { it.startsWith("INTERVAL=") }?.substringAfter('=')?.toLongOrNull() ?: 1
        return if (interval > 0) unit to interval else null
    }

    fun item(o: JSONObject): Item? {
        val orderId = o.optString("standingOrderId").ifEmpty { return null }
        val original = time(o.optString("originalDate"))
        val due = time(o.optString("alignedDate")) ?: original ?: return null
        val ids = o.optJSONArray("recordIds")?.strings().orEmpty().ifEmpty { listOfNotNull(o.optString("recordId").ifEmpty { null }) }
        return Item(orderId, due, original ?: due, time(o.optString("paidDate")) != null, o.optBoolean("dismissed"), ids)
    }

    /**
     * What's still to be paid: open occurrences Wallet lists, and each order's next due date when no
     * occurrence is listed for that day (Wallet may only list one once it's due).
     */
    fun upcoming(orders: List<Order>, items: List<Item>): List<Due> {
        val byId = orders.associateBy { it.id }
        val out = items.filter { it.open }.mapNotNull { i ->
            val o = byId[i.orderId] ?: return@mapNotNull null
            Due(o.id, o.name, o.account, o.amount, o.currency, i.dueMs, o.payee)
        }.toMutableList()
        for (o in orders) {
            val due = o.dueMs ?: continue
            if (items.any { it.orderId == o.id && (sameDay(it.dueMs, due) || sameDay(it.originalMs, due)) }) continue
            out += Due(o.id, o.name, o.account, o.amount, o.currency, due, o.payee)
        }
        return out
    }

    /**
     * The planned payment an alert ([amount] signed, on [account] in [currency], at [timeMs]) is, or
     * null: the same account and amount, the same currency when both are known, due within
     * [WINDOW_DAYS] days in [zone]. Occurrences in [taken] are already held for another alert. The
     * payee never decides whether it matches, since people name a planned payment their own way
     * ("Uncle Sample" for an alert's "SAMPLE PERSON"); it only breaks a tie: the one sharing the most
     * words with the alert's [payee] wins, then the closest due day.
     */
    fun match(dues: List<Due>, amount: Double, account: String, currency: String, timeMs: Long, zone: ZoneId, taken: Set<String>, payee: String? = null): Due? {
        val day = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        fun days(d: Due) = abs(ChronoUnit.DAYS.between(Instant.ofEpochMilli(d.dueMs).atZone(zone).toLocalDate(), day))
        val said = words(payee)
        return dues
            .filter {
                it.account == account && abs(it.amount - amount) < 0.005 &&
                    (it.currency.isEmpty() || currency.isEmpty() || it.currency.equals(currency, ignoreCase = true)) &&
                    days(it) <= WINDOW_DAYS && it.key !in taken
            }
            .minWithOrNull(compareByDescending<Due> { (words(it.name) + words(it.payee)).count { w -> w in said } }.thenBy { days(it) })
    }

    /** Words too common in payee names to say two are the same person or business. */
    private val COMMON = setOf("bin", "binti", "bte", "sdn", "bhd", "the", "and", "for", "from", "payment", "transfer", "planned")

    /** A name's distinctive words, lower case: "Sample Person Bin Demo" → sample, person, demo. */
    fun words(s: String?): Set<String> =
        s.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in COMMON }.toSet()

    /** Wallet's record for the occurrence of [orderId] due at [dueMs], once it's been marked paid there; null until then. */
    fun paidRecord(items: List<Item>, orderId: String, dueMs: Long): String? =
        items.firstOrNull {
            it.orderId == orderId && it.recordIds.isNotEmpty() && (sameDay(it.dueMs, dueMs) || sameDay(it.originalMs, dueMs))
        }?.recordIds?.first()

    /**
     * Whether Wallet has moved on from the occurrence of [orderId] due at [dueMs]: the order's next due
     * date is a later day, or the order is gone or has none left (its last one, say). Wallet may list no
     * occurrences at all, and this is then the only sign one was paid (or skipped, so see [paidBy]).
     */
    fun movedOn(orders: List<Order>, orderId: String, dueMs: Long): Boolean {
        val next = orders.firstOrNull { it.id == orderId }?.dueMs ?: return true
        return next > dueMs && !sameDay(next, dueMs)
    }

    /**
     * Whether Wallet counts the occurrence of [orderId] due at [dueMs] as paid: its paymentCount has reached
     * the number of payments from its first one up to that day. Wallet leaves dueDate where it was and
     * lists no occurrences, so this is how marking one paid shows. False when the order doesn't say enough.
     */
    fun countedPaid(orders: List<Order>, orderId: String, dueMs: Long): Boolean {
        val o = orders.firstOrNull { it.id == orderId } ?: return false
        val start = o.startMs ?: return false
        val (unit, step) = o.every ?: return false
        val paid = o.paid ?: return false
        val first = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC)
        // A day's slack either way: Wallet may write a day's midnight in another zone.
        val last = dueMs + 86_400_000L
        var due = 0
        while (due <= paid && first.plus(due * step, unit).toInstant().toEpochMilli() <= last) due++
        return due in 1..paid
    }

    /**
     * The Wallet record that paid an occurrence due at [dueMs] for [amount] (signed), among [records]
     * from its account (Wallet's record JSON), or null. Marking it paid makes a record of that amount,
     * dated from [WINDOW_DAYS] days before the due day on; skipping it makes none, so the held alert
     * stays. Records in [taken] already closed another one.
     */
    fun paidBy(records: List<JSONObject>, amount: Double, dueMs: Long, taken: Set<String>): JSONObject? {
        val from = dueMs - (WINDOW_DAYS + 1) * 86_400_000L
        return records.filter {
            val value = it.optJSONObject("amount")?.optDouble("value", Double.NaN) ?: Double.NaN
            val at = time(it.optString("recordDate"))
            abs(value - amount) < 0.005 && at != null && at >= from && it.optString("id").let { id -> id.isNotEmpty() && id !in taken }
        }.minByOrNull { abs(time(it.optString("recordDate"))!! - dueMs) }
    }

    /**
     * "Check planned payments": what Wallet's answers look like, as counts and field names only, so it
     * can be shared without giving away a name or amount.
     */
    fun check(orders: List<JSONObject>, items: List<JSONObject>, nowMs: Long): String {
        val read = orders.mapNotNull { order(it) }
        val its = items.mapNotNull { item(it) }
        fun typed(t: String) = orders.filter { it.optString("type").equals(t, ignoreCase = true) && !it.optDouble("amount", Double.NaN).isNaN() }
        fun signs(t: String): String {
            val list = typed(t)
            if (list.isEmpty()) return "none to look at"
            val minus = list.count { it.getDouble("amount") < 0 }
            return "$minus of ${list.size} stored below zero, ${list.size - minus} above"
        }
        val future = its.filter { it.open && it.dueMs > nowMs }
        val paid = its.filter { it.paid || it.recordIds.isNotEmpty() }
        // Recent ones answer "does marking one paid link its record?" for one just marked.
        val week = paid.filter { it.dueMs > nowMs - 7 * 86_400_000L }
        fun fields(list: List<JSONObject>) = list.flatMap { it.keys().asSequence().toList() }.toSortedSet().joinToString(", ").ifEmpty { "none" }
        return listOf(
            "Planned payments: ${orders.size} (${read.size} with an account and amount to match on)",
            "Expenses: ${signs("expense")}",
            "Income: ${signs("income")}",
            "",
            "Occurrences listed: ${items.size}",
            "Not paid yet and due later: ${future.size} " +
                if (future.isEmpty()) "(none listed, so the app goes by each payment's next due date)" else "(upcoming ones are listed)",
            "Paid: ${paid.size}, with a Wallet record linked: ${paid.count { it.recordIds.isNotEmpty() }}",
            "Paid, due in the last 7 days or later: ${week.size}, with a record linked: ${week.count { it.recordIds.isNotEmpty() }}",
            "Dismissed: ${its.count { it.dismissed }}",
            "",
            "Fields on a planned payment: ${fields(orders)}",
            "Fields on an occurrence: ${fields(items)}",
        ).joinToString("\n")
    }

    /**
     * For the check: how each planned payment behind a held alert stands, as day differences from the
     * held due day, counts and the repeat rule's frequency only, to see what marking one paid changes.
     * [held] is each held alert's order id and due day.
     */
    fun heldCheck(orders: List<JSONObject>, held: List<Pair<String, Long>>, nowMs: Long): String =
        held.mapIndexed { i, (orderId, due) ->
            val o = orders.firstOrNull { it.optString("id") == orderId } ?: return@mapIndexed "Held ${i + 1}: planned payment not found in Wallet"
            fun days(field: String) = time(o.optString(field))?.let { "${((it - due) / 86_400_000.0).roundToLong()} days" } ?: "not set"
            val hours = time(o.optString("updatedAt"))?.let { "${(nowMs - it) / 3_600_000} hours ago" } ?: "not set"
            // Only how often it repeats (frequency, interval, count) and the rule's field names say nothing about the payment.
            val often = Regex("freq|interval|count", RegexOption.IGNORE_CASE)
            val rule = when (val r = o.opt("recurrenceRule")) {
                null, JSONObject.NULL -> "none"
                is JSONObject -> r.keys().asSequence().joinToString(", ") { k -> if (often.containsMatchIn(k)) "$k=${r.opt(k)}" else k }
                else -> r.toString().split(';', '\n').map { it.trim() }.filter { often.containsMatchIn(it.substringBefore('=')) }
                    .joinToString(";").ifEmpty { "a ${r.javaClass.simpleName} without a frequency" }
            }
            "Held ${i + 1}: dueDate ${days("dueDate")}, generateFromDate ${days("generateFromDate")} from the day it was held for; " +
                "paymentCount ${o.opt("paymentCount") ?: "not set"}; manualPayment ${o.opt("manualPayment") ?: "not set"}; " +
                "changed $hours; repeats $rule"
        }.joinToString("\n")

    /** A Wallet date ("2025-12-01T00:00:00Z", or a plain day taken as UTC midnight) in ms, or null. */
    fun time(s: String?): Long? {
        if (s.isNullOrBlank() || s == "null") return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Within a day of each other: the same occurrence, whichever zone Wallet wrote its midnight in. */
    private fun sameDay(a: Long, b: Long) = abs(a - b) < 86_400_000L

    /** A string field, empty when missing or null (org.json would give "null"). */
    private fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)

    private fun JSONArray.strings() = (0 until length()).mapNotNull { optString(it).ifEmpty { null } }
}
