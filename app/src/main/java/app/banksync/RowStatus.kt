package app.banksync

/** The status pill on a Recent activity row: shown only when a record isn't simply sent. */
object RowStatus {
    /**
     * The pill for a row whose deciding record is [src] (a transfer's more pressing half), or null for a
     * plain sent one. [held]: uploads are paused while a sheet is open.
     */
    fun pill(src: Rec, isTransfer: Boolean, held: Boolean): Pair<String, Chip>? = when (src.status) {
        Store.UNPARSED -> "Couldn't read" to Chip.YOU
        Store.REVIEW -> "Approve?" to Chip.YOU
        Store.FAILED -> "Failed" to Chip.BAD
        // Landing in Wallet as "Unknown" is worth a look before it goes.
        Store.PENDING -> when {
            !isTransfer && src.categoryName == null && src.amount != null -> "No category" to Chip.YOU
            held -> "Paused" to Chip.WAIT
            else -> "Waiting" to Chip.WAIT
        }
        else -> when {
            src.patch != null && src.error != null -> "Wallet refused" to Chip.BAD
            SentEdit.deleting(src.patch) -> "Removing" to Chip.WAIT
            src.patch != null -> "Updating" to Chip.WAIT
            else -> null
        }
    }
}
