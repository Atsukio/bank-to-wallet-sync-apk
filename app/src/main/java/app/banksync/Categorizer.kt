package app.banksync

/** A Wallet categorisation rule: keywords in the alert → category and labels, optionally for one account only. */
class WalletRule(
    val keywords: List<String>,
    val categoryId: String,
    val categoryName: String,
    val labelIds: List<String>,
    val accountId: String = "",
)

/** One categorised Wallet record, reduced to what's needed to learn from it. */
class PastRecord(val texts: List<String>, val categoryId: String, val categoryName: String, val income: Boolean) {
    val keys: Set<String> = texts.mapNotNull { Merchant.memoryKey(it).ifEmpty { null } }.toSet()
}

/** Where a record's category came from; shown in the app and decides what may be re-picked later. */
object CategorySource {
    const val MANUAL = "manual"
    const val REMEMBERED = "remembered"
    const val APP_RULE = "rule"
    const val WALLET_RULE = "wallet"
    const val HISTORY = "history"
}

class CategoryPick(val id: String, val name: String, val labelIds: List<String>, val source: String)

/** Merchant-name helpers, the same as the browser extension's so both learn alike. */
object Merchant {
    private fun wordsKey(s: String) = s.lowercase().replace(Regex("[^a-z\\s]"), " ")
        .split(Regex("\\s+")).filter { it.length > 2 }.take(2).joinToString(" ")

    private fun specific(k: String) = ' ' in k || k.length >= 5

    /**
     * The merchant part of a description: its first two words with digits, codes and punctuation
     * dropped, so "GRAB*RIDE 8812" and "GRAB*RIDE 1907" share "grab ride".
     */
    fun merchantKey(s: String): String {
        val parts = s.split(" · ")
        for (part in parts.drop(1).reversed()) wordsKey(part).let { if (specific(it)) return it }
        return wordsKey(parts.first())
    }

    /** A key specific enough to learn from or remember (two words, or one long word), else "". */
    fun memoryKey(s: String): String = merchantKey(s).let { if (specific(it)) it else "" }

    /**
     * Whether a Wallet text describes the same thing as [desc]: ignores case and punctuation, and
     * accepts a cut-off start of either, or a note containing the whole description (8+ characters,
     * so a short name can't match everything).
     */
    fun sameDescription(walletText: String, desc: String): Boolean {
        fun n(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        val x = n(walletText)
        val y = n(desc)
        if (x.isEmpty() || y.isEmpty()) return false
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short == long || (short.length >= 8 && long.startsWith(short)) || (y.length >= 8 && y in x)
    }
}

/**
 * Picks a category the way the browser extension does, best evidence first:
 * 1. a category the user chose by hand for this merchant before;
 * 2. this app's own "alert contains" rules;
 * 3. the user's Wallet rules (longest matching keyword wins);
 * 4. the most common category of past Wallet records for the same payee, else the same merchant,
 *    of the same type (income or expense).
 * Wallet doesn't apply its own rules to records added through the API, hence all this.
 */
object Categorizer {
    fun pick(
        text: String,
        payee: String?,
        amount: Double,
        accountId: String,
        memory: Map<String, Pair<String, String>>,
        appRules: List<CategoryRule>,
        walletRules: List<WalletRule>,
        history: List<PastRecord>,
    ): CategoryPick? {
        val key = payee?.let { Merchant.memoryKey(it) }.orEmpty()
        if (key.isNotEmpty()) {
            memory[key]?.let { (id, name) -> return CategoryPick(id, name, emptyList(), CategorySource.REMEMBERED) }
        }
        CategoryMatcher.pick(appRules, text)?.let {
            return CategoryPick(it.categoryId, it.categoryName, emptyList(), CategorySource.APP_RULE)
        }
        matchWalletRule(text, walletRules, accountId)?.let {
            return CategoryPick(it.categoryId, it.categoryName, it.labelIds, CategorySource.WALLET_RULE)
        }
        return payee?.let { guess(it, amount > 0, history) }
    }

    fun matchWalletRule(text: String, rules: List<WalletRule>, accountId: String): WalletRule? {
        val lower = text.lowercase()
        var best: WalletRule? = null
        var bestLen = 0
        for (rule in rules) {
            if (rule.accountId.isNotEmpty() && rule.accountId != accountId) continue
            for (kw in rule.keywords) {
                val k = kw.trim().lowercase()
                if (k.length > bestLen && k in lower) {
                    best = rule
                    bestLen = k.length
                }
            }
        }
        return best
    }

    /** The most common category among matching past records; ties go to the most recent (history is newest first). */
    fun guess(payee: String, income: Boolean, history: List<PastRecord>): CategoryPick? {
        val pool = history.filter { it.income == income }
        var found = pool.filter { h -> h.texts.any { Merchant.sameDescription(it, payee) } }
        val key = Merchant.memoryKey(payee)
        if (found.isEmpty() && key.isNotEmpty()) found = pool.filter { key in it.keys }
        if (found.isEmpty()) return null
        val counts = LinkedHashMap<String, Int>()
        found.forEach { counts[it.categoryId] = (counts[it.categoryId] ?: 0) + 1 }
        val bestId = counts.entries.fold(counts.entries.first()) { a, b -> if (b.value > a.value) b else a }.key
        val best = found.first { it.categoryId == bestId }
        return CategoryPick(best.categoryId, best.categoryName, emptyList(), CategorySource.HISTORY)
    }
}
