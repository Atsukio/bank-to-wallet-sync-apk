package app.banksync

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Edit in Wallet" for a record already sent: what the user changed, as the fields of Wallet's record
 * PATCH (`counterParty`, `categoryId`, `note`, `amount`, `accountId`). What the user sets replaces what
 * Wallet has. "Remove from Wallet" is queued the same way, as a patch holding only [DELETE].
 */
object SentEdit {
    /** A queued patch with this key asks Wallet to delete the record instead of changing it. */
    const val DELETE = "\$delete"

    /** What the record dialog shows and the user can change. A blank payee or note means none; a null account, unchanged. */
    class Fields(val amount: Double, val payee: String?, val categoryId: String?, val note: String?, val accountId: String? = null)

    /** Whether [r] can be changed in Wallet from here: sent, with Wallet's id for it, and not a transfer (Wallet files those itself). */
    fun editable(r: Rec) = r.status == Store.SYNCED && r.walletId != null && r.amount != null && r.pairId == null && r.transferTo == null

    /** Why a sent record can't be changed or removed from here, in a sentence for the user; null when it can, or isn't sent. */
    fun whyNot(r: Rec): String? = when {
        r.status != Store.SYNCED || r.amount == null || editable(r) -> null
        r.pairId != null || r.transferTo != null -> "Wallet files transfers itself, so change this one in the Wallet app."
        // Wallet's id for a record has only been kept since 1.4.
        else -> "Sent before version 1.4, so this phone doesn't know which Wallet record it is. Change it in the Wallet app."
    }

    /** The fields that differ, by Wallet's name for them; a null value clears that field in Wallet. */
    fun changes(before: Fields, after: Fields): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        fun text(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }
        if (kotlin.math.abs(after.amount - before.amount) >= 0.005) out["amount"] = after.amount
        if (text(after.payee) != text(before.payee)) out["counterParty"] = text(after.payee)
        if (after.categoryId != before.categoryId) out["categoryId"] = after.categoryId
        if (text(after.note) != text(before.note)) out["note"] = text(after.note)
        if (after.accountId != null && after.accountId != before.accountId) out["accountId"] = after.accountId
        return out
    }

    /** The patch that asks Wallet to delete the record. */
    fun deletePatch(): String = JSONObject().put(DELETE, true).toString()

    /** Whether queued [patch] is a removal from Wallet rather than a change. */
    fun deleting(patch: String?) = patch != null && runCatching { JSONObject(patch).optBoolean(DELETE) }.getOrDefault(false)

    /** Whether Wallet's [error] for a removal says the record isn't there (deleted in Wallet already), which is what was wanted. */
    fun gone(error: String?) = error != null && Regex("not[ _]?found|no such|doesn't exist|does not exist|\\b404\\b", RegexOption.IGNORE_CASE).containsMatchIn(error)

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
