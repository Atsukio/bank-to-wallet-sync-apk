package app.banksync

import android.content.DialogInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

// The home screen's record sheet: what tapping or holding a Recent activity row opens, and its actions.

/**
 * Tapping or holding a row: a sheet with its record's details and what can be done with it. While it's
 * open, nothing is sent, so the record can't go before the user has decided; see [Uploader.hold].
 */
internal fun MainActivity.showActions(id: Long) {
    sheet?.dismiss()
    sheetId = id
    val s = ActionSheet(this) {
        sheet = null
        if (!Demo.active) Uploader.release(this)
        if (!isFinishing && !isDestroyed) refresh()
    }
    s.onTap = ::holdIfWaiting
    sheet = s
    holdIfWaiting()
    renderSheet()
    s.show()
}

/** Holds uploads while the open sheet's record could still change; each tap in the sheet counts as activity. */
internal fun MainActivity.holdIfWaiting() {
    if (Demo.active) return
    val r = store.get(sheetId) ?: return
    val halves = listOfNotNull(r, r.pairId?.let { store.get(it) })
    if (halves.any { it.status == Store.PENDING || it.status == Store.REVIEW }) Uploader.hold(this)
}

/** Fills the open sheet for its record as it stands now; closes it once the record is gone. */
internal fun MainActivity.renderSheet() {
    val s = sheet ?: return
    val r = store.get(sheetId) ?: return s.dismiss()
    val partner = r.pairId?.let { store.get(it) }
    // The half that needs something decides what can be done, as it decides what the row says.
    val src = if (partner != null && urgency(partner.status) > urgency(r.status)) partner else r
    val sent = r.status == Store.SYNCED && (partner == null || partner.status == Store.SYNCED)

    val actions = ArrayList<ActionSheet.Action>()
    fun add(icon: Int, text: String, hint: String? = null, danger: Boolean = false, close: Boolean = true, busy: Boolean = false, run: () -> Unit) {
        actions += ActionSheet.Action(icon, text, hint, danger, close, busy, run)
    }
    val notTransfer = { add(R.drawable.ic_swap, "Not a transfer", "Both sides go to Wallet as separate records", close = false) { notTransfer(r) } }
    when (src.status) {
        Store.PENDING -> {
            add(R.drawable.ic_send, "Send now") { sendNow(src, partner) }
            add(R.drawable.ic_edit, "Change account, category or note", close = false) { Dialogs.showRecord(this, src) { holdIfWaiting(); refresh() } }
            // A transfer side waiting for approval would hold its pair back, so only a lone record waits.
            if (r.pairId != null) notTransfer()
            else add(R.drawable.ic_pause_circle, "Don't send yet", "It waits for you to approve it", close = false) { holdBack(src) }
        }
        Store.REVIEW -> {
            add(R.drawable.ic_send, "Send now") { sendNow(src, partner) }
            add(R.drawable.ic_edit, "Review and change", close = false) { Dialogs.showRecord(this, src) { holdIfWaiting(); refresh() } }
            if (r.pairId != null) notTransfer()
            add(R.drawable.ic_delete, "Discard", "It won't be sent to Wallet", danger = true) { discard(src) }
        }
        Store.FAILED -> {
            add(R.drawable.ic_sync, "Retry now", src.error) { retry(src) }
            add(R.drawable.ic_delete, "Discard", "It won't be sent to Wallet", danger = true) {
                store.delete(src.id)
                refresh()
            }
        }
        Store.PLANNED -> {
            if (checkingPlanned) add(R.drawable.ic_sync, "Checking Wallet…", close = false, busy = true) {}
            else add(R.drawable.ic_sync, "Check Wallet now", "Shows it as done if the planned payment is marked paid there", close = false) { checkPlannedNow() }
            add(R.drawable.ic_send, "Send anyway", "Then skip the planned payment in Wallet, or it's in twice") { sendAnyway(src) }
            add(R.drawable.ic_receipt, "This isn't a planned payment", "It goes to Wallet like any other") { notPlanned(src) }
        }
        Store.UNPARSED -> {
            add(R.drawable.ic_edit, "Fix", "Enter the amount and account yourself", close = false) { openRecord(src) }
            add(R.drawable.ic_delete, "Delete", danger = true) { deleteUnread(src) }
        }
    }
    // A sent record Wallet gave an id for can be changed or removed there; a transfer is Wallet's to file.
    if (src.status == Store.SYNCED && src.patch != null) {
        val deleting = SentEdit.deleting(src.patch)
        if (src.error != null) {
            add(R.drawable.ic_sync, "Try again", src.error) { retryEdit(src) }
        }
        if (deleting) {
            add(R.drawable.ic_wallet, "Keep it in Wallet", "Cancels the removal if it hasn't gone yet") { dropEdit(src) }
        } else if (src.error != null) {
            add(R.drawable.ic_wallet, "Forget my change", "Wallet keeps what it has; this phone keeps showing your change") { dropEdit(src) }
        }
    }
    if (SentEdit.editable(src) && !SentEdit.deleting(src.patch)) {
        add(R.drawable.ic_edit, "Edit in Wallet", "Amount, payee, account, category or note. Replaces what Wallet has", close = false) {
            Dialogs.showEditSent(this, src) { refresh() }
        }
        add(R.drawable.ic_delete, "Remove from Wallet…", "Deletes the record in Wallet too", danger = true, close = false) {
            confirmDeleteSent(src)
        }
    }
    // A not-understood alert's Delete above is the same thing.
    if (src.status != Store.UNPARSED) {
        add(R.drawable.ic_smartphone, "Remove from this phone…", if (sent) "It stays in Wallet" else null, danger = true, close = false) {
            confirmRemove(r, partner)
        }
    }

    val paused = if (!Demo.active && Uploader.held(this)) {
        val min = Uploader.holdMs(settings) / 60_000
        "Sending is paused while this is open. It carries on when you close it, or after " +
            "$min minute${if (min == 1L) "" else "s"} without a tap."
    } else null
    // The header is the row in full: the user opened this one, so hidden details show here.
    val look = describe(r, partner?.takeIf { (it.amount ?: 0.0) * (r.amount ?: 0.0) < 0 }, hide = false)
    val header = ActionSheet.Header(look.icon, look.symbol, look.monogram, look.title, look.sub, look.amount, look.amountColor)
    val facts = Dialogs.facts(this, r, partner)
    // A planned payment a week past due may never be marked paid in Wallet: ask, rather than hold it for good.
    val stale = if (src.status == Store.PLANNED && Planned.stale(src.planDue, System.currentTimeMillis())) {
        val due = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(src.planDue)
        "Due $due and still not marked paid in Wallet. If you won't mark it paid there, send it as a normal record."
    } else null
    // Paused matters more while it lasts; otherwise a sent record that can't be changed says why.
    val cant = (SentEdit.whyNot(src) ?: stale).takeIf { paused == null }
    if (cant != null) s.fill(header, cant, facts, actions, R.drawable.ic_info) else s.fill(header, paused, facts, actions)
}

