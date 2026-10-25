package app.banksync

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.view.WindowManager
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

object Dialogs {

    /** A Wallet account a rule points at; the Fix dialog offers these without going online. */
    private class Target(val id: String, val name: String, val currency: String) {
        val label get() = if (currency.isEmpty()) name else "$name ($currency)"
    }

    /**
     * Lets the user finish a "not understood" alert by hand: the original text is shown, the account,
     * amount, direction and payee are pre-filled from what could be read, and the result joins the
     * normal upload queue.
     */
    fun showFix(activity: Activity, rec: Rec, onDone: () -> Unit) {
        val settings = Settings(activity)
        // This app's accounts first, then every other account a rule uses.
        val rules = Demo.rules(settings).sortedBy { if (it.pkg == rec.pkg) 0 else 1 }
        val targets = rules.distinctBy { it.accountId }.map { Target(it.accountId, it.accountName, it.accountCurrency) }
        if (targets.isEmpty()) {
            Toast.makeText(activity, "Add this bank app under Bank apps first", Toast.LENGTH_LONG).show()
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_fix, null)
        val text = rec.note.orEmpty()
        view.findViewById<TextView>(R.id.fixOriginal).text = "\"$text\""
        view.findViewById<TextView>(R.id.fixReason).text = rec.error.orEmpty()
        val accountField = view.findViewById<AutoCompleteTextView>(R.id.fixAccount)
        val amountLayout = view.findViewById<TextInputLayout>(R.id.fixAmountLayout)
        val amountField = view.findViewById<EditText>(R.id.fixAmount)
        val parsed = Parser.parse(text)
        val alertAmount = parsed?.amount?.let { kotlin.math.abs(it) } ?: Parser.amountOf(text)
        if (parsed != null && parsed.amount > 0) view.findViewById<RadioButton>(R.id.fixIncome).isChecked = true
        parsed?.payee?.let { view.findViewById<EditText>(R.id.fixPayee).setText(it) }

        val payeeField = view.findViewById<EditText>(R.id.fixPayee)
        fun payee() = payeeField.text.toString().trim().ifEmpty { null }
        // The demo never guesses from the user's real Wallet data.
        fun guess(payee: String?, amount: Double, account: String) =
            if (Demo.active) null else WalletCache.pick(activity, text, payee, amount, account)
        val chooser = CategoryChooser(activity, view, guess(parsed?.payee, parsed?.amount ?: -1.0, rec.account), ::payee)

        // An app whose rules all need match text: offer to make the chosen account its catch-all, so the
        // next alert like this one goes straight through.
        val appRules = if (Demo.active) null else settings.rulesFor(rec.pkg)
        val remember = view.findViewById<CheckBox>(R.id.fixRemember)
        if (appRules != null && appRules.size > 1 && appRules.none { it.match.isEmpty() }) {
            remember.visibility = View.VISIBLE
            remember.text = "Use this account for other ${appRules[0].label} alerts that match no rule"
        }

        lateinit var dialog: AlertDialog
        fun amount() = amountField.text.toString().replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }
        fun refresh() {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = amount() != null
        }

