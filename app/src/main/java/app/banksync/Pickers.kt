package app.banksync

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The add-a-bank-app and add-a-category flows plus the rule lists, shared by the main screen and setup guide. */
object Pickers {
    /** Banks with a parser: CIMB OCTO, Ryt Bank, Touch 'n Go eWallet, Maybank2u / MAE, ShopeePay (in the Shopee app). */
    private val SUPPORTED = Regex("""cimb|octo|\bryt\b|touch|\btng\b|maybank|\bmae\b|\bm2u|shopee""", RegexOption.IGNORE_CASE)

    /**
     * True when a Wallet account's name mentions the bank behind [appLabel]. Apps that aren't one of
     * the supported banks get no suggestions.
     */
    private fun matchesBank(appLabel: String, accountName: String): Boolean {
        val label = appLabel.lowercase()
        val names = when {
            "cimb" in label || "octo" in label -> listOf("cimb", "octo")
            Regex("""\bryt\b""").containsMatchIn(label) -> listOf("ryt")
            "touch" in label || Regex("""\btng\b""").containsMatchIn(label) -> listOf("tng", "touch", "go+")
            Regex("""maybank|\bmae\b|\bm2u""").containsMatchIn(label) -> listOf("maybank", "mae", "m2u")
            "shopee" in label -> listOf("shopee", "spay")
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
        val done = busy(trigger, "Loading…")
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
            // A different app means a different bank, so the account and ending chosen for the old one go.
            fun clearAccount() {
                account = null
                accountField.setText("", false)
                matchField.setText("")
                filled = ""
            }
            appField.setOnItemClickListener { _, _, i, _ ->
                val list = if (showingAll) all else supported
                if (i < list.size) {
                    if (app != list[i]) clearAccount()
                    app = list[i]
                    appField.setText(list[i].first, false)
                    // Accounts named like the bank go to the top of the list; a single match is picked for them.
                    val matches = accounts.filter { matchesBank(list[i].first, it.name) }
                    setAccountChoices(matches + (accounts - matches.toSet()))
                    if (account == null && matches.size == 1) pickAccount(matches[0])
                } else {
                    showingAll = true
                    app = null
                    clearAccount()
                    setAccountChoices(accounts)
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
                    val typed = Rule(pkg, label, matchField.text.toString().trim(), acc.id, acc.name, acc.currency)
                    // A GO+ account takes Cash ins, not alerts by text, so whatever filled the ending is dropped.
                    val rule = if (GoPlus.isGoPlus(typed)) typed.copy(match = "") else typed
                    Settings(activity).addRule(rule)
                    onAdded(rule)
                    dialog.dismiss()
                    cashInFollowUp(activity, rule, allAccounts, onAdded)
                }
            }
            dialog.show()
        }
    }

