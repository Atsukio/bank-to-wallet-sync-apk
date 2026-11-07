package app.banksync

import kotlin.math.abs

/**
 * Sends alerts from [pkg] to one Wallet account. A blank [match] means every alert from the app;
 * otherwise only alerts containing that text (e.g. a card's last four digits) use this rule, so one
 * app can feed several Wallet accounts.
 */
data class Rule(
    val pkg: String,
    val label: String,
    val match: String,
    val accountId: String,
    val accountName: String,
    val accountCurrency: String = "",
    /** The app's default: its alerts that name no account of the user's go here. One per app at most. */
    val isDefault: Boolean = false,
)

object RuleMatcher {
    /**
     * A specific match wins. Then the app's default, but only for an alert that names none of the
     * user's accounts: one naming an ending no rule has is someone else's account or an unlinked one,
     * so it is held rather than guessed. Then the app-wide rule, and an app with a single rule always
     * uses it: alerts such as transfers often leave out the card number the match looks for. Null when
     * nothing applies.
     */
    fun pick(rules: List<Rule>, text: String): Rule? =
        rules.firstOrNull { it.match.isNotEmpty() && contains(text, it.match) }
            ?: rules.firstOrNull { it.isDefault }?.takeIf { endings(text).isEmpty() }
            ?: rules.firstOrNull { it.match.isEmpty() }
            ?: rules.singleOrNull()

    private val ending = Regex("""\bending\s*(?:no\.?|number)?\s*:?\s*[*xX•.]*\s*(\d{3,})(?!\d)""", RegexOption.IGNORE_CASE)
    private val yourMasked = Regex(
        """\byour\b[^.\d]{0,40}?\b(?:account|card|a/c)\b\s*(?:no\.?|number)?\s*:?\s*[*xX•]{2,}\s*(\d{3,})(?!\d)""",
        RegexOption.IGNORE_CASE
    )
    private val phone = Regex("""mobile|phone|\btel\b""", RegexOption.IGNORE_CASE)

