package app.banksync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Send / Discard buttons on a review notification. */
class ReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        val store = Store.get(context)
        when (intent.action) {
            SEND -> {
                store.setStatus(id, Store.PENDING)
                Uploader.schedule(context, 0, replace = true)
            }
            DISCARD -> store.delete(id)
        }
        Notifier.cancelReview(context, id)
    }

    companion object {
        const val SEND = "app.banksync.REVIEW_SEND"
        const val DISCARD = "app.banksync.REVIEW_DISCARD"
    }
}
