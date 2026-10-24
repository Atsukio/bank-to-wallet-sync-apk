package app.banksync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Rec(
    val id: Long,
    val pkg: String,
    val account: String,
    val amount: Double?,
    val payee: String?,
    val note: String?,
    val date: String,
    val status: Int,
    val attempts: Int,
    val error: String?,
    val categoryId: String?,
    /** True when an earlier upload may have reached Wallet even though no answer came back. */
    val uncertain: Boolean,
    val categoryName: String? = null,
    /** Label ids from a Wallet rule, sent with the record. */
    val labelIds: List<String> = emptyList(),
    /** A [CategorySource] value, or null when nothing picked a category. */
    val categorySource: String? = null,
)

/** Local queue. A row survives offline periods and process death until Wallet confirms it. */
class Store private constructor(context: Context) : SQLiteOpenHelper(context, "queue.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE rec(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hash TEXT NOT NULL, pkg TEXT NOT NULL, account TEXT NOT NULL,
                amount REAL, payee TEXT, note TEXT, date TEXT NOT NULL,
                status INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                error TEXT, created INTEGER NOT NULL,
                uncertain INTEGER NOT NULL DEFAULT 0, categoryId TEXT, transferTo TEXT,
                posted INTEGER NOT NULL DEFAULT 0,
                categoryName TEXT, labelIds TEXT, categorySource TEXT)"""
        )
        db.execSQL("CREATE INDEX rec_status ON rec(status)")
        db.execSQL("CREATE INDEX rec_hash ON rec(hash, created)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("ALTER TABLE rec ADD COLUMN uncertain INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE rec ADD COLUMN categoryId TEXT")
            db.execSQL("ALTER TABLE rec ADD COLUMN transferTo TEXT")
        }
        if (old < 3) {
            // When the notification was posted. `date` can now be the (earlier) time written in the alert.
            db.execSQL("ALTER TABLE rec ADD COLUMN posted INTEGER NOT NULL DEFAULT 0")
        }
        if (old < 4) {
            db.execSQL("ALTER TABLE rec ADD COLUMN categoryName TEXT")
            db.execSQL("ALTER TABLE rec ADD COLUMN labelIds TEXT")
            db.execSQL("ALTER TABLE rec ADD COLUMN categorySource TEXT")
        }
    }

    fun isDuplicate(hash: String, windowMs: Long = 120_000): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM rec WHERE hash=? AND created>? LIMIT 1",
            arrayOf(hash, (System.currentTimeMillis() - windowMs).toString())
        ).use { it.moveToFirst() }

    /**
     * Catch-up duplicate check: do we already hold this alert? Compares the text hash and the posted
     * time (within two minutes, since banks re-post updates), over everything still in history.
     */
    fun hasSimilar(hash: String, epochMs: Long, windowMs: Long = 120_000): Boolean {
        readableDatabase.rawQuery("SELECT date, posted FROM rec WHERE hash=?", arrayOf(hash)).use { c ->
            while (c.moveToNext()) {
                // Compare on when the notification was posted; older rows only have the record date.
                val t = c.getLong(1).takeIf { it > 0 }
                    ?: runCatching { java.time.Instant.parse(c.getString(0)).toEpochMilli() }.getOrNull()
                    ?: continue
                if (kotlin.math.abs(t - epochMs) <= windowMs) return true
            }
        }
        return false
    }

    fun insert(
        hash: String, pkg: String, account: String, amount: Double?,
        payee: String?, note: String?, date: String, status: Int,
        error: String? = null, category: CategoryPick? = null, posted: Long = 0L,
    ): Long {
        val v = ContentValues().apply {
            put("hash", hash); put("pkg", pkg); put("account", account)
            put("amount", amount); put("payee", payee); put("note", note)
            put("date", date); put("status", status); put("error", error)
            putCategory(category); put("posted", posted)
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("rec", null, v)
    }

    private fun ContentValues.putCategory(c: CategoryPick?) {
        put("categoryId", c?.id); put("categoryName", c?.name)
        put("labelIds", c?.labelIds?.joinToString(",")); put("categorySource", c?.source)
    }

    /** Sets the category picked by the uploader for a record that had none. */
    fun setCategory(id: Long, c: CategoryPick) {
        val v = ContentValues().apply { putCategory(c) }
        writableDatabase.update("rec", v, "id=? AND categoryId IS NULL", arrayOf(id.toString()))
    }

    /**
     * The user's changes to a record that hasn't gone yet. Returns false when it was sent (or removed)
     * in the meantime, so the change came too late.
     */
    fun edit(id: Long, account: String, category: CategoryPick?): Boolean {
        val v = ContentValues().apply { put("account", account); putCategory(category) }
        return writableDatabase.update("rec", v, "id=? AND status IN ($PENDING,$REVIEW)", arrayOf(id.toString())) > 0
    }

    fun pending(limit: Int): List<Rec> = query("status=$PENDING", "id", limit)

    fun recent(limit: Int): List<Rec> = query(null, "id DESC", limit)

    fun get(id: Long): Rec? = query("id=$id", "id", 1).firstOrNull()

    fun counts(): IntArray {
        val out = IntArray(5)
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM rec GROUP BY status", null).use {
            while (it.moveToNext()) out[it.getInt(0)] = it.getInt(1)
        }
        return out
    }

    /** Drops the raw notification text once Wallet has the record. */
    fun markSynced(ids: List<Long>) = exec("status=$SYNCED, note=NULL, error=NULL, uncertain=0", ids)

    /** Not sent because Wallet already holds it; the reason stays visible in the activity list. */
    fun markSkipped(id: Long, reason: String) {
        val v = ContentValues().apply {
            put("status", SYNCED); put("error", reason); putNull("note"); put("uncertain", 0)
        }
        writableDatabase.update("rec", v, "id=?", arrayOf(id.toString()))
    }

    fun markUncertain(ids: List<Long>) = exec("uncertain=1", ids)

    fun clearUncertain(id: Long) = exec("uncertain=0", listOf(id))

    fun markFailed(ids: List<Long>, error: String) {
        if (ids.isEmpty()) return
        val v = ContentValues().apply { put("status", FAILED); put("error", error) }
        writableDatabase.update("rec", v, "id IN (${ids.joinToString(",")})", null)
    }

    /** Counts a failed upload attempt and gives up on rows that hit [max]. */
    fun bumpAttempts(ids: List<Long>, max: Int) {
        if (ids.isEmpty()) return
        val list = ids.joinToString(",")
        val db = writableDatabase
        db.execSQL("UPDATE rec SET attempts=attempts+1 WHERE id IN ($list)")
        db.execSQL(
            "UPDATE rec SET status=$FAILED, error='Gave up after ' || attempts || ' attempts' " +
                "WHERE id IN ($list) AND attempts>=$max"
        )
    }

    /**
     * Queues a record the user approved. Only one still waiting for approval moves, so a stale Send
     * button can't re-queue something already sent or discarded. Returns whether it moved.
     */
    fun approve(id: Long): Boolean {
        val v = ContentValues().apply { put("status", PENDING); put("attempts", 0); putNull("error") }
        return writableDatabase.update("rec", v, "id=? AND status=$REVIEW", arrayOf(id.toString())) > 0
    }

    fun setStatus(id: Long, status: Int) {
        val v = ContentValues().apply { put("status", status); put("attempts", 0); putNull("error") }
        writableDatabase.update("rec", v, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("rec", "id=?", arrayOf(id.toString()))
    }

    /** Turns a "not understood" row into a normal queued record using values the user supplied. */
    fun fix(id: Long, account: String, amount: Double, payee: String?, category: CategoryPick?) {
        val v = ContentValues().apply {
            put("account", account); put("amount", amount); put("payee", payee)
            putCategory(category); put("status", PENDING); put("attempts", 0); putNull("error")
        }
        writableDatabase.update("rec", v, "id=?", arrayOf(id.toString()))
    }

    fun retryFailed(): Int {
        val v = ContentValues().apply { put("status", PENDING); put("attempts", 0); putNull("error") }
        return writableDatabase.update("rec", v, "status=$FAILED AND amount IS NOT NULL", null)
    }

    fun clearHistory() {
        writableDatabase.delete("rec", "status IN ($SYNCED,$UNPARSED)", null)
    }

    fun purge(keepDays: Int) {
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        writableDatabase.delete("rec", "status IN ($SYNCED,$UNPARSED) AND created<?", arrayOf(cutoff.toString()))
    }

    private fun exec(set: String, ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("UPDATE rec SET $set WHERE id IN (${ids.joinToString(",")})")
    }

    private fun query(where: String?, order: String, limit: Int): List<Rec> {
        val out = ArrayList<Rec>()
        readableDatabase.query(
            "rec",
            arrayOf(
                "id", "pkg", "account", "amount", "payee", "note", "date", "status", "attempts", "error",
                "categoryId", "uncertain", "categoryName", "labelIds", "categorySource"
            ),
            where, null, null, null, order, limit.toString()
        ).use {
            while (it.moveToNext()) {
                out += Rec(
                    it.getLong(0), it.getString(1), it.getString(2),
                    if (it.isNull(3)) null else it.getDouble(3),
                    it.getString(4), it.getString(5), it.getString(6),
                    it.getInt(7), it.getInt(8), it.getString(9),
                    it.getString(10), it.getInt(11) == 1,
                    it.getString(12), it.getString(13)?.split(',')?.filter { s -> s.isNotEmpty() }.orEmpty(),
                    it.getString(14),
                )
            }
        }
        return out
    }

    companion object {
        const val PENDING = 0
        const val SYNCED = 1
        const val FAILED = 2
        const val UNPARSED = 3
        const val REVIEW = 4

        @Volatile
        private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }
    }
}
