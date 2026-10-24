package app.banksync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

object Uploader {
    private const val JOB_ID = 1
    private const val BATCH = 50

    sealed interface Result {
        object Done : Result
        object Retry : Result
        class RetryAfter(val ms: Long) : Result
    }

    /**
     * Queues one upload job. JobScheduler runs it only with a usable network and lets the system
     * batch it with other work, so the radio is not woken just for us. [replace] applies changed
     * constraints (e.g. Wi-Fi only) to a job that is already waiting.
     */
    fun schedule(context: Context, delayMs: Long? = null, replace: Boolean = false) {
        val js = context.getSystemService(JobScheduler::class.java)
        if (!replace && js.getPendingJob(JOB_ID) != null) return
        val s = Settings(context)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UploadJobService::class.java))
            .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delayMs ?: (s.batchDelayMin * 60_000L))
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        // A scheduling failure must never take down the notification listener.
        try {
            js.schedule(job)
        } catch (e: SecurityException) {
            Log.e("Uploader", "Could not schedule upload job", e)
        }
    }

    fun run(context: Context, cancelled: () -> Boolean): Result {
        val s = Settings(context)
        val store = Store.get(context)
        val token = s.token
        if (token.isEmpty()) return Result.Done

        // The network is up anyway, so this is the cheap moment to bring Wallet's rules and history up to date.
        WalletCache.refresh(context, token)

        // Incoming records already checked against Wallet this run and found missing.
        val checked = HashSet<Long>()

        while (true) {
            if (cancelled()) return Result.Retry
            val batch = store.pending(BATCH)
            if (batch.isEmpty()) return Result.Done

            // An earlier attempt may have reached Wallet without an answer coming back. Wallet has no
            // duplicate protection, so look for the record first and only re-send if it isn't there.
            val uncertain = batch.filter { it.uncertain }
            if (uncertain.isNotEmpty()) {
                for (rec in uncertain) {
                    val exists = try {
                        WalletApi.recordExists(token, rec)
                    } catch (e: WalletApi.HttpStatusException) {
                        if (e.code == 401 || e.code == 403) {
                            s.authError = true
                            return Result.Done
                        }
                        return transient(store, s, listOf(rec.id))
                    } catch (e: IOException) {
                        return transient(store, s, listOf(rec.id))
                    }
                    if (exists) store.markSynced(listOf(rec.id)) else store.clearUncertain(rec.id)
                }
                continue
            }

            // Money received that Wallet already holds is usually the other leg of a transfer the user
            // recorded themselves. Sending it again would count the money twice.
            if (s.skipExisting) {
                val incoming = batch.filter { (it.amount ?: 0.0) > 0 && it.id !in checked }
                var skipped = false
                for (rec in incoming) {
                    val exists = try {
                        WalletApi.recordExists(token, rec, windowSec = 180)
                    } catch (e: WalletApi.HttpStatusException) {
                        if (e.code == 401 || e.code == 403) {
                            s.authError = true
                            return Result.Done
                        }
                        return transient(store, s, listOf(rec.id))
                    } catch (e: IOException) {
                        return transient(store, s, listOf(rec.id))
                    }
                    if (exists) {
                        store.markSkipped(rec.id, "Already in Wallet, so not sent again (likely a transfer)")
                        skipped = true
                    } else {
                        checked += rec.id
                    }
                }
                if (skipped) continue
            }

            // A record captured before the cache had anything to go on gets another try now.
            val ready = batch.map { rec ->
                if (rec.categoryId != null || rec.amount == null) return@map rec
                val pick = WalletCache.pick(context, rec.note.orEmpty(), rec.payee, rec.amount, rec.account) ?: return@map rec
                store.setCategory(rec.id, pick)
                store.get(rec.id) ?: rec
            }

            val ids = batch.map { it.id }
            val resp = try {
                WalletApi.postRecords(token, toJson(ready, s))
            } catch (e: WalletApi.UncertainException) {
                store.markUncertain(ids)
                return transient(store, s, ids)
            } catch (e: IOException) {
                return transient(store, s, ids)
            }

            when (resp.code) {
                200 -> {
                    s.authError = false
                    s.tokenVerified = true
                    store.markSynced(ids)
                }
                207, 400 -> {
                    s.authError = false
                    val again = perItem(store, batch, resp)
                    if (again.isNotEmpty()) return transient(store, s, again)
                }
                401, 403 -> {
                    // Retrying with the same token cannot succeed. Records stay queued until it is fixed.
                    s.authError = true
                    return Result.Done
                }
                429 -> return Result.RetryAfter(resp.retryAfterMs ?: 60_000)
                in 500..599 -> return transient(store, s, ids)
                else -> store.markFailed(ids, "HTTP ${resp.code}")
            }
        }
    }

    /** Network or server trouble: keep the rows and let the backoff retry, up to the attempt limit. */
    private fun transient(store: Store, s: Settings, ids: List<Long>): Result {
        if (!s.autoRetry) {
            store.markFailed(ids, "Upload failed (auto-retry is off)")
            return Result.Done
        }
        store.bumpAttempts(ids, s.maxAttempts)
        return Result.Retry
    }

    /** Applies a mixed result. Returns the ids worth retrying. */
    private fun perItem(store: Store, batch: List<Rec>, resp: WalletApi.Resp): List<Long> {
        val items = parseResults(resp.body, batch.size)
        if (items == null) {
            // Cannot tell which rows were created; retrying could duplicate them.
            store.markFailed(batch.map { it.id }, "Mixed result from Wallet: check the app")
            return emptyList()
        }
        val ok = ArrayList<Long>()
        val retry = ArrayList<Long>()
        val failed = HashMap<String, MutableList<Long>>()
        batch.forEachIndexed { i, rec ->
            val item = items[i]
            when {
                item == null -> failed.getOrPut("No result returned for this record") { ArrayList() }.add(rec.id)
                item.optBoolean("success") -> ok += rec.id
                isTransient(item) -> retry += rec.id
                else -> failed.getOrPut(item.opt("error")?.toString()?.take(200) ?: "Rejected") { ArrayList() }.add(rec.id)
            }
        }
        store.markSynced(ok)
        failed.forEach { (msg, ids) -> store.markFailed(ids, msg) }
        return retry
    }

    private fun parseResults(body: String, n: Int): Array<JSONObject?>? {
        val v = try { JSONTokener(body).nextValue() } catch (e: Exception) { return null }
        val arr = when (v) {
            is JSONArray -> v
            is JSONObject -> v.keys().asSequence().map { v.opt(it) }.filterIsInstance<JSONArray>().firstOrNull()
            else -> null
        } ?: return null
        val out = arrayOfNulls<JSONObject>(n)
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val idx = if (o.has("inputIndex")) o.optInt("inputIndex") else k
            if (idx in 0 until n) out[idx] = o
        }
        return out
    }

    private fun isTransient(item: JSONObject): Boolean = when (val e = item.opt("error")) {
        is JSONObject -> e.optInt("status", e.optInt("code", 0)) >= 500
        is String -> e.contains("server", true) || e.contains("timeout", true)
        else -> false
    }

    private fun toJson(batch: List<Rec>, s: Settings): String {
        val arr = JSONArray()
        val currencies = s.rules().associate { it.accountId to it.accountCurrency }
        for (r in batch) {
            val o = JSONObject()
                .put("accountId", r.account)
                .put("amount", JSONObject().put("value", r.amount))
                .put("recordDate", r.date)
                .put("recordState", if (s.markUncleared) "uncleared" else "cleared")
            r.payee?.let { o.put("counterParty", it) }
            r.categoryId?.takeIf { it.isNotEmpty() }?.let { o.put("categoryId", it) }
            if (r.labelIds.isNotEmpty()) o.put("labelIds", JSONArray(r.labelIds))
            // The stored text is the whole alert; Wallet only gets the payment details from it.
            if (s.includeNote && !r.note.isNullOrEmpty()) {
                o.put("note", Parser.note(r.note, r.payee, currencies[r.account].orEmpty()))
            }
            arr.put(o)
        }
        return arr.toString()
    }
}

class UploadJobService : JobService() {
    @Volatile
    private var cancelled = false

    override fun onStartJob(params: JobParameters): Boolean {
        cancelled = false
        Thread {
            val settings = Settings(this)
            val store = Store.get(this)
            val failedBefore = store.counts()[Store.FAILED]
            val authBefore = settings.authError

            val result = try {
                Uploader.run(this) { cancelled }
            } catch (t: Throwable) {
                Uploader.Result.Retry
            }

            // Only speak up when something new needs attention, not on every retry.
            val newlyFailed = store.counts()[Store.FAILED] - failedBefore
            if (settings.authError && !authBefore) {
                Notifier.alert(this, "Wallet rejected your token", "Transactions are waiting. Open the app and tap Change token.")
            } else if (newlyFailed > 0) {
                Notifier.alert(this, "$newlyFailed transaction(s) couldn't be sent", "Open the app to see why or retry.")
            }

            // Finish first so the job slot is free, then queue the follow-up for a 429.
            jobFinished(params, result is Uploader.Result.Retry)
            if (result is Uploader.Result.RetryAfter) Uploader.schedule(this, result.ms, replace = true)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        return true
    }
}
