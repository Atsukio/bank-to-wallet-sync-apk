package app.banksync

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What categorising needs from Wallet (its rules, categories and categorised past records), kept in a
 * file so a new alert is categorised without going online. Refreshed only when an upload is already
 * using the network, or when the user asks, and never more often than the ages below.
 */
object WalletCache {
    private const val FILE = "wallet_cache.json"
    private const val RULES_MAX_AGE = 60 * 60_000L
    private const val HISTORY_MAX_AGE = 12 * 60 * 60_000L

    class Data(
        val rules: List<WalletRule> = emptyList(),
        val history: List<PastRecord> = emptyList(),
        val categories: List<WalletApi.Category> = emptyList(),
        val rulesAt: Long = 0,
        val historyAt: Long = 0,
    )

    @Volatile
    private var loaded: Data? = null

    fun get(context: Context): Data = loaded ?: synchronized(this) {
        loaded ?: read(context).also { loaded = it }
    }

    /**
     * Fetches whatever is older than its limit (everything when [force]). A failure leaves the old copy
     * in place: categorising is a nice-to-have and must never hold up sending. Returns false on failure.
     */
    fun refresh(context: Context, token: String, force: Boolean = false): Boolean {
        if (token.isEmpty()) return false
        val now = System.currentTimeMillis()
        var d = get(context)
        return try {
            if (force || now - d.rulesAt > RULES_MAX_AGE) {
                d = Data(WalletApi.recordRules(token), d.history, WalletApi.categories(token), now, d.historyAt)
            }
            if (force || now - d.historyAt > HISTORY_MAX_AGE) {
                d = Data(d.rules, WalletApi.categorisedHistory(token), d.categories, d.rulesAt, now)
            }
            save(context, d)
            true
        } catch (e: Exception) {
            Log.w("WalletCache", "Refresh failed: ${e.message}")
            if (d !== loaded) save(context, d) // keep whatever part did arrive
            false
        }
    }

    /** The best category for an alert from what's on the phone; see [Categorizer.pick]. */
    fun pick(context: Context, text: String, payee: String?, amount: Double, accountId: String): CategoryPick? {
        val s = Settings(context)
        val d = get(context)
        return Categorizer.pick(text, payee, amount, accountId, s.categoryMemory(), s.categoryRules(), d.rules, d.history)
    }

    /** Categories for the pickers, fetched once if this phone has never loaded them. */
    fun categories(context: Context, token: String): List<WalletApi.Category> {
        val have = get(context).categories
        if (have.isNotEmpty()) return have
        val fresh = WalletApi.categories(token)
        val d = get(context)
        save(context, Data(d.rules, d.history, fresh, d.rulesAt, d.historyAt))
        return fresh
    }

    private fun save(context: Context, d: Data) {
        loaded = d
        val o = JSONObject()
            .put("rulesAt", d.rulesAt)
            .put("historyAt", d.historyAt)
            .put("categories", JSONArray().apply {
                d.categories.forEach { put(JSONObject().put("id", it.id).put("label", it.label)) }
            })
            .put("rules", JSONArray().apply {
                d.rules.forEach {
                    put(
                        JSONObject().put("keywords", JSONArray(it.keywords)).put("categoryId", it.categoryId)
                            .put("categoryName", it.categoryName).put("labelIds", JSONArray(it.labelIds))
                            .put("accountId", it.accountId)
                    )
                }
            })
            .put("history", JSONArray().apply {
                d.history.forEach {
                    put(
                        JSONObject().put("texts", JSONArray(it.texts)).put("categoryId", it.categoryId)
                            .put("categoryName", it.categoryName).put("income", it.income)
                    )
                }
            })
        runCatching { File(context.filesDir, FILE).writeText(o.toString()) }
    }

    private fun read(context: Context): Data = try {
        val o = JSONObject(File(context.filesDir, FILE).readText())
        fun JSONArray.strings() = (0 until length()).map { getString(it) }
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        Data(
            rules = o.getJSONArray("rules").objects().map {
                WalletRule(
                    it.getJSONArray("keywords").strings(), it.getString("categoryId"), it.optString("categoryName"),
                    it.getJSONArray("labelIds").strings(), it.optString("accountId"),
                )
            },
            history = o.getJSONArray("history").objects().map {
                PastRecord(it.getJSONArray("texts").strings(), it.getString("categoryId"), it.optString("categoryName"), it.getBoolean("income"))
            },
            categories = o.getJSONArray("categories").objects().map { WalletApi.Category(it.getString("id"), it.getString("label")) },
            rulesAt = o.optLong("rulesAt"),
            historyAt = o.optLong("historyAt"),
        )
    } catch (e: Exception) {
        Data()
    }
}
