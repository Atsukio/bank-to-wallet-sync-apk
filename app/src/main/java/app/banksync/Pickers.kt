package app.banksync

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The add-a-bank-app and add-a-category flows plus the rule lists, shared by the main screen and setup guide. */
object Pickers {
    /** Banks with a parser: CIMB OCTO, Ryt Bank, Touch 'n Go eWallet, Maybank2u / MAE. */
    private val SUPPORTED = Regex("""cimb|octo|\bryt\b|touch|\btng\b|maybank|\bmae\b|\bm2u""", RegexOption.IGNORE_CASE)

    /**
     * True when a Wallet account's name mentions the bank behind [appLabel]. Apps that aren't one of
     * the supported banks get no suggestions.
     */
    private fun matchesBank(appLabel: String, accountName: String): Boolean {
        val label = appLabel.lowercase()
        val names = when {
            "cimb" in label || "octo" in label -> listOf("cimb", "octo")
            Regex("""\bryt\b""").containsMatchIn(label) -> listOf("ryt")
            "touch" in label || Regex("""\btng\b""").containsMatchIn(label) -> listOf("tng", "touch")
            Regex("""maybank|\bmae\b|\bm2u""").containsMatchIn(label) -> listOf("maybank", "mae", "m2u")
            else -> return false
        }
        return names.any { Regex("""\b${Regex.escape(it)}""", RegexOption.IGNORE_CASE).containsMatchIn(accountName) }
    }

