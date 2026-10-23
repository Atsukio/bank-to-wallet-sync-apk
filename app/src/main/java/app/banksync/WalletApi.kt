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

    /** Custom name if the user renamed it, plus the group, e.g. "Groceries · Food & Drinks". */
    fun categories(token: String): List<Category> {
        val r = call("GET", "/v1/api/categories?limit=200&archived=false", token, null)
        if (r.code == 401 || r.code == 403) throw IOException("Wallet rejected the token (HTTP ${r.code})")
        if (r.code != 200) throw HttpStatusException(r.code)
        val arr = JSONObject(r.body).optJSONArray("categories") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val name = it.optString("customName").ifEmpty { it.optString("name") }
            val group = it.optJSONObject("group")?.optString("name").orEmpty()
            Category(it.optString("id"), if (group.isEmpty() || group == name) name else "$name · $group")
        }.sortedBy { it.label.lowercase() }
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
