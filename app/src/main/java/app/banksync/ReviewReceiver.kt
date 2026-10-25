package app.banksync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Send / Discard buttons on a review notification, and Don't send on a "picked up" one. */
class ReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        val store = Store.get(context)
        when (intent.action) {
            SEND -> if (store.approve(id)) Uploader.schedule(context, 0, replace = true)
            // Only a record still waiting for approval can be discarded from its notification.
            DISCARD -> if (store.get(id)?.status == Store.REVIEW) store.delete(id)
            IGNORE_CASH_IN -> {
                Settings(context).cashInIgnored = true
                // The alert that raised the question has nowhere to go either.
                if (store.get(id)?.status == Store.UNPARSED) store.delete(id)
                if (store.counts()[Store.UNPARSED] == 0) Notifier.cancelUnparsed(context)
            }
            DROP -> {
                // Already gone: say so rather than pretend it was stopped.
                if (!store.discardWaiting(id)) Notifier.alert(context, "Too late to stop it", "That transaction was already sent to Wallet.")
                Notifier.cancelCaptured(context)
                return
            }
        }
        Notifier.cancelReview(context, id)
    }

    companion object {
        const val SEND = "app.banksync.REVIEW_SEND"
        const val DISCARD = "app.banksync.REVIEW_DISCARD"
        const val DROP = "app.banksync.CAPTURED_DROP"
        const val IGNORE_CASH_IN = "app.banksync.IGNORE_CASH_IN"
    }
}