        var target = targets.firstOrNull { it.id == rec.account } ?: targets[0]
        var filled = ""
        // A foreign-currency alert's amount isn't what the account was charged (rate and fees come
        // later), so it's never pre-filled for an account in another currency.
        fun pickTarget(t: Target) {
            target = t
            accountField.setText(t.label, false)
            val foreign = parsed?.currency != null && t.currency.isNotEmpty() && parsed.currency != t.currency
            amountLayout.helperText = if (foreign) {
                "The alert is in ${parsed?.currency}. Enter the ${t.currency} amount from your statement."
            } else {
                null
            }
            val typed = amountField.text.toString()
            if (typed.isEmpty() || typed == filled) {
                filled = if (foreign || alertAmount == null) "" else String.format(Locale.US, "%.2f", alertAmount)
                amountField.setText(filled)
            }
        }
        accountField.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, targets.map { it.label }))
        accountField.setOnItemClickListener { _, _, i, _ -> pickTarget(targets[i]) }
        pickTarget(target)
        amountField.doAfterTextChanged { refresh() }

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Enter this transaction")
            .setView(view)
            .setPositiveButton("Queue") { _, _ ->
                val amount = amount() ?: return@setPositiveButton
                val signed = if (view.findViewById<RadioButton>(R.id.fixIncome).isChecked) amount else -amount
                val payee = payee()
                // Untouched, the category is worked out again from what was finally entered.
                val category = chooser.result { guess(payee, signed, target.id) }
                chooser.commit()
                if (remember.isChecked && appRules != null) {
                    settings.addRule(Rule(rec.pkg, appRules[0].label, "", target.id, target.name, target.currency))
                }
                Store.shown(activity).fix(rec.id, target.id, signed, payee, category)
                if (!Demo.active) Uploader.schedule(activity, 0, replace = true)
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                Store.shown(activity).delete(rec.id)
                onDone()
            }
            .create()
        dialog.setOnShowListener { refresh() }
        dialog.show()
    }

    /**
     * A captured transaction that hasn't gone yet: shows what will be sent and lets the user change the
     * account or category first. For one waiting for approval, it also sends or discards it.
     */
    fun showRecord(activity: Activity, rec: Rec, onDone: () -> Unit) {
        val settings = Settings(activity)
        val store = Store.shown(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_record, null)
        val amount = rec.amount ?: 0.0
        view.findViewById<TextView>(R.id.recordAmount).text =
            String.format(Locale.US, "%s%,.2f", if (amount > 0) "+" else "−", kotlin.math.abs(amount))
        view.findViewById<TextView>(R.id.recordWhat).text =
            rec.payee?.takeIf { it.isNotBlank() } ?: if (amount > 0) "Money in" else "Payment"

        val targets = Demo.rules(settings).distinctBy { it.accountId }.map { Target(it.accountId, it.accountName, it.accountCurrency) }
        var target = targets.firstOrNull { it.id == rec.account } ?: Target(rec.account, "Account no longer linked", "")
        val accountField = view.findViewById<AutoCompleteTextView>(R.id.recordAccount)
        accountField.setText(target.label, false)
        accountField.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, targets.map { it.label }))
        accountField.setOnItemClickListener { _, _, i, _ -> target = targets[i] }

        val initial = rec.categoryId?.let {
            CategoryPick(it, rec.categoryName ?: "Category", rec.labelIds, rec.categorySource ?: CategorySource.MANUAL)
        }
        val chooser = CategoryChooser(activity, view, initial) { rec.payee }

        // The note Wallet would get as it stands; the field starts with it, or with the user's earlier edit.
        val autoNote = Parser.walletText(rec.note.orEmpty(), rec.payee, target.currency, settings.includeNote).note.orEmpty()
        val noteField = view.findViewById<EditText>(R.id.recordNote)
        noteField.setText(rec.noteEdit ?: autoNote)

        /** Saves the changes; false (with a message) when the record was sent meanwhile. */
        fun save(): Boolean {
            chooser.commit()
            // Left as it was shown, the note keeps following the alert; anything else is sent as typed.
            val typed = noteField.text.toString().trim()
            val noteEdit = if (rec.noteEdit == null && typed == autoNote) null else typed
            if (store.edit(rec.id, target.id, chooser.result { initial }, noteEdit)) return true
            Toast.makeText(activity, "Too late: it was already sent", Toast.LENGTH_LONG).show()
            return false
        }

        // Wallet files a transfer itself, so there's no category to choose; it can be split up again instead.
        val paired = rec.pairId != null
        if (paired) view.findViewById<View>(R.id.categoryLayout).visibility = View.GONE
        fun notTransfer() {
            store.unpair(rec.id)
            if (!Demo.active) Uploader.schedule(activity, 0, replace = true)
            Toast.makeText(activity, "Both sides will go to Wallet as separate records", Toast.LENGTH_LONG).show()
            onDone()
        }

        // Scrollable so the note field stays reachable above the keyboard.
        val builder = MaterialAlertDialogBuilder(activity).setView(android.widget.ScrollView(activity).apply { addView(view) })
        if (rec.status == Store.REVIEW) {
            builder.setTitle("Send to Wallet?")
                .setPositiveButton("Send") { _, _ ->
                    if (save() && store.approve(rec.id) && !Demo.active) Uploader.schedule(activity, 0, replace = true)
                    Notifier.cancelReview(activity, rec.id)
                    onDone()
                }
                .setNegativeButton("Discard") { _, _ ->
                    store.delete(rec.id)
                    Notifier.cancelReview(activity, rec.id)
                    onDone()
                }
                .apply { if (paired) setNeutralButton("Not a transfer") { _, _ -> notTransfer() } else setNeutralButton("Cancel", null) }
        } else {
            builder.setTitle(if (paired) "Transfer waiting to send" else "Waiting to send")
                .setPositiveButton("Save") { _, _ ->
                    save()
                    onDone()
                }
                .setNegativeButton("Cancel", null)
            if (paired) builder.setNeutralButton("Not a transfer") { _, _ -> notTransfer() }
        }
        builder.show()
    }

    /**
     * Drives the category field in part_category.xml: shows the suggested category and why, offers
     * every Wallet category (loaded in the background), and offers to remember a hand-picked one for the
     * merchant so the next alert from it gets the same category.
     */
    class CategoryChooser(
        private val activity: Activity,
        root: View,
        initial: CategoryPick?,
        private val payee: () -> String?,
    ) {
        private val layout = root.findViewById<TextInputLayout>(R.id.categoryLayout)
        private val field = root.findViewById<AutoCompleteTextView>(R.id.categoryField)
        private val remember = root.findViewById<CheckBox>(R.id.rememberBox)
        private var chosen: CategoryPick? = initial
        private var touched = false

        init {
            field.setText(initial?.name ?: NONE, false)
            layout.helperText = initial?.let { describe(it).replaceFirstChar { c -> c.uppercase() } }
                ?: "None found: Wallet will show Unknown"
            val token = Settings(activity).token
            Thread {
                val list = if (Demo.active) Demo.categories else runCatching { WalletCache.categories(activity, token) }.getOrNull()
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if (list.isNullOrEmpty()) {
                        layout.helperText = "Couldn't load your Wallet categories"
                        return@runOnUiThread
                    }
                    categories = list
                }
            }.start()
            // A searchable list instead of a long dropdown; the field only opens it.
            field.isFocusable = false
            field.setOnClickListener { open() }
            layout.setEndIconOnClickListener { open() }
        }

        private var categories: List<WalletApi.Category>? = null

        private fun open() {
            val list = categories ?: return Toast.makeText(activity, "Still loading your categories", Toast.LENGTH_SHORT).show()
            pickCategory(activity, "Category", list, NONE) { c ->
                field.setText(c?.label ?: NONE, false)
                choose(c)
            }
        }

        private fun choose(c: WalletApi.Category?) {
            touched = true
            chosen = c?.let { CategoryPick(it.id, it.label, emptyList(), CategorySource.MANUAL) }
            layout.helperText = if (c == null) "Wallet will show Unknown" else "Chosen by you"
            val key = Merchant.memoryKey(payee().orEmpty())
            remember.visibility = if (c != null && key.isNotEmpty()) View.VISIBLE else View.GONE
            remember.text = "Always use this for \"$key\""
        }

        /** The user's choice if they made one, otherwise [auto]. */
        fun result(auto: () -> CategoryPick?): CategoryPick? = if (touched) chosen else auto()

        /** Remembers a hand-picked category for the merchant, if the user left the box ticked. */
        fun commit() {
            val c = chosen ?: return
            // Demo choices would land in the real memory with made-up category ids.
            if (Demo.active) return
            if (touched && remember.visibility == View.VISIBLE && remember.isChecked) {
                Settings(activity).rememberCategory(payee(), c.id, c.name)
            }
        }

        companion object {
            private const val NONE = "No category"

            /** Why this category, in a few words. */
            fun describe(c: CategoryPick) = when (c.source) {
                CategorySource.REMEMBERED -> "your earlier choice for this merchant"
                CategorySource.APP_RULE -> "from your category rules in this app"
                CategorySource.WALLET_RULE -> "from your Wallet rules"
                CategorySource.HISTORY -> "from your past Wallet records"
                else -> "chosen by you"
            }
        }
    }

    /**
     * A category list you can type into to narrow it down ([CategorySearch.filter]). With [noneLabel],
     * that row comes first (while nothing is typed) and picks null.
     */
    fun pickCategory(
        activity: Activity,
        title: String,
        list: List<WalletApi.Category>,
        noneLabel: String?,
        onPick: (WalletApi.Category?) -> Unit,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_search, null)
        val search = view.findViewById<EditText>(R.id.searchField)
        val empty = view.findViewById<TextView>(R.id.searchEmpty)
        val listView = view.findViewById<ListView>(R.id.searchList)
        val labels = list.map { it.label }
        var shown: List<WalletApi.Category?> = emptyList()
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
        listView.adapter = adapter
        fun render() {
            val q = search.text.toString()
            val hits = CategorySearch.filter(labels, q).map { list[it] }
            shown = (if (noneLabel != null && q.isBlank()) listOf(null) else emptyList<WalletApi.Category?>()) + hits
            adapter.clear()
            adapter.addAll(shown.map { it?.label ?: noneLabel })
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }
        render()
        search.doAfterTextChanged { render() }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        listView.setOnItemClickListener { _, _, i, _ ->
            dialog.dismiss()
            onPick(shown[i])
        }
        // Enter with a single match picks it, as a select box would.
        search.setOnEditorActionListener { _, _, _ ->
            if (shown.size == 1) {
                dialog.dismiss()
                onPick(shown[0])
            }
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        search.requestFocus()
    }

    /** "What's new" after an update, or the full changelog from Settings. */
    fun showChangelog(activity: Activity, title: String, releases: List<Release>) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(Changelog.format(releases))
            .setPositiveButton("OK", null)
            .show()
    }

    /** Paste a notification (dummy details) and see exactly how the parser reads it. */
    fun showTest(activity: Activity) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_test, null)
        val result = view.findViewById<TextView>(R.id.testResult)
        val input = view.findViewById<EditText>(R.id.testInput)
        fun update() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return run { result.text = "" }
            val p = Parser.parse(text)
            result.text = if (Settings(activity).skipNotices && Parser.isNotice(text)) {
                "Skipped: looks like a notice, ad or reminder, not a transaction. Turn off \"Skip ads and notices\" to keep these."
            } else if (p == null) {
                "Not understood: no amount found, or can't tell whether money came in or went out."
            } else {
                val pick = WalletCache.pick(activity, text, p.payee, p.amount, "")
                val category = pick?.let { "${it.name} (${CategoryChooser.describe(it)})" }
                val settings = Settings(activity)
                val currency = RuleMatcher.pick(settings.rules(), text)?.accountCurrency.orEmpty()
                val w = Parser.walletText(text, p.payee, currency, settings.includeNote)
                String.format(Locale.US, "Amount: %+.2f %s\n", p.amount, p.currency ?: "") +
                    (if (p.amount > 0) "Direction: income\n" else "Direction: expense\n") +
                    "Payee: ${w.payee ?: "none"}\n" +
                    "Note: ${w.note ?: "none (notes are off)"}\n" +
                    "Category: ${category ?: "none found (Wallet would show Unknown)"}"
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = update()
        })
        MaterialAlertDialogBuilder(activity)
            .setTitle("Test the parser")
            .setView(view)
            .setPositiveButton("Close", null)
            .show()
    }
}
