package app.banksync

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Edit in Wallet" for a record already sent: what the user changed, as the fields of Wallet's record
 * PATCH (`counterParty`, `categoryId`, `note`, `amount`). What the user sets replaces what Wallet has.
 */
object SentEdit {
    /** What the record dialog shows and the user can change. A blank payee or note means none. */
    class Fields(val amount: Double, val payee: String?, val categoryId: String?, val note: String?)

    /** Whether [r] can be changed in Wallet from here: sent, with Wallet's id for it, and not a transfer (Wallet files those itself). */
    fun editable(r: Rec) = r.status == Store.SYNCED && r.walletId != null && r.amount != null && r.pairId == null && r.transferTo == null

    /** The fields that differ, by Wallet's name for them; a null value clears that field in Wallet. */
    fun changes(before: Fields, after: Fields): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        fun text(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        if (kotlin.math.abs(after.amount - before.amount) >= 0.005) out["amount"] = after.amount
        if (text(after.payee) != text(before.payee)) out["counterParty"] = text(after.payee)
        if (after.categoryId != before.categoryId) out["categoryId"] = after.categoryId
        if (text(after.note) != text(before.note)) out["note"] = text(after.note)
        return out
    }

    /** One PATCH item for Wallet's record [walletId] from queued [changes], where JSON null clears a field. */
    fun item(walletId: String, changes: JSONObject): JSONObject {
        val o = JSONObject().put("id", walletId)
        val clear = JSONArray()
        for (k in changes.keys()) {
            val v = changes.get(k)
            when {
                v == JSONObject.NULL -> clear.put(k)
                k == "amount" -> o.put("amount", JSONObject().put("value", v))
                else -> o.put(k, v)
            }
        }
        if (clear.length() > 0) o.put("\$clear", clear)
        return o
    }
}