    /**
     * Touch 'n Go Cash ins move money into a GO+ account, so linking the app (or a GO+ account) is when to
     * settle where they go. A main account with no GO+ linked offers the Wallet's GO+ accounts, or asks to
     * ignore Cash ins when there are none. A GO+ account linked while they are ignored asks to start using it.
     */
    private fun cashInFollowUp(activity: Activity, rule: Rule, accounts: List<WalletApi.Account>, onAdded: (Rule) -> Unit) {
        if (!GoPlus.isTng(rule.label)) return
        val settings = Settings(activity)
        if (GoPlus.isGoPlus(rule)) {
            if (!settings.cashInIgnored) return
            MaterialAlertDialogBuilder(activity)
                .setTitle("Move Cash ins into ${rule.accountName}?")
                .setMessage("Touch 'n Go Cash in alerts are being ignored. Start moving them from your Touch 'n Go account into ${rule.accountName} instead?")
                .setPositiveButton("Move them") { _, _ -> settings.cashInIgnored = false }
                .setNegativeButton("Keep ignoring", null)
                .show()
            return
        }
        if (settings.rules().any { it.pkg == rule.pkg && GoPlus.isGoPlus(it) }) return
        val linked = settings.rules().map { it.accountId }.toSet()
        val goAccounts = accounts.filter { GoPlus.isGoPlus(it.name) && it.id !in linked }
        if (goAccounts.isEmpty()) {
            if (settings.cashInIgnored) return
            MaterialAlertDialogBuilder(activity)
                .setTitle("Ignore Cash in alerts?")
                .setMessage(
                    "Your Wallet has no GO+ account, so Touch 'n Go Cash in alerts have nowhere to go. Ignore them? " +
                        "You can change this in Settings, and you'll be asked again if you link a GO+ account later."
                )
                .setPositiveButton("Ignore them") { _, _ -> settings.cashInIgnored = true }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        val choices = (goAccounts.map { it.label } + "None: ignore Cash ins").toTypedArray()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Which GO+ account do Cash ins go into?")
            .setItems(choices) { _, i ->
                if (i < goAccounts.size) {
                    val a = goAccounts[i]
                    val go = Rule(rule.pkg, rule.label, "", a.id, a.name, a.currency)
                    settings.addRule(go)
                    settings.cashInIgnored = false
                    onAdded(go)
                } else {
                    settings.cashInIgnored = true
                }
            }
            .setNegativeButton("Not now", null)
            .show()
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
                val done = busy(trigger, "Loading…")
                val fail = { msg: String -> done(); onError(msg) }
                background(activity, fail, { WalletApi.categories(token).filter { it.id !in Settings(activity).refusedCategories() } }) { categories ->
                    done()
                    if (categories.isEmpty()) return@background onError("No categories found in Wallet")
                    Dialogs.pickCategory(activity, "Use which category?", categories, null) { c ->
                        c ?: return@pickCategory
                        Settings(activity).addCategoryRule(CategoryRule(contains, c.id, c.label))
                        onAdded()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Fills [container] with one row per rule: the app's own icon, where its alerts go, and a remove button. */
    fun renderRules(activity: Activity, container: ViewGroup, settings: Settings, onChanged: () -> Unit = {}) {
        container.removeAllViews()
        val rules = settings.rules()
        if (rules.isEmpty()) {
            return addEmpty(activity, container, "No bank apps yet. Tap Add to choose one.")
        }
        for (rule in rules) {
            val sub = if (GoPlus.isGoPlus(rule)) "Cash ins go into ${rule.accountName}" else "To ${rule.accountName}" +
                if (rule.match.isEmpty()) "" else " · alerts containing \"${rule.match}\""
            val row = addRow(activity, container, rule.label, sub) {
                settings.removeRule(rule)
                renderRules(activity, container, settings, onChanged)
                onChanged()
            }
            val icon = runCatching { activity.packageManager.getApplicationIcon(rule.pkg) }.getOrNull()
            row.findViewById<ImageView>(R.id.ruleIcon).apply {
                if (icon != null) {
                    // A real app icon is shown as is, without the tinted badge.
                    setImageDrawable(icon)
                    background = null
                    imageTintList = null
                    setPadding(0, 0, 0, 0)
                } else {
                    setImageResource(R.drawable.ic_apps)
                }
            }
        }
    }

    fun renderCategoryRules(activity: Activity, container: ViewGroup, settings: Settings, onChanged: () -> Unit = {}) {
        container.removeAllViews()
        val rules = settings.categoryRules()
        if (rules.isEmpty()) {
            return addEmpty(activity, container, "No category rules yet.")
        }
        for (rule in rules) {
            val row = addRow(activity, container, rule.categoryName, "When the alert contains \"${rule.contains}\"") {
                settings.removeCategoryRule(rule)
                renderCategoryRules(activity, container, settings, onChanged)
                onChanged()
            }
            row.findViewById<ImageView>(R.id.ruleIcon).setImageResource(R.drawable.ic_label)
        }
    }

    private fun addRow(activity: Activity, container: ViewGroup, title: String, sub: String, onRemove: () -> Unit): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_rule, container, false)
        row.findViewById<TextView>(R.id.ruleTitle).text = title
        row.findViewById<TextView>(R.id.ruleSub).text = sub
        row.findViewById<Button>(R.id.ruleRemove).apply {
            contentDescription = "Remove $title"
            setOnClickListener { onRemove() }
        }
        container.addView(row)
        return row
    }

    /** A quiet placeholder line for a list with nothing in it yet. */
    fun addEmpty(activity: Activity, container: ViewGroup, message: String) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        container.addView(TextView(activity).apply {
            text = message
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
    }
}
