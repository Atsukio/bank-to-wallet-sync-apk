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

    /**
     * Bumped when what's kept changes; an older file's categories and history are fetched again.
     * 2: labels fixed ("false · …"). 3: transfers and the Transfer category left out.
     */
    private const val CATEGORIES_VERSION = 3

    /** How often planned payments are fetched; while an alert is held for one, as often as [PLANNED_HELD_AGE]. */
    private const val PLANNED_MAX_AGE = 60 * 60_000L
    private const val PLANNED_HELD_AGE = 2 * 60_000L

    data class Data(
        val rules: List<WalletRule> = emptyList(),
        val history: List<PastRecord> = emptyList(),
        val categories: List<WalletApi.Category> = emptyList(),
        val rulesAt: Long = 0,
        val historyAt: Long = 0,
        /** Planned payments still to be paid, for [plannedMatch]. */
        val planned: List<Planned.Due> = emptyList(),
        val plannedAt: Long = 0,
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
                d = d.copy(rules = WalletApi.recordRules(token), categories = WalletApi.categories(token), rulesAt = now)
            }
            if (force || now - d.historyAt > HISTORY_MAX_AGE) {
                d = d.copy(history = WalletApi.categorisedHistory(token), historyAt = now)
            }
            save(context, d.withPlannedOf(get(context)))
            true
        } catch (e: Exception) {
            Log.w("WalletCache", "Refresh failed: ${e.message}")
            if (d !== loaded) save(context, d.withPlannedOf(get(context))) // keep whatever part did arrive
            false
        }
    }

    /** Planned payments are fetched on their own, maybe while the rest was loading: the newest copy stays. */
    private fun Data.withPlannedOf(latest: Data) = copy(planned = latest.planned, plannedAt = latest.plannedAt)

    /** The best category for an alert from what's on the phone; see [Categorizer.pick]. */
    fun pick(context: Context, text: String, payee: String?, amount: Double, accountId: String): CategoryPick? {
        val s = Settings(context)
        val d = get(context)
        return Categorizer.pick(
            text, payee, amount, accountId, s.categoryMemory(), s.categoryRules(), d.rules, d.history, s.refusedCategories()
        )
    }

    /** Categories for the pickers, fetched once if this phone has never loaded them; never one Wallet refused. */
    fun categories(context: Context, token: String): List<WalletApi.Category> {
        val refused = Settings(context).refusedCategories()
        val have = get(context).categories
        if (have.isNotEmpty()) return have.filter { it.id !in refused }
        val fresh = WalletApi.categories(token)
        save(context, get(context).copy(categories = fresh))
        return fresh.filter { it.id !in refused }
    }

    /** The planned payment a new alert is, if any, from what's on the phone; see [Planned.match]. */
    fun plannedMatch(context: Context, amount: Double, account: String, currency: String, timeMs: Long, payee: String?): Planned.Due? {
        if (!Settings(context).plannedPayments) return null
        val dues = get(context).planned.ifEmpty { return null }
        return Planned.match(dues, amount, account, currency, timeMs, java.time.ZoneId.systemDefault(), Store.get(context).plannedKeys(), payee)
    }

    /**
     * Fetches planned payments when the copy is older than its limit (always when [force]), then
     * closes held alerts Wallet now has as paid. Only while the setting is on or alerts are held. Returns
     * false on failure, which leaves the old copy: holding is a nice-to-have, like categories.
     */
    @Synchronized
    fun refreshPlanned(context: Context, token: String, force: Boolean = false): Boolean {
        if (token.isEmpty()) return false
        val held = Store.get(context).planned().isNotEmpty()
        if (!held && !Settings(context).plannedPayments) return true
        val age = System.currentTimeMillis() - get(context).plannedAt
        if (!force && age < if (held) PLANNED_HELD_AGE else PLANNED_MAX_AGE) return true
        return try {
            usePlanned(context, token, WalletApi.standingOrders(token), WalletApi.standingOrderItems(token, plannedSince()))
            true
        } catch (e: Exception) {
            Log.w("WalletCache", "Planned payments refresh failed: ${e.message}")
            false
        }
    }

    /** How far back occurrences are fetched: long enough to see one a held alert is waiting on. */
    fun plannedSince(): java.time.Instant = java.time.Instant.now().minusSeconds(60 * 86_400L)

    /**
     * Keeps what's still to be paid and closes held alerts whose planned payment Wallet has as paid: by
     * the occurrence's linked record, or, once Wallet counts it paid or the order has moved on, by a record
     * of that amount in the account (one request per such alert). A closed row takes on that record's
     * payee, note and category. Returns how many closed.
     */
    fun usePlanned(context: Context, token: String, orders: List<JSONObject>, items: List<JSONObject>): Int {
        val its = items.mapNotNull { Planned.item(it) }
        val read = orders.mapNotNull { Planned.order(it) }
        val data = get(context)
        save(context, data.copy(planned = Planned.upcoming(read, its), plannedAt = System.currentTimeMillis()))
        val names = data.categories.associate { it.id to it.label }
        val store = Store.get(context)
        // Records this app sent are other transactions, never the planned payment's.
        val taken = store.walletIds().toMutableSet()
        var closed = 0
        for (r in store.planned()) {
            val id = r.planId ?: continue
            val due = r.planDue ?: continue
            val paid = Planned.paidRecord(its, id, due)?.let { Planned.paid(it, read.firstOrNull { o -> o.id == id }, names) }
                ?: if (r.amount != null && (Planned.countedPaid(read, id, due) || Planned.movedOn(read, id, due))) {
                    val since = java.time.Instant.ofEpochMilli(due - (Planned.WINDOW_DAYS + 1) * 86_400_000L)
                    Planned.paidBy(WalletApi.recordsSince(token, r.account, since), r.amount, due, taken)?.let { Planned.paid(it, names) }
                } else null
            if (paid != null && store.paidInWallet(r.id, paid, r.planName)) {
                taken += paid.recordId
                Notifier.cancelReview(context, r.id)
                closed++
            }
        }
        if (closed > 0) Uploader.changed()
        return closed
    }

    private fun save(context: Context, d: Data) {
        loaded = d
        val o = JSONObject()
            .put("rulesAt", d.rulesAt)
            .put("historyAt", d.historyAt)
            .put("categoriesVersion", CATEGORIES_VERSION)
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
            .put("plannedAt", d.plannedAt)
            .put("planned", JSONArray().apply {
                d.planned.forEach {
                    put(
                        JSONObject().put("orderId", it.orderId).put("name", it.name).put("account", it.account)
                            .put("amount", it.amount).put("currency", it.currency).put("dueMs", it.dueMs).put("payee", it.payee)
                    )
                }
            })
        runCatching { File(context.filesDir, FILE).writeText(o.toString()) }
    }

    private fun read(context: Context): Data = try {
        val o = JSONObject(File(context.filesDir, FILE).readText())
        val old = o.optInt("categoriesVersion") < CATEGORIES_VERSION
        fun JSONArray.strings() = (0 until length()).map { getString(it) }
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        Data(
            rules = o.getJSONArray("rules").objects().map {
                WalletRule(
                    it.getJSONArray("keywords").strings(), it.getString("categoryId"), it.optString("categoryName"),
                    it.getJSONArray("labelIds").strings(), it.optString("accountId"),
                )
            },
            // An older file may hold "false · …" labels, transfers and the Transfer category; leaving
            // them out (and the history's age at 0) forces a fresh load.
            history = if (old) emptyList() else o.getJSONArray("history").objects().map {
                PastRecord(it.getJSONArray("texts").strings(), it.getString("categoryId"), it.optString("categoryName"), it.getBoolean("income"))
            },
            categories = if (old) emptyList() else
                o.getJSONArray("categories").objects().map { WalletApi.Category(it.getString("id"), it.getString("label")) },
            rulesAt = if (old) 0 else o.optLong("rulesAt"),
            historyAt = if (old) 0 else o.optLong("historyAt"),
            planned = (o.optJSONArray("planned") ?: JSONArray()).objects().map {
                Planned.Due(
                    it.getString("orderId"), it.optString("name"), it.getString("account"),
                    it.getDouble("amount"), it.optString("currency"), it.getLong("dueMs"), it.optString("payee"),
                )
            },
            plannedAt = o.optLong("plannedAt"),
        )
    } catch (e: Exception) {
        Data()
    }
}
