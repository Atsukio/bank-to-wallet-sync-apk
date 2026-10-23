package app.banksync

/**
 * Sends alerts from [pkg] to one Wallet account. A blank [match] means every alert from the app;
 * otherwise only alerts containing that text (e.g. a card's last four digits) use this rule, so one
 * app can feed several Wallet accounts.
 *
 * A transfer rule ([transferAccountId] set) records a transfer between two of the user's own
 * accounts instead of an income or expense: [direction] +1 means money arrives in [accountId] from
 * the other account, -1 means it leaves [accountId] to the other account.
 */
data class Rule(
    val pkg: String,
    val label: String,
    val match: String,
    val accountId: String,
    val accountName: String,
    val accountCurrency: String = "",
    val direction: Int = 0,
    val transferAccountId: String = "",
    val transferAccountName: String = "",
)

object RuleMatcher {
    /** A specific match wins over the app-wide rule; null when nothing applies. */
    fun pick(rules: List<Rule>, text: String): Rule? {
        val lower = text.lowercase()
        return rules.firstOrNull { it.match.isNotEmpty() && it.match.lowercase() in lower }
            ?: rules.firstOrNull { it.match.isEmpty() }
    }
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
        return rules.firstOrNull { it.contains.isNotBlank() && it.contains.lowercase() in lower }
    }
}
