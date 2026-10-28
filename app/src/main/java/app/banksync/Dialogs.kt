package app.banksync

import android.app.Activity
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
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
     * A Touch 'n Go Cash in held because no GO+ account is linked: offers to ignore every future one (and
     * drop this one), or to enter this one by hand through [showFix].
     */
    fun showCashIn(activity: Activity, rec: Rec, onDone: () -> Unit) {
        val settings = Settings(activity)
        val store = Store.shown(activity)
        val linked = settings.rulesFor(rec.pkg)?.firstOrNull(GoPlus::isGoPlus)

        /** Queues this Cash in as a transfer into [go], and stops ignoring Cash ins. */
        fun move(go: Rule) {
            val amount = Parser.cashIn(rec.note.orEmpty())?.amount
                ?: return Toast.makeText(activity, "Couldn't read the amount", Toast.LENGTH_LONG).show()
            settings.cashInIgnored = false
            store.makeTransfer(rec.id, rec.account, amount, go.accountId)
            Uploader.schedule(activity, 0, replace = true)
            onDone()
        }

        /** Lets the user pick one of Wallet's GO+ accounts, links it to the app, and moves this Cash in there. */
        fun link() {
            val error = { msg: String -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }
            Pickers.withAccounts(activity, settings.token, error) { all ->
                val taken = settings.rules().map { it.accountId }.toSet()
                val goPlus = all.filter { GoPlus.isGoPlus(it.name) && it.id !in taken }
                if (goPlus.isEmpty()) return@withAccounts error("No GO+ account left to link in your Wallet")
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Which GO+ account?")
                    .setItems(goPlus.map { it.label }.toTypedArray()) { _, i ->
                        val a = goPlus[i]
                        val label = settings.rulesFor(rec.pkg)?.firstOrNull()?.label ?: "Touch 'n Go"
                        val rule = Rule(rec.pkg, label, "", a.id, a.name, a.currency)
                        settings.addRule(rule)
                        move(rule)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Ignore Cash in alerts?")
            .setMessage(
                (if (linked != null) "A GO+ account is linked now, so this Cash in can move into ${linked.accountName}.\n\n" else
                    "This Cash in has nowhere to go: no GO+ account is linked to the app.\n\n") +
                    "Ignore all future Cash in alerts and drop this one? You can change that in Settings, and you'll be asked again if you link a GO+ account."
            )
            .setPositiveButton("Ignore Cash ins") { _, _ ->
                settings.cashInIgnored = true
                store.delete(rec.id)
                if (store.counts()[Store.UNPARSED] == 0) Notifier.cancelUnparsed(activity)
                onDone()
            }
            .setNegativeButton("Enter it myself") { _, _ -> showFix(activity, rec, onDone) }
            .setNeutralButton(if (linked != null) "Move to ${linked.accountName}" else "Link a GO+ account") { _, _ ->
                if (linked != null) move(linked) else link()
            }
            .show()
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

        // An app with several accounts, all needing match text and none the default: offer to make the
        // chosen account its default, so the next alert like this one goes straight through.
        val appRules = if (Demo.active) null else settings.rulesFor(rec.pkg)?.filterNot(GoPlus::isGoPlus)
        val remember = view.findViewById<CheckBox>(R.id.fixRemember)
        if (appRules != null && appRules.size > 1 && appRules.none { it.match.isEmpty() || it.isDefault }) {
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
                val defaultFor = appRules?.takeIf { remember.visibility == View.VISIBLE && remember.isChecked }
                if (defaultFor != null) {
                    // The account's own link becomes the default; an account the app isn't linked to yet gets one.
                    if (defaultFor.any { it.accountId == target.id }) settings.setDefault(rec.pkg, target.id)
                    else settings.addRule(Rule(rec.pkg, defaultFor[0].label, "", target.id, target.name, target.currency, isDefault = true))
                }
                Store.shown(activity).fix(rec.id, target.id, signed, payee, category)
                if (!Demo.active) Uploader.schedule(activity, 0, replace = true)
                onDone()
                // Other alerts held for the same reason can now go through too.
                if (defaultFor != null) Pickers.recheckHeld(activity, rec.pkg, defaultFor[0].label) { onDone() }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                Store.shown(activity).delete(rec.id)
                onDone()
            }
            .create()
        dialog.setOnShowListener { refresh() }
        dialog.show()
        dialog.danger(AlertDialog.BUTTON_NEUTRAL)
    }

    /**
     * Read-only details of any record in Recent activity: what Wallet has or will get (payee or payer,
     * note, category, account) and where it stands. [partner] is the other half of a transfer. A record
     * that hasn't been sent also gets a button that calls [onEdit] with the half to open.
     */
    fun showDetails(activity: Activity, rec: Rec, partner: Rec?, onEdit: (Rec) -> Unit) {
        val settings = Settings(activity)
        val rules = Demo.rules(settings)
        val names = rules.associate { it.accountId to it.accountName }
        val currencies = rules.associate { it.accountId to it.accountCurrency }
        val inflater = LayoutInflater.from(activity)
        val list = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 8.dp(activity))
        }
        /** One labelled line; [color], a colour resource, tints the value and its icon as on the row. */
        fun add(icon: Int, label: String, value: String?, color: Int? = null) {
            if (value.isNullOrBlank()) return
            val item = inflater.inflate(R.layout.item_detail, list, false)
            item.findViewById<ImageView>(R.id.detailIcon).apply {
                setImageResource(icon)
                if (color != null) imageTintList = ColorStateList.valueOf(activity.getColor(color))
            }
            item.findViewById<TextView>(R.id.detailLabel).text = label
            item.findViewById<TextView>(R.id.detailValue).apply {
                text = value
                if (color != null) setTextColor(activity.getColor(color))
            }
            list.addView(item)
        }
        fun account(id: String?) = id?.let { names[it] ?: settings.accountName(it) }

        val transfer = partner != null || rec.transferTo != null
        // A transfer's outgoing half stands for both, as on the row.
        val main = if (partner != null && (rec.amount ?: 0.0) > 0) partner else rec
        val amount = main.amount ?: 0.0
        val money = String.format(Locale.US, "%,.2f", kotlin.math.abs(amount))
        when {
            // A "not understood" alert has no amount until it is fixed; don't show it as 0.00.
            main.amount == null -> add(R.drawable.ic_help, "Amount", "Not read from the alert")
            transfer -> add(R.drawable.ic_swap, "Amount", money, R.color.mark_transfer)
            amount > 0 -> add(R.drawable.ic_arrow_in, "Amount", "+$money", R.color.amount_in)
            else -> add(R.drawable.ic_arrow_out, "Amount", "−$money", R.color.amount_out)
        }
        if (transfer) {
            val into = partner?.let { if ((rec.amount ?: 0.0) > 0) rec else it }
            add(R.drawable.ic_wallet, "From", account(main.account) ?: "Account no longer linked")
            add(R.drawable.ic_wallet, "To", account(into?.account ?: main.transferTo) ?: "Another Wallet account")
        } else {
            add(R.drawable.ic_person, if (amount > 0) "Payer" else "Payee", main.payee?.takeIf { it.isNotBlank() } ?: "None")
            add(R.drawable.ic_wallet, "Wallet account", account(main.account) ?: "Account no longer linked")
        }
        if (!transfer && main.status != Store.UNPARSED) {
            add(R.drawable.ic_label, "Category", main.categoryName ?: "None: Wallet files it as Unknown")
            val note = main.noteEdit
                ?: Parser.walletText(main.note.orEmpty(), main.payee, currencies[main.account].orEmpty(), settings.includeNote).note
            add(R.drawable.ic_notes, "Note", note?.takeIf { it.isNotBlank() } ?: "None")
        }
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        add(R.drawable.ic_event, "When", iso.parse(main.date)?.let { java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(it) } ?: main.date)
        val waiting = listOfNotNull(rec, partner).firstOrNull { it.status != Store.SYNCED }
        val shown = waiting ?: rec
        // Same icons and colours as the row's badge: blue waiting, amber needs you, red failed, green sent.
        when (shown.status) {
            Store.PENDING -> add(R.drawable.ic_schedule, "Status", "Waiting to send", R.color.mark_wait)
            Store.REVIEW -> add(R.drawable.ic_schedule, "Status", "Waiting for your approval", R.color.status_warn)
            Store.FAILED -> add(R.drawable.ic_error, "Status", "Couldn't be sent: ${shown.error ?: "unknown error"}", R.color.status_bad)
            Store.UNPARSED -> add(R.drawable.ic_help, "Status", shown.error ?: "Not understood", R.color.status_warn)
            else -> add(R.drawable.ic_check_circle, "Status", "Sent to Wallet", R.color.status_good)
        }
        // The alert itself is kept until it's sent; it helps with a record that wasn't understood.
        if (main.status == Store.UNPARSED || main.status == Store.FAILED) add(R.drawable.ic_notifications, "Bank alert", main.note)

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (transfer) "Transfer" else "Transaction")
            .setView(android.widget.ScrollView(activity).apply {
                setPadding(24.dp(activity), 0, 24.dp(activity), 0)
                addView(list)
            })
            .setNegativeButton("Close", null)
        if (waiting != null) {
            builder.setPositiveButton(
                when (waiting.status) {
                    Store.UNPARSED -> "Fix"
                    Store.REVIEW -> "Review"
                    Store.FAILED -> "Retry or discard"
                    else -> "Edit"
                }
            ) { _, _ -> onEdit(waiting) }
        }
        builder.show()
    }

    private fun Int.dp(activity: Activity) = (this * activity.resources.displayMetrics.density).toInt()

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
        // Wallet files who it was with as the payee of an expense or the payer of income.
        val role = if (amount > 0) "Payer" else "Payee"
        view.findViewById<TextView>(R.id.recordWhat).text =
            "$role: " + (rec.payee?.takeIf { it.isNotBlank() } ?: "none")

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

        // Money out can be turned into a transfer to another Wallet account; Wallet files that itself, so no category.
        var transferTo: String? = rec.transferTo
        val canTransfer = amount < 0 && rec.pairId == null
        if (canTransfer) {
            val layout = view.findViewById<View>(R.id.transferLayout)
            val field = view.findViewById<AutoCompleteTextView>(R.id.recordTransfer)
            layout.visibility = View.VISIBLE
            val notTransfer = "Not a transfer"
            var choices: List<Target> = targets.filter { it.id != rec.account }
            val knownName = transferTo?.let { settings.accountName(it) }
            fun showChoices() {
                field.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, listOf(notTransfer) + choices.map { it.label }))
            }
            fun categoryVisible() {
                view.findViewById<View>(R.id.categoryLayout).visibility = if (transferTo == null) View.VISIBLE else View.GONE
            }
            field.setText(choices.firstOrNull { it.id == transferTo }?.label ?: knownName ?: if (transferTo == null) notTransfer else "Another Wallet account", false)
            showChoices()
            field.setOnItemClickListener { _, _, i, _ ->
                transferTo = if (i == 0) null else choices[i - 1].id
                categoryVisible()
            }
            categoryVisible()
            // Any Wallet account, not only the linked ones.
            if (!Demo.active && settings.token.isNotEmpty()) {
                Thread {
                    val all = runCatching { WalletApi.accounts(settings.token) }.getOrNull()
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed || all.isNullOrEmpty()) return@runOnUiThread
                        settings.rememberAccountNames(all)
                        choices = all.filter { it.id != rec.account }.map { Target(it.id, it.name, it.currency) }
                        showChoices()
                        choices.firstOrNull { it.id == transferTo }?.let { field.setText(it.label, false) }
                    }
                }.start()
            }
        }

        /** Saves the changes; false (with a message) when the record was sent meanwhile. */
        fun save(): Boolean {
            val toAccount = transferTo?.takeIf { canTransfer && it != target.id }
            if (toAccount == null) chooser.commit()
            // Left as it was shown, the note keeps following the alert; anything else is sent as typed.
            val typed = noteField.text.toString().trim()
            val noteEdit = if (rec.noteEdit == null && typed == autoNote) null else typed
            val category = if (toAccount != null) null else chooser.result { initial }
            if (store.edit(rec.id, target.id, category, noteEdit, toAccount)) return true
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
        val dialog = builder.show()
        if (rec.status == Store.REVIEW) dialog.danger(AlertDialog.BUTTON_NEGATIVE)
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
