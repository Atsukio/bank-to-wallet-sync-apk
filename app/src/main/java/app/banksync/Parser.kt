package app.banksync

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class Parsed(val amount: Double, val payee: String?, val currency: String?)

/**
 * Regex-only parsing: no model, no network, microseconds per notification.
 * Returns null instead of guessing when direction or amount is unclear; the notification is then
 * kept as "not understood" so it can be entered by hand.
 */
object Parser {
    private val amountRe = Regex(
        """(A\$|US\$|NZ\$|S\$|[$€£]|\b(?:RM|MYR|AUD|USD|NZD|SGD|EUR|GBP)\s?)\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )
    private val creditRe = Regex(
        """\b(received|credited|deposit(?:ed)?|refund(?:ed)?|cashback|salary|incoming|payment from|transferred in)\b"""
    )
    private val debitRe = Regex(
        """\b(spent|purchase[d]?|paid|debited|debit|withdraw(?:n|al)?|sent|payment|charged|transferred to|transfer to|duitnow to)\b"""
    )
    private val payeeRe = Regex(
        """\b(?:at|to|from)\s+([^.,\n]+?)(?=\s+(?:on|with|using|via|for|ending|card)\b|[.,\n]|$)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * "<method> of RM 10.00 to <name>": an amount followed by "to someone" with no verb. Reads as
     * money going out, unless the "someone" is the user ("to you" / "to your account").
     */
    private val outgoingRe = Regex(
        """(?:a\$|us\$|nz\$|s\$|[$€£]|\b(?:rm|myr|aud|usd|nzd|sgd|eur|gbp)\s?)\s?\d[\d,]*(?:\.\d{1,2})?\s+to\s+(?!you)"""
    )

    /** "<name> has transferred / sent / paid ..." puts the other party before the verb. */
    private val senderRe = Regex(
        """(?:^|[.!?]\s+)([^.!?]+?)\s+has\s+(?:transferred|sent|paid)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Card merchants often end with a phone number and country ("GOOGLE PLAY 650-2530000 US"). */
    private val phoneTailRe = Regex("""\s+\+?\d[0-9 \-]{6,}.*""")

    /** A bare "$" is ambiguous (AUD, USD, SGD...), so it reports no currency rather than guess. */
    private fun currencyOf(token: String): String? = when (token.trim().uppercase()) {
        "RM", "MYR" -> "MYR"
        "A$", "AUD" -> "AUD"
        "US$", "USD" -> "USD"
        "NZ$", "NZD" -> "NZD"
        "S$", "SGD" -> "SGD"
        "€", "EUR" -> "EUR"
        "£", "GBP" -> "GBP"
        else -> null
    }

    private val monthNames = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** "24-OCT-2026, 12:14:16" or "24-Oct 2026, 00:56:45" */
    private val timeNamedMonth = Regex("""(\d{1,2})[-\s]([A-Za-z]{3})[-\s](\d{4}),?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /** "24/10/2026, 12:14 AM (GMT+8)": day first, optional AM/PM and optional GMT offset. */
    private val timeSlashed = Regex(
        """(\d{1,2})/(\d{1,2})/(\d{4}),?\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?(?:\s*\(GMT([+-]\d{1,2})(?::?(\d{2}))?\))?"""
    )

    /**
     * The transaction time written in the alert itself, when there is one. Alerts can arrive late, so
     * this is more accurate than when the notification was posted. Times with no zone are read in [zone].
     */
    fun timeOf(text: String, zone: ZoneId): Instant? {
        timeNamedMonth.find(text)?.let { m ->
            val month = monthNames.indexOf(m.groupValues[2].uppercase()) + 1
            if (month == 0) return null
            return runCatching {
                LocalDateTime.of(
                    m.groupValues[3].toInt(), month, m.groupValues[1].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toIntOrNull() ?: 0
                ).atZone(zone).toInstant()
            }.getOrNull()
        }
        timeSlashed.find(text)?.let { m ->
            var hour = m.groupValues[4].toInt()
            when (m.groupValues[7].uppercase()) {
                "PM" -> if (hour < 12) hour += 12
                "AM" -> if (hour == 12) hour = 0
            }
            return runCatching {
                val offset = m.groupValues[8].takeIf { it.isNotEmpty() }?.let {
                    val h = it.toInt()
                    val min = m.groupValues[9].toIntOrNull() ?: 0
                    ZoneOffset.ofHoursMinutes(h, if (h < 0) -min else min)
                }
                LocalDateTime.of(
                    m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt(),
                    hour, m.groupValues[5].toInt(), m.groupValues[6].toIntOrNull() ?: 0
                ).atZone(offset ?: zone).toInstant()
            }.getOrNull()
        }
        return null
    }

    /**
     * The time to record: the alert's own time when it is plausible, otherwise when it was posted.
     * Plausible means no more than 3 days before posting and no later than 5 minutes after it, which
     * also rejects a time read in the wrong zone.
     */
    fun recordTime(text: String, postedMs: Long, zone: ZoneId): Long {
        val t = timeOf(text, zone)?.toEpochMilli() ?: return postedMs
        return if (t in (postedMs - 3 * 86_400_000L)..(postedMs + 300_000L)) t else postedMs
    }

    fun amountOf(text: String): Double? =
        amountRe.find(text)?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it != 0.0 }

    fun parse(text: String): Parsed? {
        val m = amountRe.find(text) ?: return null
        val amount = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        if (amount == 0.0) return null
        val lower = text.lowercase()
        val credit = creditRe.find(lower)?.range?.first
        val debit = listOfNotNull(debitRe.find(lower)?.range?.first, outgoingRe.find(lower)?.range?.first).minOrNull()
        val income = when {
            credit == null && debit == null -> return null
            credit == null -> false
            debit == null -> true
            else -> credit < debit
        }
        // "to you" / "from you" names the user, not the other party, and a candidate that swallowed an
        // amount ("to Mobile/ID RM 10") isn't a name either.
        val payee = (senderRe.find(text) ?: payeeRe.find(text))?.groupValues?.get(1)
            ?.replace(phoneTailRe, "")?.trim()?.take(100)
            ?.takeIf { it.isNotEmpty() && !it.equals("you", ignoreCase = true) && amountRe.find(it) == null }
        return Parsed(if (income) amount else -amount, payee, currencyOf(m.groupValues[1]))
    }

    /**
     * Whether [payee] is who the money went to: a card merchant ("spent RM 5.00 at SAMPLE SHOP") or
     * someone sent money ("sent RM10.00 to Sample Person"). Those go in the Wallet note; only someone
     * who sent the money ("from Sample Person") becomes a Wallet payee.
     */
    fun paidTo(text: String, payee: String): Boolean =
        Regex("""\b(?:at|to)\s+""" + Regex.escape(payee), RegexOption.IGNORE_CASE).containsMatchIn(text)

    class WalletText(val payee: String?, val note: String?)

    /**
     * The payee and note a record gets in Wallet, from the stored alert [text] and [payee]. Each name
     * is in one place only: who was paid in the note (unless notes are off, then it stays the payee),
     * who sent the money as the payee.
     */
    fun walletText(text: String, payee: String?, accountCurrency: String, includeNote: Boolean): WalletText {
        val paid = payee != null && paidTo(text, payee)
        return WalletText(
            payee?.takeUnless { paid && includeNote },
            if (includeNote) note(text, payee.takeIf { paid }, accountCurrency) else null,
        )
    }

    /**
     * [payee] as the alert wrote it, with the phone number and country a card merchant may carry
     * ("SAMPLE SHOP 03-12345678 MY"). The payee itself is kept short so the same shop matches for
     * categories; the note shows it in full.
     */
    private fun fullName(text: String, payee: String): String =
        Regex(
            """\b(?:at|to)\s+(""" + Regex.escape(payee) + """[^.,\n]*?)(?=\s+(?:on|with|using|via|for|ending|card)\b|[.,\n]|$)""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1)?.trim() ?: payee

    private val refRe =Regex("""\bref(?:erence)?\b\s*(?:no\.?)?\s*:?\s*([A-Z0-9][A-Z0-9-]{2,})""", RegexOption.IGNORE_CASE)

    /**
     * The Wallet note: just the payment details (who was paid, reference) instead of the whole alert,
     * plus the alert's own amount when it isn't in [accountCurrency], since the record then holds the
     * converted amount. The card or account number is left out; the Wallet account already says which
     * one. With none of those, no note: the amount, date and account are already on the record.
     */
    fun note(text: String, payee: String?, accountCurrency: String): String? {
        val m = amountRe.find(text)
        val currency = m?.let { currencyOf(it.groupValues[1]) }
        val parts = listOfNotNull(
            payee?.trim()?.takeIf { it.isNotEmpty() }?.let { fullName(text, it) },
            refRe.find(text)?.let { "Ref ${it.groupValues[1]}" },
            if (m != null && currency != null && accountCurrency.isNotEmpty() && currency != accountCurrency) {
                "$currency ${m.groupValues[2]}"
            } else {
                null
            },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.take(255)
    }
}