/** The sheet's Send now: approves a record waiting for approval (both sides of a transfer) and sends straight away. */
internal fun MainActivity.sendNow(src: Rec, partner: Rec?) {
    for (h in listOfNotNull(src, partner).filter { it.status == Store.REVIEW }) {
        if (!store.approve(h.id)) return toast("Too late: it was already sent or removed")
        Notifier.cancelReview(this, h.id)
    }
    if (Demo.active) {
        listOfNotNull(src, partner).forEach { Demo.markSent(store, store.get(it.id) ?: it) }
        toast("Demo: marked as sent. Nothing went to Wallet.")
        return refresh()
    }
    syncBase = store.counts()[Store.SYNCED]
    Uploader.requested = true
    Uploader.release(this, now = true)
    refresh()
}

/** The sheet's "Check Wallet now" for a held planned payment: closes it if Wallet has it as paid. */
internal fun MainActivity.checkPlannedNow() {
    if (Demo.active) return toast("Not available in the demo")
    if (checkingPlanned) return
    val id = sheetId
    checkingPlanned = true
    renderSheet()
    checkPlanned(force = true) { ok ->
        checkingPlanned = false
        renderSheet()
        val held = store.get(id)?.status == Store.PLANNED
        toast(
            when {
                !ok -> "Couldn't reach Wallet. Try again later"
                held -> "Not marked paid in Wallet yet"
                else -> "Paid in Wallet: done"
            }
        )
    }
}

/** The menu's "Check planned payments": the same check for every held one at once, saying how many closed. */
internal fun MainActivity.checkAllPlanned() {
    if (Demo.active) return toast("Not available in the demo")
    if (checkingPlanned) return
    val before = store.counts()[Store.PLANNED]
    checkingPlanned = true
    toast("Checking Wallet…")
    checkPlanned(force = true) { ok ->
        checkingPlanned = false
        renderSheet()
        toast(Planned.result(ok, before, store.counts()[Store.PLANNED]))
    }
}

/** "Send anyway": a held planned payment goes to Wallet now, like Send now. */
internal fun MainActivity.sendAnyway(r: Rec) {
    if (!store.unplan(r.id, Store.PENDING)) return toast("Too late: it was already marked paid in Wallet")
    Notifier.cancelReview(this, r.id)
    sendNow(store.get(r.id) ?: r, null)
}

/** "This isn't a planned payment": it goes on as any alert would, waiting for approval when the settings say so. */
internal fun MainActivity.notPlanned(r: Rec) {
    val ask = settings.reviewMode || (settings.askNoCategory && r.categoryId == null)
    if (!store.unplan(r.id, if (ask) Store.REVIEW else Store.PENDING)) return toast("Too late: it was already marked paid in Wallet")
    Notifier.cancelReview(this, r.id)
    if (!Demo.active && !ask) Uploader.schedule(this)
    refresh()
}

/** "Don't send yet": the record waits for approval, as in "ask before sending" mode. */
internal fun MainActivity.holdBack(r: Rec) {
    if (!store.holdBack(r.id)) toast("Too late: it was already sent")
    refresh()
}

