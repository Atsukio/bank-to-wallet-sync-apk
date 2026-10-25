package app.banksync

import android.content.Context
import android.os.Build

/** One released version: [code] matches `versionCode` in app/build.gradle.kts, [name] its `versionName`. */
class Release(val code: Int, val name: String, val date: String, val changes: List<String>)

/**
 * What changed in each version, newest first, written for the person using the app. Shipped inside
 * the app, so "What's new" works offline. Add an entry here whenever `versionCode` goes up.
 */
object Changelog {
    val releases = listOf(
        Release(
            4, "1.3", "25 Oct 2026",
            listOf(
                "A notification when a bank alert couldn't be read, so it doesn't sit unnoticed in the app.",
                "A silent notification for each transaction picked up, with the amount, account and category, and Change or Don't send buttons until it goes. Turn it off in Settings › Alerts and privacy.",
                "Optional daily summary around 9 pm: how many went to Wallet and anything that needs you.",
                "Optional \"Ask when there's no category\": only those transactions wait for you to pick one.",
                "A bank app with a single rule now uses it even when the alert leaves out the card number, such as for transfers.",
                "When you enter an alert that matched no rule, you can have that account used for the rest of that app's alerts.",
                "Wallet notes keep a shop's full name as the alert wrote it, such as its phone number.",
                "Alerts that couldn't be read now say why on the home screen, such as no bank-app rule matching.",
                "Notifications from the app no longer show amounts or payees on the lock screen.",
            )
        ),
        Release(
            3, "1.2", "24 Oct 2026",
            listOf(
                "Try the app with demo data: made-up bank apps and transactions, nothing read or sent. Start it from the menu or the first setup screen.",
                "Before you turn on notification access, the app now explains exactly what it reads and where it goes.",
                "The battery button now opens the app's own settings page; choose Battery › Unrestricted there.",
                "Transactions now go to Wallet as soon as there's a connection. You can still set a delay in Settings › Uploads.",
                "Fixed Wallet categories showing as \"false\" and looking repeated in the category list.",
            )
        ),
        Release(
            2, "1.1", "24 Oct 2026",
            listOf(
                "Wallet notes now hold just the payment details (who you paid and the reference) instead of the whole alert, or nothing when there are none.",
                "Shops you buy from and people you send money to go in the note; only people who send you money become Wallet payees.",
                "A payment in another currency keeps its original amount in the note, such as \"USD 25.00\".",
                "Bank apps have moved to Settings, so recent activity is higher up on the home screen.",
                "Tap the eye next to Recent activity to hide payees, amounts and categories.",
                "This \"What's new\" message, and the full list under Settings › About.",
            )
        ),
        Release(
            1, "1.0", "Oct 2026",
            listOf(
                "Reads bank-app alerts and adds them to Wallet, with an offline queue so nothing is lost.",
                "Categories from your choices, your rules, your Wallet rules and your past records.",
                "Ask before sending, app lock, and a scan for alerts missed while the app was off.",
            )
        ),
    )

    /** The installed `versionCode` and `versionName`. */
    fun installed(context: Context): Pair<Int, String> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        return code to info.versionName.orEmpty()
    }

    /** Releases newer than [seen] up to [installed]; what the user hasn't been told about yet. */
    fun since(seen: Int, installed: Int): List<Release> = releases.filter { it.code in (seen + 1)..installed }

    fun format(list: List<Release>): String = list.joinToString("\n\n") { r ->
        "${r.name} · ${r.date}\n" + r.changes.joinToString("\n") { "• $it" }
    }
}
