# Google Play listing

Everything to paste into Play Console (https://play.google.com/console), section by section. Keep it in step with `PRIVACY.md` and what the app does.

## Main store listing (Grow users › Store presence › Main store listing)

**App name** (max 30): Bank to Wallet

**Short description** (max 80, this one is 75):
Turns your bank app alerts into BudgetBakers Wallet records, automatically.

**Full description** (max 4000):

```
Bank to Wallet adds your spending to BudgetBakers Wallet for you, so you stop typing in every coffee and bill.

When a bank app on your phone shows a notification such as "You spent RM 12.30 at a shop", Bank to Wallet reads it, works out the amount, who you paid and the right category, and adds the record to your Wallet account.

FEATURES
• Only reads the bank apps you choose; every other notification is ignored and never stored.
• Each bank app (or each card, by its last four digits) feeds the Wallet account you pick, with a default account for alerts that don't say which.
• Categories from your own Wallet rules, your past records and the choices you've made before.
• Transfers between your own accounts become one Wallet transfer, not an expense and an income. Touch 'n Go Cash ins to GO+ too.
• Nothing is lost offline: records wait on your phone and send themselves when you're back online, optionally on Wi-Fi only.
• No duplicates: repeated alerts are recognised, and an upload that got no answer is checked against Wallet before it is sent again.
• Ask before sending: approve or discard each record from a notification, if you prefer. Or let records go and change or stop one from its "picked up" notification.
• Ads and notices from your bank are skipped; real alerts it can't read are kept, so you can enter them by hand in a few taps.
• Change the category, payee or note of any record before it is sent, or edit or remove it in Wallet afterwards, and an optional daily summary at 9 pm.
• Scan for alerts you missed while the app was off, and a Quick Settings tile to pause it.
• App lock with your fingerprint, face or screen lock, and a button that hides amounts on screen.
• Uses almost no battery: nothing runs until a bank notification arrives or an upload is due.
• Material You design with light and dark themes.
• Try it first with demo data: no bank app or Wallet account needed.

PRIVACY
Everything happens on your phone. Records go straight from your phone to the official Wallet REST API with your own token, which is stored encrypted on the phone. No developer server, no analytics, no ads, no tracking.

REQUIREMENTS
• A BudgetBakers Wallet Premium account (Wallet's REST API is Premium only) and an API token, created in the Wallet web app.
• A bank or e-wallet app that shows transaction notifications. Built and tested with Malaysian apps (amounts in RM, as well as foreign-currency payments), such as CIMB OCTO, MAE by Maybank, Ryt Bank, Touch 'n Go eWallet and ShopeePay.
• Android 8.0 or newer.

A NOTE FROM THE DEVELOPER
I built this for my own use. If it fits your needs too, you're welcome to use it. It's a small personal project, so updates may be occasional.

Not affiliated with or endorsed by BudgetBakers or any bank. "Wallet" and the bank and app names are trademarks of their respective owners.
```

**Graphics** (all in this folder):
- App icon: `play-icon-512.png` (512×512)
- Feature graphic: `feature-graphic-1024x500.png` (1024×500, opaque)
- Phone screenshots: 2 to 8, taken in demo mode (see "Screenshots" below)

## Store settings (Grow users › Store presence › Store settings)
- App or game: **App**
- Category: **Finance**
- Tags: Budgeting, Personal finance (pick the closest offered)
- Contact email: required, shown on the listing
- Website: optional (for example the GitHub page)

## App content (Policy and programs › App content)

**Privacy policy:** a public URL of `PRIVACY.md` (fill in its contact email first).

**App access:** choose "All or some functionality is restricted", and add these instructions (no username or password needed):

```
The app needs a BudgetBakers Wallet Premium API token and a bank app's notifications, so please use the built-in demo. On the first screen of the setup guide, tap "Just looking? Try the demo" (or later: ⋮ menu › "Try with demo data"). The home screen then shows made-up bank apps and transactions: tap a row to see its details and approve, edit or fix it, and "Send N waiting" to mark the waiting ones as sent. Nothing is read from real notifications or sent to Wallet. "Exit demo" leaves it.
```

**Ads:** No, the app doesn't contain ads.

**Content rating:** fill in the questionnaire. Category: "All other app types". Answer No to everything (no violence, sexual content, profanity, drugs, gambling, user-generated content or sharing, location sharing, purchases). Expected result: rated for everyone (PEGI 3 / Everyone).

**Target audience and content:** 18 and over only. Not designed to appeal to children.

**News app:** No. **Government app:** No. **Health apps:** none of the health features.

**Financial features:** the app doesn't provide financial services (no banking, payments, loans, investing or crypto). It's a bookkeeping helper that copies the user's own notifications into the user's own budgeting account. If the form offers a personal finance / budgeting option, choose that; otherwise "My app doesn't provide any financial features".

**Data safety:**
- Does the app collect or share any of the required user data types? **Yes**
- Is all user data encrypted in transit? **Yes** (HTTPS only; cleartext traffic is off)
- Do you provide a way for users to request that their data is deleted? **Yes**: everything on the phone is deleted by clearing history or uninstalling; records in Wallet are deleted in Wallet.
- Data types, each **Collected: yes, Shared: no, Processed ephemerally: no, Required (users can't turn it off), Purpose: App functionality**:
  - Financial info › **Purchase history** (amounts, merchants, dates of the user's transactions)
  - Financial info › **Other financial info** (references; who paid the user and whom the user paid; transfers between the user's own accounts)
- Not collected: location, contacts, messages, photos, files, health, app activity, web history, device IDs, crash logs, diagnostics.
- Why "collected" but not "shared": records are sent off the phone only to the user's own BudgetBakers Wallet account at the user's direction, which Play counts as collection; nothing goes to the developer or any other party.

**Permissions to explain if asked** (no Permissions Declaration form is needed for these):
- Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`): the app's core feature, reading bank-app transaction alerts. A disclosure is shown in the app, and the user must tap "Agree and continue" before the app opens the system setting.
- `POST_NOTIFICATIONS`: problems with uploads, alerts it couldn't read, the "ask before sending" approvals, the optional "picked up" notice for each transaction and the optional daily summary.
- `INTERNET`, `ACCESS_NETWORK_STATE`: sending records to the Wallet API when a network is available.
- `USE_BIOMETRIC`: the optional app lock.
- The battery exemption isn't requested; the user sets "Unrestricted" on the app's settings page if they choose to.

## Screenshots
Use demo mode only, never real transactions. The ones in `screenshots/` (1.6, 1080×2160, the app's own teal colours) are, in upload order:
1. `1-home.png`: the home screen (demo hero card, counts and recent activity by day)
2. `2-details.png`: "Sample Electric" tapped, its sheet with details and actions
3. `3-approve.png`: its "Review and change", the "Send to Wallet?" dialog
4. `4-not-understood.png`: the "Sample Bank: RM30.00" row's Fix, the "Enter this transaction" dialog
5. `5-dark.png`: the home screen in dark mode
6. `6-sent.png`: "Sample Grocer" tapped, a sent record's sheet with Edit in Wallet

To retake them, run `.	ools\screenshots.ps1` from the repo root (about a minute): it builds the debug APK, starts the emulator, opens the app straight into demo mode and saves all six with a clean status bar. Play needs 2 to 8 phone screenshots, PNG or JPEG, sides between 320 and 3840 px, the long side no more than twice the short side.

## Release
1. Build the bundle: `.\gradlew.bat bundleRelease` (with `keystore.properties` in place), output `app/build/outputs/bundle/release/app-release.aab`.
2. First upload: **Test and release › Testing › Closed testing**, create a track, add testers, upload the `.aab`, add release notes (the latest `Changelog` entry), roll out.
3. Release notes for 1.6 (Play's limit is 500 characters):

```
• New home screen: activity grouped by day, calmer rows with status labels; tap Waiting, Sent or Need you to filter
• Tap a transaction for its details and actions; nothing is sent while that's open
• Edit or remove a transaction already sent to Wallet
• Pull down to scan for missed alerts
• Bank apps grouped by app; any of them can have a default account
• New installs wait 2 minutes before sending
• Settings say "Saved" as each change is saved
```

4. Personal developer accounts need 12 testers opted in for 14 days in a row before **Production** can be applied for.
