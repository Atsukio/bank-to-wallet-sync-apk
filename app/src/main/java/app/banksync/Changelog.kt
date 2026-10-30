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
            12, "1.6", "30 Oct 2026",
            listOf(
                "Hold a transaction in Recent activity for a menu: send it now, change it, hold it back, retry, see its details or remove it. A tip under Recent activity points it out until you've used it.",
                "While that menu is open nothing is sent to Wallet, so a transaction can't go before you've decided. Sending carries on when you close it, or after your batch delay passes without a tap. With the batch delay at 0, nothing is paused.",
                "Edit in Wallet: change the amount, payee, category or note of a transaction already sent. Your change replaces what Wallet has, and waits for a connection like everything else. Works for transactions sent from this version on, and for most sent since 1.4.",
                "New installs wait 2 minutes before sending, so a burst of alerts goes together and there's time to change one. Change it in Settings › Uploads (0 sends straight away).",
            )
        ),
        Release(
            11, "1.5.5", "30 Oct 2026",
            listOf(
                "Fixed shops with a dot in their name, such as a website (\"Sample.com\"), being cut short at the dot.",
            )
        ),
        Release(
            10, "1.5.4", "29 Oct 2026",
            listOf(
                "Bank notifications with no amount in them are skipped instead of showing as not understood. Turn off \"Skip ads and notices\" in Settings to keep them.",
            )
        ),
        Release(
            9, "1.5.3", "29 Oct 2026",
            listOf(
                "More bank ads are skipped, such as Ryt's \"keep earning interest\" reminders, instead of showing as not understood.",
                "Delete an alert the app couldn't read straight from its details, without opening Fix first.",
            )
        ),
        Release(
            8, "1.5.2", "28 Oct 2026",
            listOf(
                "Checked that transfer alerts are read the same whether they say DuitNow, Instant Transfer, IBG or another transfer method.",
            )
        ),
        Release(
            7, "1.5.1", "28 Oct 2026",
            listOf(
                "Fixed the details of an alert the app couldn't read showing its amount as −0.00. It now says the amount wasn't read.",
            )
        ),
        Release(
            6, "1.5", "28 Oct 2026",
            listOf(
                "Touch 'n Go Cash ins to GO+ become a transfer from your Touch 'n Go account to your GO+ account in Wallet. No GO+ account in Wallet? The app offers to ignore Cash ins instead, and Settings has a switch for it.",
                "A bank app with several accounts can have a default: tap one of its accounts in Bank apps (or tick the box when adding one) and alerts that name none of your accounts, such as some transfers, go there. Alerts naming an account ending you haven't linked are still held for you, and now say which ending it was.",
                "Alerts held because no account matched are tried again as soon as you link an account or set a default, so you don't have to fix them by hand.",
                "Fixed a transfer getting stuck waiting for you when its first side had no category.",
                "Tap anything in Recent activity to see its details: payee, account, category, note and status, with Fix, Review, Edit and Retry for ones not sent yet. Rows show the note Wallet will get.",
                "Edit the note Wallet gets while a record waits to send. Clear it to send no note.",
                "Shops and people you pay now go in Wallet's payee field, like people who send you money. The note repeats the name only when the alert gives a fuller one.",
                "ShopeePay alerts from the Shopee app are read, and more ads (promo codes, \"chip in\" offers) are skipped.",
                "Delete and drop buttons are red, and the home screen and Settings have icons.",
            )
        ),
        Release(
            5, "1.4", "25 Oct 2026",
            listOf(
                "Transfers between your own accounts: money out of one bank and the same amount into another within 5 minutes becomes one Wallet transfer, instead of an expense and an income. Tap one that's still waiting and choose \"Not a transfer\" if it got it wrong.",
                "\"Skip money Wallet already has\" is gone; matching transfers replaces it.",
                "A transfer between your accounts is one row in Recent activity (amount without a sign, \"Ryt → CIMB\" underneath) instead of two. Tap it (it has a small arrow) to open both sides underneath, and tap a side that is still waiting to change it; hold it to remove both.",
                "The send button says what it will do (\"Send 3 waiting\", greyed out as \"Nothing to send\" when nothing is waiting) and shows what it is doing: \"Waiting for network…\" or \"Sending…\" with a moving bar, then says how many were sent. Recent activity and the counts now update by themselves as uploads and new alerts land, without leaving the screen.",
                "Recent activity shows each bank's own icon with a clear status badge on its corner: green arrow for money in, red for money out, blue clock for waiting, amber for needs you, dark red for failed, purple for a transfer. Amounts are green when money comes in and red when it goes out.",
                "When everything is working, the status card at the top shrinks to its icon and \"Watching 2 bank apps\". Problems still show the full card with a button.",
                "The home screen now moves when things change: the status card morphs and fades its colour, the counts tick up or down with a bump, new activity rows drop in, and a row that changes state (waiting to sent) flashes and pops its badge. Turning off animations in Android settings turns these off.",
                "A \"Scan notifications\" button next to the send button scans for bank alerts still in your notification shade, so it is no longer in the menu.",
                "The large title is gone: a slim top bar with the logo on the left and Settings and the menu on the right, which gives you more room. A bin button beside the eye button clears sent history, and asks first, saying how many items it will remove.",
                "Fixed alerts whose time has no AM or PM (such as \"02:13:41\" for 2:13 PM) being dated twelve hours off, which also stopped them matching as a transfer.",
                "Long-press anything in Recent activity to remove it from the app (not from Wallet). Scan for missed alerts then picks it up again if the bank's notification is still there.",
                "Bank ads, login notices and bill reminders are skipped instead of showing as \"not understood\". Turn this off with \"Skip ads and notices\" in Settings.",
                "Fixed payments failing with \"is a system category\": Wallet's Transfer category is no longer suggested or offered, and a payment it was picked for goes with another category instead.",
            )
        ),
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
                "Search when choosing a category: type part of its name or group, such as \"food\", to narrow the list.",
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
