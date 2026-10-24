package app.banksync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime

object WalletApi {
    private const val BASE = "https://rest.budgetbakers.com/wallet"

    class Resp(val code: Int, val body: String, val retryAfterMs: Long?)

    /** The request body was sent but no answer came back, so Wallet may or may not have the records. */
    class UncertainException(cause: IOException) : IOException(cause)

    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    class Account(val id: String, val name: String, val currency: String, val number: String = "") {
        val label get() = if (currency.isEmpty()) name else "$name ($currency)"

        /** Last 4 digits of the account number saved in Wallet, or failing that of a number in the name. */
        val ending: String
            get() {
                val digits = number.filter { it.isDigit() }
                if (digits.length >= 4) return digits.takeLast(4)
                return Regex("""(\d{4})\D*$""").find(name)?.groupValues?.get(1).orEmpty()
            }
    }

    class Category(val id: String, val label: String)

    /** Creates up to 50 records. `returnData=false` keeps the response small. */
    fun postRecords(token: String, json: String): Resp =
        call("POST", "/v1/api/records?returnData=false", token, json)

    /** The user's active Wallet accounts, so rules are built from real accounts instead of typed IDs. */
    fun accounts(token: String): List<Account> {
        val r = call("GET", "/v1/api/accounts?limit=200&archived=false", token, null)
        if (r.code == 401 || r.code == 403) throw IOException("Wallet rejected the token (HTTP ${r.code})")
        if (r.code != 200) throw HttpStatusException(r.code)
        val arr = JSONObject(r.body).optJSONArray("accounts") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map {
                Account(it.optString("id"), it.optString("name"), it.optString("currencyCode"), it.optString("bankAccountNumber"))
            }
    }

    /**
     * Active, enabled categories with their group (or, for a custom one, its parent), e.g.
     * "Groceries · Food & Drinks". `name` is already the display name; `customName` is only a flag.
     */
    fun categories(token: String): List<Category> {
        val out = ArrayList<Category>()
        var offset = 0
        repeat(5) {
            val r = call("GET", "/v1/api/categories?limit=200&offset=$offset&archived=false", token, null)
            if (r.code == 401 || r.code == 403) throw IOException("Wallet rejected the token (HTTP ${r.code})")
            if (r.code != 200) throw HttpStatusException(r.code)
            val body = JSONObject(r.body)
            val arr = body.optJSONArray("categories") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                if (!c.optBoolean("enabled", true) || c.optBoolean("archived")) continue
                val name = c.optString("name")
                if (name.isEmpty()) continue
                val group = c.optJSONObject("group")?.optString("name").orEmpty().ifEmpty { c.optString("parentName") }
                out += Category(c.optString("id"), if (group.isEmpty() || group == name) name else "$name · $group")
            }
            if (body.isNull("nextOffset") || !body.has("nextOffset")) return out.sortedBy { it.label.lowercase() }
            offset = body.optInt("nextOffset")
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** The user's Wallet categorisation rules. Transfer rules (with a target account) are left out. */
    fun recordRules(token: String): List<WalletRule> {
        val arr = getList(token, "/v1/api/record-rules?limit=200", "recordRules")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("toAccountId").isEmpty() && it.optJSONObject("category")?.optString("id").orEmpty().isNotEmpty() }
            .map { r ->
                val kws = r.optJSONArray("keywords")
                val labels = r.optJSONArray("labels")
                WalletRule(
                    keywords = (0 until (kws?.length() ?: 0)).map { kws!!.optString(it) }.filter { it.isNotBlank() },
                    categoryId = r.getJSONObject("category").optString("id"),
                    categoryName = r.getJSONObject("category").optString("name"),
                    labelIds = (0 until (labels?.length() ?: 0)).map { labels!!.getJSONObject(it) }
                        .filter { !it.optBoolean("archived") && it.optString("id").isNotEmpty() }
                        .map { it.optString("id") },
                    accountId = r.optString("fromAccountId"),
                )
            }
    }

    /**
     * Categorised records from the last [days] days, newest first, up to 1000, to learn from.
     * "Unknown" and voided records teach nothing, so they're skipped.
     */
    fun categorisedHistory(token: String, days: Long = 180): List<PastRecord> {
        val since = Instant.now().minusSeconds(days * 86_400).toString()
        val out = ArrayList<PastRecord>()
        var offset = 0
        repeat(5) {
            val r = call("GET", "/v1/api/records?recordDate=gte.$since&sortBy=-recordDate&limit=200&offset=$offset", token, null)
            if (r.code != 200) throw HttpStatusException(r.code)
            val body = JSONObject(r.body)
            val arr = body.optJSONArray("records") ?: return out
            for (i in 0 until arr.length()) {
                val rec = arr.getJSONObject(i)
                val cat = rec.optJSONObject("category") ?: continue
                val catId = cat.optString("id")
                if (catId.isEmpty() || cat.optString("name").contains("unknown", ignoreCase = true)) continue
                if (rec.optString("recordState").equals("void", ignoreCase = true)) continue
                val texts = listOf(rec.optString("counterParty"), rec.optString("note")).filter { it.isNotBlank() }
                if (texts.isEmpty()) continue
                val income = rec.optString("recordType").equals("income", ignoreCase = true)
                out += PastRecord(texts.map { it.take(120) }, catId, cat.optString("name"), income)
            }
            if (body.isNull("nextOffset") || !body.has("nextOffset")) return out
            offset = body.optInt("nextOffset")
        }
        return out
    }

    private fun getList(token: String, path: String, key: String): org.json.JSONArray {
        val r = call("GET", path, token, null)
        if (r.code == 401 || r.code == 403) throw IOException("Wallet rejected the token (HTTP ${r.code})")
        if (r.code != 200) throw HttpStatusException(r.code)
        return JSONObject(r.body).optJSONArray(key) ?: org.json.JSONArray()
    }

    /**
     * Does Wallet already hold this record? Used before re-sending a record whose earlier upload
     * got no answer, because Wallet has no duplicate protection of its own. Matches on account,
     * amount and a two-minute window around the transaction time.
     */
    fun recordExists(token: String, rec: Rec, windowSec: Long = 120): Boolean {
        val amount = rec.amount ?: return false
        val when_ = Instant.parse(rec.date)
        val from = when_.minusSeconds(windowSec)
        val r = call(
            "GET",
            "/v1/api/records?accountId=${rec.account}&recordDate=gte.$from&sortBy=%2BrecordDate&limit=30",
            token, null
        )
        if (r.code != 200) throw HttpStatusException(r.code)
        val arr = JSONObject(r.body).optJSONArray("records") ?: return false
        return (0 until arr.length()).map { arr.getJSONObject(it) }.any {
            val value = it.optJSONObject("amount")?.optDouble("value", Double.NaN) ?: Double.NaN
            val date = runCatching { OffsetDateTime.parse(it.optString("recordDate")).toInstant() }.getOrNull()
            kotlin.math.abs(value - amount) < 0.005 && date != null &&
                kotlin.math.abs(date.epochSecond - when_.epochSecond) <= windowSec
        }
    }

    private fun call(method: String, path: String, token: String, body: String?): Resp {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        var sent = false
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                sent = true
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val retryAfter = c.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
            return Resp(code, text, retryAfter)
        } catch (e: IOException) {
            throw if (sent) UncertainException(e) else e
        } finally {
            c.disconnect()
        }
    }
}
