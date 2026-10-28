# Privacy Policy

_Bank to Wallet (Android app, package `app.banksync`) · last updated 28 October 2026_

Bank to Wallet reads the transaction notifications from the bank apps you choose and adds them as records in your BudgetBakers Wallet account. This policy explains what the app does with your data. In short: everything stays on your phone, except the transactions it sends to your own Wallet account.

## What the app reads
- **Notifications from the bank apps you choose.** With your permission (Android's "notification access"), the app sees notifications as they arrive. It immediately ignores every notification from an app you haven't added under **Bank apps**. From a bank app's alert it reads the text to find the amount, the currency, who was paid or who paid you, the reference and the time.
- **Your Wallet API token.** You paste it into the app. It is stored on your phone only, encrypted with a key held in the Android Keystore.
- **Your Wallet data**, fetched from Wallet with your token so the app can pick the right account and category: your accounts, your categories, your record rules (categorisation rules), and your categorised records from about the last six months. Before re-sending a record whose earlier upload got no answer, the app also checks your Wallet records around that time, so nothing is added twice.
- **The list of apps on your phone that have a launcher icon**, only while you pick a bank app, so you can choose one. It is not stored or sent anywhere, apart from the bank apps you choose.

## What the app stores on your phone
In the app's private storage only:
- the transactions it captured, with any note you typed for one, until Wallet confirms them. The alert's full text is deleted as soon as the record is in Wallet; the remaining history (date, amount, payee, category, status) is deleted after the number of days you choose (7 by default), or when you tap **Clear sent history**;
- alerts it couldn't understand, until you fix or delete them, or the history period ends;
- your token (encrypted), the bank apps and the Wallet account each one feeds, your category rules, the categories you chose by hand for particular merchants, and your settings;
- a copy of the Wallet data listed above, so categories can be picked without going online.

The app turns off Android backup, so none of this is copied to Google Drive. **Export settings** writes a file you choose; it never includes your token. Uninstalling the app deletes everything.

## Where data goes
- Records are sent **directly from your phone to the official BudgetBakers Wallet REST API** (`https://rest.budgetbakers.com`) over HTTPS, using your own token. Each record contains the Wallet account, amount, date, category and labels, the payee (who you paid, or who sent you money), and a short note with the reference, the original amount for a payment in another currency and, when the alert gives one, the payee's fuller name (you can turn the note off, or change or clear it for a record before it is sent). When money moves between two of your own accounts, including a Touch 'n Go Cash in to GO+, the record is sent as a Wallet transfer, which also names the other account (or Wallet's id for the record already on it). The full notification text is not sent.
- **Nothing is sent to the developer or to anyone else.** There is no developer server, no analytics, no crash reporting, no advertising and no tracking. The app makes no network requests except to the Wallet API.
- BudgetBakers handles your Wallet data under its own privacy policy. This app is not made by, affiliated with or endorsed by BudgetBakers or any bank.

## Demo mode
**Try with demo data** shows made-up bank apps and transactions. Demo data lives only in memory: it is never sent to Wallet and is gone when you leave the demo or close the app.

## Your choices
- Turn notification access off at any time in Android's settings; the app then reads nothing.
- Pause capture from the Quick Settings tile or the app.
- Turn on **Ask before sending** to approve each record before it goes to Wallet.
- Remove a bank app, clear the history, forget remembered choices, or uninstall the app to delete everything on the phone. Records already in Wallet stay there until you delete them in Wallet.

## Children
The app is meant for adults managing their own finances and is not directed at children.

## Changes
If what the app does with data changes, this policy is updated in the same release, with a new date above.

## Contact
Questions about this policy: atsukiodev@gmail.com