internal fun MainActivity.discard(r: Rec) {
    if (!store.discardWaiting(r.id)) toast("Too late: it was already sent")
    Notifier.cancelReview(this, r.id)
    refresh()
}

internal fun MainActivity.retry(r: Rec) {
    store.setStatus(r.id, Store.PENDING)
    if (!Demo.active) Uploader.release(this, now = true)
    refresh()
}

/** Splits a transfer back into two normal records; the hold keeps them until the sheet closes. */
internal fun MainActivity.notTransfer(r: Rec) {
    store.unpair(r.id)
    if (!Demo.active) Uploader.schedule(this, 0, replace = true)
    toast("Both sides will go to Wallet as separate records")
    refresh()
}

/**
 * From a row's actions: forget one record on this phone only. With it gone, a scan can capture the bank's
 * alert again if it's still in the notification shade, which is how to redo one by hand.
 */
internal fun MainActivity.confirmRemove(r: Rec, other: Rec? = null) {
    val sent = r.status == Store.SYNCED && (other == null || other.status == Store.SYNCED)
    MaterialAlertDialogBuilder(this)
        .setTitle(if (other != null) "Remove this transfer?" else "Remove from this list?")
        .setMessage(
            (if (sent) "It stays in Wallet; only this phone forgets it. " else "It won't be sent to Wallet. ") +
                (if (other != null) "Both sides are removed. " else "") +
                "If the bank's notification is still in your notification shade, Scan notifications picks it up again as new."
        )
        .setPositiveButton("Remove") { _, _ ->
            // A transfer is removed with both sides; a lone half leaves its partner as a normal record.
            store.unpair(r.id)
            store.delete(r.id)
            Notifier.cancelReview(this, r.id)
            if (other != null) {
                store.delete(other.id)
                Notifier.cancelReview(this, other.id)
            }
            refresh()
            if (Demo.active) return@setPositiveButton
            Snackbar.make(findViewById(R.id.root), "Removed", Snackbar.LENGTH_LONG).setAction("Scan now") { scan() }.show()
        }
        .setNegativeButton("Cancel", null)
        .show()
        .danger(DialogInterface.BUTTON_POSITIVE)
}

/** The sheet's "Remove from Wallet": after asking, queues the record's deletion in Wallet; the row goes once Wallet has done it. */
internal fun MainActivity.confirmDeleteSent(r: Rec) {
    MaterialAlertDialogBuilder(this)
        .setTitle("Remove from Wallet?")
        .setMessage(
            "The record is deleted in Wallet with the next upload, then from this phone. " +
                "Until it goes, you can keep it from the same menu."
        )
        .setPositiveButton("Remove") { _, _ ->
            sheet?.dismiss()
            if (Demo.active) {
                // Nothing goes to Wallet in the demo; the record just goes.
                store.delete(r.id)
            } else {
                if (!store.deleteSent(r.id)) return@setPositiveButton toast("This record can't be removed from here")
                Uploader.schedule(this)
            }
            refresh()
        }
        .setNegativeButton("Cancel", null)
        .show()
        .danger(DialogInterface.BUTTON_POSITIVE)
}

/** Sends a change or removal Wallet refused once more. */
internal fun MainActivity.retryEdit(r: Rec) {
    // It goes with the next upload, as an edit does; a job already waiting takes it along.
    if (store.retryEdit(r.id) && !Demo.active) Uploader.schedule(this)
    refresh()
}

/** Forgets a change or removal that hasn't reached Wallet. */
internal fun MainActivity.dropEdit(r: Rec) {
    store.dropEdit(r.id)
    refresh()
}

/** The details dialog's Delete: an alert that wasn't understood goes without asking, as from the Fix dialog. */
internal fun MainActivity.deleteUnread(r: Rec) {
    store.delete(r.id)
    refresh()
    if (Demo.active) return
    Snackbar.make(findViewById(R.id.root), "Deleted", Snackbar.LENGTH_LONG).setAction("Scan now") { scan() }.show()
}

internal fun MainActivity.openRecord(r: Rec) {
    when (r.status) {
        Store.UNPARSED ->
            if (!Demo.active && Parser.isCashIn(r.note.orEmpty())) Dialogs.showCashIn(this, r) { refresh() }
            else Dialogs.showFix(this, r) { refresh() }
        Store.REVIEW, Store.PENDING -> Dialogs.showRecord(this, r) { refresh() }
        Store.SYNCED -> if (SentEdit.editable(r)) Dialogs.showEditSent(this, r) { refresh() }
        Store.FAILED -> MaterialAlertDialogBuilder(this)
            .setTitle("Couldn't be sent")
            .setMessage(r.error ?: "Unknown error")
            .setPositiveButton("Retry") { _, _ ->
                store.setStatus(r.id, Store.PENDING)
                if (!Demo.active) Uploader.schedule(this, 0, replace = true)
                refresh()
            }
            .setNegativeButton("Discard") { _, _ ->
                store.delete(r.id)
                refresh()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }
}
