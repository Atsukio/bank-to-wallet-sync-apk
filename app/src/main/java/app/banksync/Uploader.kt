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
    /** Wallet takes at most 10 record changes a request. */
    private const val PATCH_BATCH = 10

    /** True while the upload job is running, so the home screen can show it. */
    @Volatile
    var running = false

    /** True from a "Sync now" tap until the job that answers it ends. */
    @Volatile
    var requested = false

    /** Told, from any thread, whenever the queue changed or the job started or ended; the home screen refreshes. */
    @Volatile
    var onChange: (() -> Unit)? = null

    fun changed() {
        onChange?.invoke()
    }

    /**
     * Lets go of transfer sides stuck waiting for approval, which hold their pair back with them.
     * Capture no longer leaves any; this frees ones from before. Cheap when there are none.
     */
    fun releaseTransfers(context: Context) {
        if (Settings(context).reviewMode) return
        val store = Store.get(context)
        val released = store.pairedReviews().filter { store.approve(it) }
        if (released.isEmpty()) return
        released.forEach { Notifier.cancelReview(context, it) }
        schedule(context, 0, replace = true)
        changed()
    }

    /** How long a hold lasts with no taps before uploads carry on by themselves: the user's batch delay. 0 means no holds. */
    fun holdMs(s: Settings): Long = s.batchDelayMin * 60_000L

    /** Whether uploads are held because the user has a record's actions open. */
    fun held(context: Context): Boolean = Settings(context).holdUntil > System.currentTimeMillis()

    /** Time until the held job should run: the rest of the hold, then what was left of the delay. Null when not held. */
    private fun holdLeft(s: Settings): Long? {
        val left = s.holdUntil - System.currentTimeMillis()
        return if (left > 0) left + s.holdLeftMs else null
    }

    /**
     * Holds uploads while the user has a record's actions open, so nothing goes before they've decided.
     * Each call counts as activity and moves the end to [holdMs] from now; with no more taps, uploads
     * carry on by themselves then. The delay's countdown stops where it was and resumes from there.
     * Does nothing when the batch delay is 0.
     */
    fun hold(context: Context) {
        val s = Settings(context)
        val length = holdMs(s)
        // With no batch delay the user wants records sent at once, open sheet or not.
        if (length == 0L) return
        val now = System.currentTimeMillis()
        if (s.holdUntil <= now) s.holdLeftMs = (s.uploadDueAt - now).coerceAtLeast(0)
        s.holdUntil = now + length
        // A job already sending finishes its batch; the next one sees the hold. Otherwise move the job to the hold's end.
        if (!running && Store.get(context).counts()[Store.PENDING] > 0) schedule(context, length + s.holdLeftMs, replace = true)
        changed()
    }

    /** Ends a hold: the countdown carries on from where it stopped, or [now] sends straight away. */
    fun release(context: Context, now: Boolean = false) {
        val s = Settings(context)
        val left = s.holdLeftMs
        // A hold that ran out already let its job go at the time; there's nothing to move.
        val wasHeld = held(context)
        s.holdUntil = 0
        if ((wasHeld || now) && !running && Store.get(context).counts()[Store.PENDING] > 0) schedule(context, if (now) 0 else left, replace = true)
        changed()
    }

    /** Whether a job is waiting for its turn (no network yet, say) rather than running. */
    fun waiting(context: Context): Boolean = context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null

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
        val latency = delayMs ?: (s.batchDelayMin * 60_000L)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UploadJobService::class.java))
            .setRequiredNetworkType(if (s.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(latency)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        // A scheduling failure must never take down the notification listener.
        try {
            js.schedule(job)
            s.uploadDueAt = System.currentTimeMillis() + latency
        } catch (e: SecurityException) {
            Log.e("Uploader", "Could not schedule upload job", e)
        }
    }

    fun run(context: Context, cancelled: () -> Boolean): Result {
        val s = Settings(context)
        val store = Store.get(context)
        val token = s.token
        if (token.isEmpty()) return Result.Done
        // The user has a record's actions open (a job queued before the hold, say): come back when it ends.
        holdLeft(s)?.let { return Result.RetryAfter(it) }

        // The network is up anyway, so this is the cheap moment to bring Wallet's rules and history up to date.
        WalletCache.refresh(context, token)

        while (true) {
            if (cancelled()) return Result.Retry
            holdLeft(s)?.let { return Result.RetryAfter(it) }
            val batch = store.pending(BATCH)
            // New records first; then changes the user made to sent ones.
            if (batch.isEmpty()) return sendEdits(store, s, token)

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
                    if (exists) {
                        store.markSynced(listOf(rec.id))
                        // It went as a whole transfer, so Wallet made the other side too.
                        val partner = rec.pairId?.let { store.get(it) }
                        if (partner != null && partner.status == Store.PENDING && partner.id > rec.id) {
                            store.markSynced(listOf(partner.id))
                        }
                    } else {
                        store.clearUncertain(rec.id)
                    }
                }
                continue
            }

            // Halves of a transfer: bind to the side already in Wallet, send both as one, or wait for the other.
            val plans = HashMap<Long, Transfers.Send>()
            for (rec in batch) {
                // A record the user (or a Cash in) made a transfer to an account goes as the whole transfer.
                rec.transferTo?.takeIf { rec.pairId == null }?.let { plans[rec.id] = Transfers.Send.New(it) }
                val partnerId = rec.pairId ?: continue
                val partner = store.get(partnerId)
                val plan = Transfers.plan(rec.id, partnerId, partner?.status, partner?.walletId, partner?.account.orEmpty())
                if (plan === Transfers.Send.Alone) store.unpair(rec.id) else plans[rec.id] = plan
            }
            val sending = batch.filter { plans[it.id] !== Transfers.Send.Wait }
            if (sending.isEmpty()) return sendEdits(store, s, token)

            // A record captured before the cache had anything to go on gets another try now. Transfers have no category.
            val ready = sending.map { rec ->
                if (rec.id in plans || rec.categoryId != null || rec.amount == null) return@map rec
                val pick = WalletCache.pick(context, rec.note.orEmpty(), rec.payee, rec.amount, rec.account) ?: return@map rec
                store.setCategory(rec.id, pick)
                store.get(rec.id) ?: rec
            }

            val ids = ready.map { it.id }
            val resp = try {
                WalletApi.postRecords(token, toJson(ready, s, plans))
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
                    val items = parseResults(resp.body, ready.size)
                    ready.forEachIndexed { i, rec -> synced(store, s, rec, items?.get(i), plans[rec.id]) }
                }
                207, 400 -> {
                    s.authError = false
                    val again = perItem(store, s, ready, resp, plans)
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
            // Show each batch landing, not just the end of the job.
            changed()
        }
    }

    /**
     * Marks [rec] sent, keeping Wallet's id so a later transfer side can bind to it. A record sent as a
     * whole transfer also made the other side in Wallet, so that one is done too.
     */
    private fun synced(store: Store, s: Settings, rec: Rec, item: JSONObject?, plan: Transfers.Send?) {
        // A transfer has no note of its own to edit later.
        val note = if (plan != null) null else noteFor(rec, s, s.rules().associate { it.accountId to it.accountCurrency }).orEmpty()
        store.markSynced(rec.id, item?.optString("id")?.takeIf { it.isNotEmpty() }, note)
        if (plan is Transfers.Send.New && plan.partnerId != null) {
            store.markSynced(plan.partnerId, item?.optString("createdMirrorRecordId")?.takeIf { it.isNotEmpty() })
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
    private fun perItem(store: Store, s: Settings, batch: List<Rec>, resp: WalletApi.Resp, plans: Map<Long, Transfers.Send>): List<Long> {
        val items = parseResults(resp.body, batch.size)
        if (items == null) {
            // Cannot tell which rows were created; retrying could duplicate them.
            store.markFailed(batch.map { it.id }, "Mixed result from Wallet: check the app")
            return emptyList()
        }
        val retry = ArrayList<Long>()
        val failed = HashMap<String, MutableList<Long>>()
        batch.forEachIndexed { i, rec ->
            val item = items[i]
            when {
                item == null -> failed.getOrPut("No result returned for this record") { ArrayList() }.add(rec.id)
                item.optBoolean("success") -> synced(store, s, rec, item, plans[rec.id])
                isTransient(item) -> retry += rec.id
                // Wallet wouldn't pair it (the other side changed in Wallet, say): send it as a normal record next pass.
                rec.id in plans -> store.unpair(rec.id)
                // Wallet's Transfer category can't go on a normal record. Never use it again, and leave
                // the record queued: the next pass picks another category (or none) and sends it.
                refused(item) != null -> {
                    s.refuseCategory(refused(item)!!)
                    store.clearCategory(rec.id)
                }
                else -> failed.getOrPut(item.opt("error")?.toString()?.take(200) ?: "Rejected") { ArrayList() }.add(rec.id)
            }
        }
        failed.forEach { (msg, ids) -> store.markFailed(ids, msg) }
        return retry
    }

    private fun parseResults(body: String, n: Int): Array<JSONObject?>? {
        val v = try { JSONTokener(body).nextValue() } catch (e: Exception) { return null }
        val arr = when (v) {
            is JSONArray -> v
            is JSONObject -> v.optJSONArray("results") ?: v.keys().asSequence().map { v.opt(it) }.filterIsInstance<JSONArray>().firstOrNull()
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

    private fun refused(item: JSONObject): String? = item.opt("error")?.toString()?.let { Categorizer.refusedCategory(it) }

    private fun isTransient(item: JSONObject): Boolean = item.optString("errorType") == "server_error" || when (val e = item.opt("error")) {
        is JSONObject -> e.optInt("status", e.optInt("code", 0)) >= 500
        is String -> e.contains("server", true) || e.contains("timeout", true)
        else -> false
    }

    /** The note Wallet gets: one the user wrote replaces the built one, and blank means they cleared it. */
    private fun noteFor(r: Rec, s: Settings, currencies: Map<String, String>): String? =
        (r.noteEdit ?: Parser.walletText(r.note.orEmpty(), r.payee, currencies[r.account].orEmpty(), s.includeNote).note)
            ?.takeIf { it.isNotBlank() }

    /**
     * Sends changes the user made to records already in Wallet ("Edit in Wallet") and removals ("Remove
     * from Wallet"), up to 10 a request. Sending either again is harmless, so a request with no answer is
     * simply tried again. What the user changed replaces what Wallet has; a refused one stays queued but
     * unsent, with the reason, until the user tries again, changes it or forgets it.
     */
    private fun sendEdits(store: Store, s: Settings, token: String): Result {
        while (true) {
            val batch = store.edits(PATCH_BATCH)
            if (batch.isEmpty()) return Result.Done
            val (deletes, patches) = batch.partition { SentEdit.deleting(it.patch) }
            for (group in listOf(deletes, patches)) {
                if (group.isEmpty()) continue
                val delete = group === deletes
                val resp = try {
                    if (delete) WalletApi.deleteRecords(token, JSONObject().put("ids", JSONArray(group.map { it.walletId })).toString())
                    else WalletApi.patchRecords(token, JSONArray(group.map { SentEdit.item(it.walletId!!, JSONObject(it.patch!!)) }).toString())
                } catch (e: java.net.ProtocolException) {
                    // The phone itself won't make this request; trying again wouldn't help, so say so on the row.
                    Log.w("Uploader", "Edit request refused by the phone", e)
                    group.forEach { store.editFailed(it.id, it.patch!!, "This phone couldn't send that to Wallet") }
                    continue
                } catch (e: IOException) {
                    return if (s.autoRetry) Result.Retry else Result.Done
                }
                val refused = if (delete) "Wallet wouldn't remove it" else "Wallet refused the change"
                when (resp.code) {
                    200, 207, 400 -> {
                        s.authError = false
                        val items = parseResults(resp.body, group.size)
                        group.forEachIndexed { i, r ->
                            val item = items?.get(i)
                            val error = item?.opt("error")?.toString()
                            when {
                                // No answer we can read, or a server hiccup: it goes again after the backoff.
                                item == null || isTransient(item) -> {}
                                // Already gone from Wallet is what a removal wanted.
                                item.optBoolean("success") || (delete && SentEdit.gone(error)) ->
                                    if (delete) store.delete(r.id) else store.editDone(r.id, r.patch!!)
                                else -> store.editFailed(r.id, r.patch!!, "$refused: " + (error?.take(200) ?: "rejected"))
                            }
                        }
                        if (group.any { r -> store.get(r.id)?.let { it.patch == r.patch && it.error == null } == true }) return Result.Retry
                    }
                    401, 403 -> {
                        s.authError = true
                        return Result.Done
                    }
                    429 -> return Result.RetryAfter(resp.retryAfterMs ?: 60_000)
                    in 500..599 -> return Result.Retry
                    else -> group.forEach { store.editFailed(it.id, it.patch!!, "$refused (HTTP ${resp.code})") }
                }
            }
            changed()
        }
    }

    private fun toJson(batch: List<Rec>, s: Settings, plans: Map<Long, Transfers.Send>): String {
        val arr = JSONArray()
        val currencies = s.rules().associate { it.accountId to it.accountCurrency }
        for (r in batch) {
            val o = JSONObject()
                .put("accountId", r.account)
                .put("amount", JSONObject().put("value", r.amount))
                .put("recordDate", r.date)
                .put("recordState", if (s.markUncleared) "uncleared" else "cleared")
            // The stored text is the whole alert; Wallet only gets the payment details from it.
            val w = Parser.walletText(r.note.orEmpty(), r.payee, currencies[r.account].orEmpty(), s.includeNote)
            w.payee?.let { o.put("counterParty", it) }
            noteFor(r, s, currencies)?.let { o.put("note", it) }
            when (val plan = plans[r.id]) {
                is Transfers.Send.Existing -> o.put("transfer", JSONObject().put("pairingMode", "existing").put("recordId", plan.walletId))
                is Transfers.Send.New -> o.put("transfer", JSONObject().put("pairingMode", "new").put("accountId", plan.toAccount))
                // Wallet sets a transfer's category itself and refuses one sent with it.
                else -> {
                    r.categoryId?.takeIf { it.isNotEmpty() }?.let { o.put("categoryId", it) }
                    if (r.labelIds.isNotEmpty()) o.put("labelIds", JSONArray(r.labelIds))
                }
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
            Uploader.running = true
            Uploader.changed()
            val settings = Settings(this)
            val store = Store.get(this)
            val failedBefore = store.counts()[Store.FAILED]
            val authBefore = settings.authError

            val result = try {
                Uploader.run(this) { cancelled }
            } catch (t: Throwable) {
                Uploader.Result.Retry
            }

            Uploader.running = false
            Notifier.capturedSent(this)
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
            Uploader.changed()
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        return true
    }
}
