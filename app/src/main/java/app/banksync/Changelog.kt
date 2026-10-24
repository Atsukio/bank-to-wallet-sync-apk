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
