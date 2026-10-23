package app.banksync

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

object Dialogs {

    /**
     * Lets the user finish a "not understood" alert by hand: the original text is shown, the amount
     * is pre-filled when one could be found, and the result joins the normal upload queue.
     */
    fun showFix(activity: Activity, rec: Rec, onDone: () -> Unit) {
        val settings = Settings(activity)
        val account = rec.account.ifEmpty { settings.rulesFor(rec.pkg)?.firstOrNull()?.accountId.orEmpty() }
        if (account.isEmpty()) {
            Toast.makeText(activity, "Add this bank app under Bank apps first", Toast.LENGTH_LONG).show()
            return
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_fix, null)
        val text = rec.note.orEmpty()
        view.findViewById<TextView>(R.id.fixOriginal).text = "\"$text\""
        view.findViewById<TextView>(R.id.fixReason).text = rec.error.orEmpty()
        val amountField = view.findViewById<EditText>(R.id.fixAmount)
        Parser.amountOf(text)?.let { amountField.setText(String.format(Locale.US, "%.2f", it)) }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Enter this transaction")
            .setView(view)
            .setPositiveButton("Queue") { _, _ ->
                val amount = amountField.text.toString().replace(",", "").toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(activity, "Enter an amount above zero", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val income = view.findViewById<RadioButton>(R.id.fixIncome).isChecked
                val payee = view.findViewById<EditText>(R.id.fixPayee).text.toString().trim().ifEmpty { null }
                val category = CategoryMatcher.pick(settings.categoryRules(), text)?.categoryId
                Store.get(activity).fix(rec.id, account, if (income) amount else -amount, payee, category)
                Uploader.schedule(activity, 0, replace = true)
                onDone()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                Store.get(activity).delete(rec.id)
                onDone()
            }
            .show()
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