    /** Loads [load] off the main thread and hands the result to [onLoaded] on it, unless the screen has closed. */
    private fun <T> background(activity: Activity, onError: (String) -> Unit, load: () -> T, onLoaded: (T) -> Unit) {
        Thread {
            val result = runCatching(load)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess(onLoaded).onFailure { onError("Could not load from Wallet: ${it.message}") }
            }
        }.start()
    }

    /**
     * Shows [label] on [button] and disables it until the returned function is called, so the user
     * sees something is happening and can't start the same slow load twice.
     */
    private fun busy(button: Button?, label: String): () -> Unit {
        if (button == null) return {}
        val text = button.text
        button.isEnabled = false
        button.text = label
        return {
            button.isEnabled = true
            button.text = text
        }
    }

    /** Launchable apps as (label, package), sorted by label. Loading labels is slow, so call it off the main thread. */
    private fun launcherApps(activity: Activity): List<Pair<String, String>> {
        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { it.second != activity.packageName }
            .sortedBy { it.first.lowercase() }
    }

    fun withAccounts(
        activity: Activity, token: String,
        onError: (String) -> Unit, onLoaded: (List<WalletApi.Account>) -> Unit,
    ) {
        if (token.isEmpty()) return onError("Enter your Wallet API token first")
        background(activity, onError, { WalletApi.accounts(token) }, onLoaded)
    }

    /**
     * One dialog with three dropdown fields: the bank app, the Wallet account, and an optional card or
     * account ending. The app comes from the phone's own list so the package name is exact; the accounts
     * come from Wallet so no IDs are typed. Alerts are read the same way for every bank: purchases,
     * payments and money received. [trigger] shows a loading state while the accounts load.
     */
    fun addRule(activity: Activity, token: String, trigger: Button?, onAdded: (Rule) -> Unit, onError: (String) -> Unit) {
        if (token.isEmpty()) return onError("Enter your Wallet API token first, so your accounts can be listed")
        val done = busy(trigger, "Loading accounts…")
        val fail = { msg: String -> done(); onError(msg) }

        background(activity, fail, { launcherApps(activity) to WalletApi.accounts(token) }) { (all, allAccounts) ->
            done()
            if (allAccounts.isEmpty()) return@background onError("No Wallet accounts found")
            // Accounts already linked to a bank app aren't offered again; removing the link frees them.
            val linked = Settings(activity).rules().map { it.accountId }.toSet()
            val accounts = allAccounts.filter { it.id !in linked }
            if (accounts.isEmpty()) {
                return@background onError("Every Wallet account is already linked. Remove one under Bank apps to link it again.")
            }
            // Only the banks this app can read are offered; "Other app…" switches the list to everything installed.
            val supported = all.filter { (name, _) -> SUPPORTED.containsMatchIn(name) }
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_rule, null)
            val appField = view.findViewById<AutoCompleteTextView>(R.id.ruleApp)
            val accountField = view.findViewById<AutoCompleteTextView>(R.id.ruleAccount)
            val matchField = view.findViewById<EditText>(R.id.ruleMatch)

            var app: Pair<String, String>? = null
            var account: WalletApi.Account? = null
            var showingAll = supported.isEmpty()
            var filled = ""
            lateinit var dialog: AlertDialog
            fun refresh() {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = app != null && account != null
            }

            var shown = accounts
            fun setAccountChoices(list: List<WalletApi.Account>) {
                shown = list
                accountField.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, list.map { it.label }))
            }
            fun pickAccount(a: WalletApi.Account) {
                account = a
                accountField.setText(a.label, false)
                // Fill the ending from Wallet, but never overwrite something the user typed themselves.
                val text = matchField.text.toString()
                if (text.isEmpty() || text == filled) {
                    filled = a.ending
                    matchField.setText(filled)
                }
                refresh()
            }
            setAccountChoices(accounts)
            accountField.setOnItemClickListener { _, _, i, _ -> pickAccount(shown[i]) }

            fun setAppChoices() {
                val names = if (showingAll) all.map { it.first } else supported.map { it.first } + "Other app…"
                appField.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, names))
            }
            setAppChoices()
            appField.setOnItemClickListener { _, _, i, _ ->
                val list = if (showingAll) all else supported
                if (i < list.size) {
                    app = list[i]
                    appField.setText(list[i].first, false)
                    // Accounts named like the bank go to the top of the list; a single match is picked for them.
                    val matches = accounts.filter { matchesBank(list[i].first, it.name) }
                    setAccountChoices(matches + (accounts - matches.toSet()))
                    if (account == null && matches.size == 1) pickAccount(matches[0])
                } else {
                    showingAll = true
                    app = null
                    appField.setText("", false)
                    setAppChoices()
                    appField.post { appField.showDropDown() }
                }
                refresh()
            }
            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle("Add a bank app")
                .setView(view)
                .setPositiveButton("Add", null)
                .setNegativeButton("Cancel", null)
                .create()
            dialog.setOnShowListener {
                refresh()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val (label, pkg) = app ?: return@setOnClickListener
                    val acc = account ?: return@setOnClickListener
                    val rule = Rule(pkg, label, matchField.text.toString().trim(), acc.id, acc.name, acc.currency)
                    Settings(activity).addRule(rule)
                    onAdded(rule)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }

    /** "When the alert contains X, use this Wallet category." */
    fun addCategoryRule(activity: Activity, token: String, trigger: Button?, onAdded: () -> Unit, onError: (String) -> Unit) {
        if (token.isEmpty()) return onError("Enter your Wallet API token first, so your categories can be listed")
        val input = EditText(activity).apply {
            hint = "e.g. grab"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val pad = (24 * activity.resources.displayMetrics.density).toInt()
        val frame = FrameLayout(activity).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("When the alert contains…")
            .setMessage("Text from the alert, such as a shop or payee name. It isn't case sensitive.")
            .setView(frame)
            .setPositiveButton("Next") { _, _ ->
                val contains = input.text.toString().trim()
                if (contains.isEmpty()) return@setPositiveButton onError("Enter some text first")
                val done = busy(trigger, "Loading categories…")
                val fail = { msg: String -> done(); onError(msg) }
                background(activity, fail, { WalletApi.categories(token) }) { categories ->
                    done()
                    if (categories.isEmpty()) return@background onError("No categories found in Wallet")
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Use which category?")
                        .setItems(categories.map { it.label }.toTypedArray()) { _, i ->
                            Settings(activity).addCategoryRule(CategoryRule(contains, categories[i].id, categories[i].label))
                            onAdded()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Fills [container] with one row per rule, each with a Remove button. */
    fun renderRules(activity: Activity, container: ViewGroup, settings: Settings, onChanged: () -> Unit = {}) {
        container.removeAllViews()
        val rules = settings.rules()
        if (rules.isEmpty()) {
            container.addView(TextView(activity).apply { text = "No bank apps yet. Tap \"Add bank app\"." })
            return
        }
        for (rule in rules) {
            val sub = if (rule.match.isEmpty()) "All alerts from this app" else "Only alerts containing \"${rule.match}\""
            addRow(activity, container, "${rule.label} → ${rule.accountName}", sub) {
                settings.removeRule(rule)
                renderRules(activity, container, settings, onChanged)
                onChanged()
            }
        }
    }

    fun renderCategoryRules(activity: Activity, container: ViewGroup, settings: Settings, onChanged: () -> Unit = {}) {
        container.removeAllViews()
        val rules = settings.categoryRules()
        if (rules.isEmpty()) {
            container.addView(TextView(activity).apply { text = "No category rules yet." })
            return
        }
        for (rule in rules) {
            addRow(activity, container, "\"${rule.contains}\" → ${rule.categoryName}", "Applies to alerts containing this text") {
                settings.removeCategoryRule(rule)
                renderCategoryRules(activity, container, settings, onChanged)
                onChanged()
            }
        }
    }

    private fun addRow(activity: Activity, container: ViewGroup, title: String, sub: String, onRemove: () -> Unit) {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_rule, container, false)
        row.findViewById<TextView>(R.id.ruleTitle).text = title
        row.findViewById<TextView>(R.id.ruleSub).text = sub
        row.findViewById<Button>(R.id.ruleRemove).setOnClickListener { onRemove() }
        container.addView(row)
    }
}