    /**
     * Account or card endings the alert says are the user's own ("account ending 1234", "your card
     * XXXX1234"). A masked mobile number ("Mobile Number: ****1234") or a payee's number isn't one.
     */
    fun endings(text: String): List<String> =
        (ending.findAll(text) + yourMasked.findAll(text))
            .filterNot { phone.containsMatchIn(text.substring(maxOf(0, it.range.first - 25), it.range.first)) }
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /**
     * Case-insensitive, and the match can't be part of a longer number or word: ending "1234" must not
     * pick up "RM 12345.00", and "mae" must not pick up "maeve". A digits-only match only refuses
     * neighbouring digits, so masked numbers such as "XXXX1234" still match "1234".
     */
    fun contains(text: String, match: String): Boolean {
        val m = match.trim()
        if (m.isEmpty()) return false
        val edge = if (m.all { it.isDigit() }) """\p{N}""" else """\p{L}\p{N}"""
        val start = if (m.first().isLetterOrDigit()) "(?<![$edge])" else ""
        val end = if (m.last().isLetterOrDigit()) "(?![$edge])" else ""
        return Regex(start + Regex.escape(m) + end, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}

/**
 * Touch 'n Go's GO+ pocket is its own Wallet account. A rule whose account is named like one is not for
 * the app's ordinary alerts: it is where Cash ins go, as a transfer from the app's main account.
 */
object GoPlus {
    private val name = Regex("""\bgo\s?\+""", RegexOption.IGNORE_CASE)

    fun isGoPlus(accountName: String): Boolean = name.containsMatchIn(accountName)

    /** Only Touch 'n Go's own rules: another bank's account that happens to be named like one is an ordinary rule. */
    fun isGoPlus(rule: Rule): Boolean = isTng(rule.label) && isGoPlus(rule.accountName)

    /** Touch 'n Go's app, by the label it is linked under. */
    fun isTng(appLabel: String): Boolean = Regex("""touch|\btng\b""", RegexOption.IGNORE_CASE).containsMatchIn(appLabel)
}

/**
 * ShopeePay's SPayLater (buy now, pay later) is its own Wallet account, linked under the Shopee app. Its alerts go
 * only to that account and never fall back to the app's ordinary one; with none linked they are held and asked about.
 */
object SpayLater {
    private val name = Regex("""\bs?pay\s?later\b""", RegexOption.IGNORE_CASE)
    private val payment = Regex("""\bpayment\b""", RegexOption.IGNORE_CASE)
    private const val HOUR = 3_600_000L

    fun isAccount(accountName: String): Boolean = name.containsMatchIn(accountName)

    /** Only the Shopee app's rules: another bank's account that happens to be named like one is an ordinary rule. */
    fun isRule(rule: Rule): Boolean = MoneyPlus.isShopee(rule.label) && isAccount(rule.accountName)

    /** An SPayLater alert, by its wording ("Your SPayLater payment is successful"). */
    fun isAlert(text: String): Boolean = name.containsMatchIn(text)

    /** The line a notification shows when the alert names no currency, so the amount is in the account's. */
    fun currencyNote(accountCurrency: String): String =
        "Amount in ${accountCurrency.ifEmpty { "the account's currency" }}: the alert names no currency.\n"

    /**
     * A ShopeePay "Payment" activity repeating an SPayLater alert already kept: it names the same amount, posted
     * within an hour of it. [alerts] are the (posted time, text) of SPayLater alerts already kept.
     */
    fun isPaymentCopy(text: String, posted: Long, alerts: List<Pair<Long, String>>): Boolean {
        if (isAlert(text) || !payment.containsMatchIn(text)) return false
        val amounts = Parser.decimals(text)
        if (amounts.isEmpty()) return false
        return alerts.any { (t, earlier) ->
            val paid = Parser.spayLater(earlier)?.amount ?: return@any false
            abs(t - posted) <= HOUR && amounts.any { abs(it - abs(paid)) < 0.005 }
        }
    }
}

object IgnoreWords {
    /** True when [text] contains one of the comma-separated [words] as a whole word ("otp" but not "hotpot"). */
    fun matches(words: String, text: String): Boolean =
        words.split(',').any { RuleMatcher.contains(text, it) }
}

/**
 * ShopeePay's Money+ (its savings pocket) posts bonus and earnings notifications in the Shopee app.
 * Someone may want those cleared from the shade while keeping the app's other notifications, ads included.
 */
object MoneyPlus {
    private val name = Regex("""\bmoney\s?\+""", RegexOption.IGNORE_CASE)
    private val shopee = Regex("""shopee""", RegexOption.IGNORE_CASE)

    /** A Money+ notification, by its wording ("Money+ Bonus"). */
    fun matches(text: String): Boolean = name.containsMatchIn(text)

    /** The Shopee app, by the label it is linked under. */
    fun isShopee(appLabel: String): Boolean = shopee.containsMatchIn(appLabel)
}

object CatchUp {
    /**
     * Oldest notification a catch-up scan will accept. Never older than the history we keep (or the
     * duplicate check would forget captured alerts and re-add them), and never older than [since],
     * so alerts from before the app was set up aren't imported.
     */
    fun cutoff(now: Long, since: Long, hours: Int, keepDays: Int): Long =
        maxOf(since, now - minOf(hours, keepDays * 24) * 3_600_000L)
}

/** "When the alert contains [contains], file it under this Wallet category." */
data class CategoryRule(val contains: String, val categoryId: String, val categoryName: String)

object CategoryMatcher {
    fun pick(rules: List<CategoryRule>, text: String): CategoryRule? {
        val lower = text.lowercase()
        // Plain "contains" on purpose: merchant names run together ("GRAB*RIDE", "GRABFOOD").
        return rules.firstOrNull { it.contains.isNotBlank() && it.contains.trim().lowercase() in lower }
    }
}
