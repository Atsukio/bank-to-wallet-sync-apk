package app.banksync

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
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
        val rules = settings.rules().sortedBy { if (it.pkg == rec.pkg) 0 else 1 }
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
                val income = view.findViewById<RadioButton>(R.id.fixIncome).isChecked
                val payee = view.findViewById<EditText>(R.id.fixPayee).text.toString().trim().ifEmpty { null }
                val category = CategoryMatcher.pick(settings.categoryRules(), text)?.categoryId
                Store.get(activity).fix(rec.id, target.id, if (income) amount else -amount, payee, category)
                Uploader.schedule(activity, 0, replace = true)
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                Store.get(activity).delete(rec.id)
                onDone()
            }
            .create()
        dialog.setOnShowListener { refresh() }
        dialog.show()
    }

    /** Paste a notification (dummy details) and see exactly how the parser reads it. */
    fun showTest(activity: Activity) {
        val settings = Settings(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_test, null)
        val result = view.findViewById<TextView>(R.id.testResult)
        val input = view.findViewById<EditText>(R.id.testInput)
        fun update() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return run { result.text = "" }
            val p = Parser.parse(text)
            result.text = if (p == null) {
                "Not understood: no amount found, or can't tell whether money came in or went out."
            } else {
                val category = CategoryMatcher.pick(settings.categoryRules(), text)?.categoryName
                String.format(Locale.US, "Amount: %+.2f %s\n", p.amount, p.currency ?: "") +
                    (if (p.amount > 0) "Direction: income\n" else "Direction: expense\n") +
                    "Payee: ${p.payee ?: "none found"}\n" +
                    "Category: ${category ?: "none (no category rule matches)"}"
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
