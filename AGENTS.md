# AGENTS.md

Android app "Bank to Wallet" (package `app.banksync`): reads bank-app notifications (e.g. CIMB OCTO MY, Ryt Bank, TNG eWallet, MAE/Maybank2u, ShopeePay in the Shopee app) and adds them as records in BudgetBakers Wallet through its REST API. Sibling of the Chrome extension in `../bank-to-wallet-sync` (same owner, same Wallet API, same category logic). Sideloaded for the owner's own use, and being prepared for Google Play (closed testing first); see `store/LISTING.md`.

Priorities, in order: battery and data (nothing runs unless a notification arrives or an upload is due), never losing or duplicating a transaction (offline queue with retry), then a clean Material 3 UI.

## Data and security rules (read first)

- **Never read, print, log or copy the owner's Wallet API token**, `local.properties`, keystores or any `.env`. The token lives only on the phone, AES-GCM encrypted with a Keystore key (`Settings.kt`).
- Test data is **synthetic only**: made-up merchants, amounts and account numbers. Never ask for real notifications; ask for dummy ones in the bank's format. If the owner pastes or screenshots real transactions, don't repeat or store the values.
- Release APK size, test counts and build output are fine to report.

## Layout

Single module `app/`. Kotlin, **Android Views with Material Components 1.12 (Material 3), not Compose**. minSdk 26, target/compile 36.

| File | Role |
| --- | --- |
| `BankNotificationListener.kt` | `NotificationListenerService`. Package check first (only apps with a rule), ignore words, parse, pick account and category, insert into `Store`, schedule upload. `catchUp()` scans notifications still in the shade. `handle` is `synchronized` so a scan and a live alert can't both insert. Everything after the duplicate check is the companion's `capture`, so `recheck(context, pkg)` can rerun alerts held without an account (`Store.heldWithoutAccount`, same hash and posted time) after a link or default is added; `Pickers.recheckHeld` runs it off the main thread. |
| `Parser.kt` | Regex-only `Parser.parse(text)` → `Parsed(amount signed, payee, currency)` or null when unsure (then the row is UNPARSED, fixed by hand). `cashIn` reads a Touch 'n Go "cashed in RM x into your GO+ account" alert (no payment word, so `parse` can't) as money out of the app's main account. `recordTime` uses the alert's own time when plausible; an hour written with no AM/PM (1 to 12) may be the other half of the day, and the one within 2 h of posting wins (CIMB writes "02:13:41" for 2:13 PM). `walletText` decides what Wallet gets at upload: the payee always goes in Wallet's `counterParty` (payee of an expense, payer of income), shops, people paid and senders alike. `note` builds the Wallet note (the merchant's fuller name with phone and country only when it adds to the payee (`paidTo`), ref, foreign amount; no card/account number; nothing to say means no note); the stored `note` column keeps the full alert for the Fix dialog and category picking. |
| `Rules.kt` | `Rule` (bank app → Wallet account, optional `match` text such as last 4 digits), `RuleMatcher` (specific match, then the app's default (`isDefault`, one per app, set by tapping a link in Bank apps, in the Add dialog or from the Fix dialog) unless `endings` finds an ending of the user's own that no rule has (masked mobile numbers and payees' numbers don't count), then the app-wide rule, then an app's only rule), `GoPlus` (a Touch 'n Go rule whose account is named "GO+" is not for ordinary alerts: it is where Cash ins go; the listener filters those rules out before `RuleMatcher`, and `Settings.addRule` keeps one per app apart from the ordinary ones), `IgnoreWords`, `CatchUp.cutoff`, `CategoryRule`/`CategoryMatcher`. Pure, unit-tested. |
| `CategorySearch.kt` | Pure type-to-filter for category pickers (every word anywhere, accents ignored, prefix matches first); `Dialogs.pickCategory` shows it, used by `CategoryChooser` and the category-rule picker. Unit-tested. |
| `Categorizer.kt` | Pure category picking ported from the extension: `Merchant` keys, `Categorizer.pick`, Wallet rule matching, history guess. Unit-tested. |
| `Changelog.kt` | User-facing release notes, newest first, shipped in the app. "What's new" shows once after an update (`seenVersion`; never on a fresh install); Settings › About shows them all. Unit-tested. |
| `WalletCache.kt` | `wallet_cache.json` in `filesDir`: Wallet record rules, categories and categorised history, used offline at capture time. `refresh` only runs when an upload is already online (rules ≤ hourly, history ≤ 12 h) or forced after a token check. |
| `Store.kt` | SQLite queue `queue.db` (version 6). Statuses `PENDING`, `SYNCED`, `FAILED`, `UNPARSED`, `REVIEW`. `noteEdit` is a note the user wrote in the record dialog while the record waits (null follows the alert, blank sends no note; `Uploader.toJson` prefers it over `Parser.walletText`'s note). `walletId` (Wallet's record id, kept from the create response) and `pairId` (the other side of a transfer); `transferTo` is the Wallet account a record goes to as a transfer by itself (set in the record dialog for money out, or by a Touch 'n Go Cash in); `pair`/`unpair` clear it. Raw text is dropped once synced; history purged after `keepDays`. `get` is always the real queue (listener, uploader, `ReviewReceiver`); screens use `shown`, which is the in-memory demo queue while `Demo` runs. |
| `Demo.kt` | Demo mode for trying the app and for Play reviewers: made-up rules, categories and alerts (run through the real `Parser`) in an in-memory queue. Never saved, never touches real settings, nothing uploaded; screens guard `Uploader.schedule`, `WalletCache` and `rememberCategory` with `Demo.active`. Started from the menu or the guide's first step. |
| `Uploader.kt` | (`running`, `requested` and `onChange` tell the home screen when a job starts, ends or a batch lands; `MainActivity` redraws on them and `renderSync` drives the Sync now button.) JobScheduler job (`UploadJobService`): network constraint (Wi-Fi only optional), batches of 50, backoff, `uncertain` handling via `WalletApi.recordExists` because Wallet has no duplicate protection. Transfer halves follow `Transfers.plan`: `transfer.pairingMode` `existing` (bind to the sent side's `walletId`) or `new` (older unsent side sends the whole transfer; the other is marked sent with `createdMirrorRecordId`), never with a category; a refused pairing unpairs and re-sends as normal records. A record with `transferTo` and no `pairId` goes as `pairingMode: new` to that account (`Send.New` with no partner). |
| `Transfers.kt` | Pure transfer matching: same amount the other way, different linked account, same currency, alert times within `WINDOW_MS` (5 min), closest wins; `plan` decides how each half is sent. Unit-tested. |
| `WalletApi.kt` | `HttpURLConnection` client: post records, accounts, categories, record rules, history, `recordExists`. |
| `Settings.kt` | SharedPreferences wrapper, encrypted token, rules, category rules, `categoryMemory`, export/import JSON. |
| `Notifier.kt` | Four channels: alerts (token, failed sends, and one counting "not understood" alerts, cleared once none are left), "ask before sending" review notifications with Send/Discard (`ReviewReceiver`), a silent "picked up" one per captured transaction (`capturedOn`; the latest replaces the last; Change opens `MainActivity.EXTRA_RECORD` after unlock, Don't send is `ReviewReceiver.DROP` via `Store.discardWaiting`; `capturedSent` turns it into "Sent to Wallet" after an upload; no details when `hideDetails`), and the daily summary. Private on the lock screen. Event-driven only. |
| `Summary.kt` | Opt-in daily summary (`dailySummary`): one inexact, non-waking `AlarmManager.RTC` alarm at 9 pm, `SummaryReceiver`, rescheduled on app open and when the listener reconnects (covers reboot). Pure `nextAt`/`dayStart`/`text` are unit-tested. |
| `Permissions.kt` | Notification access, listener rebind, battery. Every "turn on access" button goes through `askAccess`, the prominent disclosure Play requires (what is read, where it goes, "Agree and continue"). Battery opens the app's info page (Battery › Unrestricted); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was removed because Play restricts it, so don't add it back. |
| `PauseTileService.kt` | Quick Settings tile to pause capture. |
| `LockedActivity.kt` | Base of every screen: app lock (`BiometricPrompt`, BIOMETRIC_WEAK or device credential, 60 s grace, process-wide `started`/`locking`), `enableEdgeToEdge()`, `applyInsets`, notification permission flow. |
| `MainActivity.kt` | Home: a slim top bar (logo on the left, no title; Settings and the menu on the right), hero status card (worst problem first; when all is well it is compact, an icon and "Watching N bank apps", via `Hero.compact`), check cards, count tiles, recent activity (a transfer is one row, its outgoing half standing for both, unsigned; tapping the chevron opens both halves underneath (`item_transfer_half`, state in `openTransfers`); tapping any row or half opens `Dialogs.showDetails` (read-only payee/payer, account, category, note, status; an unsent one also gets Fix/Review/Edit/Retry buttons into the old dialogs); a row's "Note:" line shows the Wallet note; amounts are green in and red out; each row shows the bank app's own icon with a `Mark` badge on its corner, or the same badge full size when the app isn't installed; the button row has Send N waiting (greyed out when none), Scan notifications and Retry failed; tap a row to review/edit/fix/retry; long-press removes it from the phone only, unpairing a transfer, so a scan can recapture the alert; a bin button by the eye clears sent and not-understood rows after a confirmation that warns a scan could re-send them; eye button sets `hideDetails` to mask payees, amounts and categories). |
| `SettingsActivity.kt` | Auto-saving settings: switches save immediately, text fields in `onPause`. Wallet token card, bank apps, categories, capture, uploads, privacy. |
| `OnboardingActivity.kt` | Setup guide; the step reached is saved. Steps: welcome, token, notification access, battery, bank apps, a few setting switches (ask before sending, ask when there's no category, picked up, daily summary, Wi-Fi only, app lock; same behaviour as in Settings), then a summary. |
| `Dialogs.kt` / `Pickers.kt` | Record/fix/test dialogs, `CategoryChooser`, app and account pickers, list rows. |
| `Motion.kt` | Short home-screen animations that play only when something changed (`animateLayout`, `TextView.countTo`, `pop`, `dropIn`, `flash`, `fadeColors`); `MainActivity` remembers what it drew last (`heroKey`, `rowStates`, `checksKey`, `attentionColors`) so the first draw and plain refreshes never animate. They follow the system animation scale. |
| `Ui.kt` | `Tone` (GOOD/NEUTRAL/WARN/BAD → M3 container roles), `themeColor`, `ImageView.badge`; `Mark` (solid row-status colours from `res/values{,-night}/colors.xml`: green in, red out, blue waiting, amber needs you, dark red failed, purple transfer) and `ImageView.mark`; `AlertDialog.danger` turns a dialog button that deletes or drops something red. |
| `res/values{,-night}/themes.xml` | Teal M3 palette and the `Widget.BankSync.*` styles. Change both files together. |
| `app/src/test/` | JUnit 4: `ParserTest`, `RuleMatcherTest`, `CategorizerTest`, `ChangelogTest`, `DemoTest`, `SummaryTest`, `CategorySearchTest`, `TransfersTest`. |
| `PRIVACY.md` | Privacy policy; its public URL goes in Play Console. Keep it true to what the code does, and update it in the same change as anything that alters what is read, stored or sent. |
| `store/` | Play listing text and form answers (`LISTING.md`), `play-icon-512.png`, `feature-graphic-1024x500.png`, `screenshots/` (five demo-mode phone shots, 1080x2160, one dark), and `make-graphics.mjs`, which renders both from the extension's icon with its Puppeteer and sharp (`CHROME_PATH` to Brave; Edge exits silently here). |
| `.claude/skills/` | Google's Android skills (github.com/android/skills), git-ignored, local only. Many assume Compose; adapt them to Views. |

## Build and test (Windows)

The command-line launcher needs JDK 17; Gradle itself runs on JetBrains Java 21 via the daemon toolchain (`gradle/gradle-daemon-jvm.properties`, foojay resolver).

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --no-daemon --console=plain
```

- Check results in `app/build/test-results/testDebugUnitTest/*.xml` and the APKs in `app/build/outputs/apk/{debug,release}/`. Report failures with their output.
- Don't run `clean`; the build and configuration caches make reruns take seconds.
- VS Code needs a Kotlin extension; without one `.kt` files open as plain text and Spell Right flags code words (`val`, `banksync`). With it, spelling hits are only in comments and strings and are worth a look. VS Code doesn't understand the Android build, so only Gradle output counts.
- After build file changes, the owner should also run **File › Sync Project with Gradle Files** in Android Studio (can't be run from here).

## Toolchain rules (AGP 9)

- AGP 9.4.1, Gradle 9.6.0, **built-in Kotlin** (AGP bundles KGP 2.2.x). Do **not** add `org.jetbrains.kotlin.android` or a `kotlinOptions`/`kotlin { }` block; the JVM target follows `compileOptions` (17).
- `gradle.properties` holds only the jvmargs, build cache, configuration cache, `useAndroidX` and `nonTransitiveRClass`. Don't add AGP 8 compatibility flags (`android.builtInKotlin`, `android.newDsl`, etc.).
- Configuration cache is on: build logic must not read files, env vars or system properties at configuration time without Gradle's providers.
- Dependencies are deliberately few: Material and `androidx.biometric` (UI only), JUnit for tests. The background path (listener, parser, SQLite, JobScheduler, HttpURLConnection) uses framework APIs only. Ask before adding a library; never add one to the background path.
- **Versioning:** when the owner asks for a release (or a batch of user-visible changes is ready to install), bump `versionCode` and `versionName` in `app/build.gradle.kts` and add a matching entry at the top of `Changelog.releases`, in plain English for the owner, not commit messages. Without the bump, "What's new" never shows.
- Release: R8 with resource shrinking, no custom keep rules (none needed, no reflection). Signed with the Play upload key described in `keystore.properties` at the repo root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; git-ignored, read through `providers.fileContents`); without that file it falls back to the debug key, which Play rejects. Never open or print `keystore.properties` or the keystore. Play takes the `.aab` from `bundleRelease`.

## How things work

**Capture:** listener (linked app only) → ignore words → `Parser.isNotice` (`skipNotices`, on by default: no amount and no payment word, or ad, offer or reminder wording; dropped, not stored) → `Store.isDuplicate` (hash, 2 min) → `RuleMatcher.pick` (none → UNPARSED) → `Parser.parse` (null → UNPARSED) → transfer match (`Transfers.match` over `Store.transferCandidates`; a match gets no category, both rows get `pairId`, "Not a transfer" in the record dialog undoes it while unsent) → `WalletCache.pick` → insert as PENDING (with a "picked up" notification), or REVIEW (review mode, or `askNoCategory` and no category) with an "ask before sending" notification; UNPARSED posts the counting "needs you" alert. A Touch 'n Go Cash in (`Parser.cashIn`) skips the parse and transfer match: dropped when `cashInIgnored`; with a linked GO+ account it becomes a transfer from the main account to it (`transferTo`, no category); with none it is held as UNPARSED and `Notifier.askIgnoreCashIn` offers "Ignore them" (`ReviewReceiver.IGNORE_CASH_IN`). `Pickers.cashInFollowUp` runs after linking: a main Touch 'n Go account offers Wallet's GO+ accounts (or asks to ignore Cash ins when there are none), and a GO+ account linked while ignoring asks to start using it. Settings has a switch for `cashInIgnored`. → `Uploader.schedule` (no delay by default, so it goes once there's a network; an optional delay in Settings sends bursts as one request).

**Categories:** remembered manual choice (`categoryMemory`, keyed by `Merchant.memoryKey`) → app `CategoryRules` → Wallet record rules (longest keyword, labels, same account) → history guess (same description, else merchant key, same income/expense type, most common; transfers are left out of history). Records with no category are re-picked just before upload. Wallet refuses its Transfer category on normal records: it is dropped from category lists (`systemId` containing "transfer"), and an upload error naming a category (`Categorizer.refusedCategory`) adds it to `Settings.refusedCategories`, clears it from the record and leaves the record queued so it is re-picked without it. `CategorySource` is stored so the UI can say why.

**Review and edits:** `Store.edit` and `approve` only touch PENDING/REVIEW rows and return false if the record was sent meanwhile ("Too late"). `ReviewReceiver` uses the same guards, so a stale notification button can't re-queue a sent record.

**Uploads:** a request with no response marks the batch `uncertain`; before re-sending, `recordExists` checks Wallet (account, amount, ±2 min). 401/403 sets `authError`, shown on the home screen. Per-item results apply to each row.

**App lock:** `checkLock` in `onStart` when the first screen starts after more than 60 s away. Content is hidden until `onAuthenticationSucceeded`; any error (cancel, back, leaving the app) finishes the screen. The prompt survives rotation because `BiometricPrompt` is built in `onCreate` and `locking` is process-wide.

**Edge-to-edge:** every screen calls `applyInsets(root, scroll)`. App bars use `fitsSystemWindows`; the scroll view gets the nav bar or keyboard as bottom padding. Activities with text fields use `adjustResize`. Don't add `statusBarColor`/`navigationBarColor` to themes.

## Wallet API notes

- Base `https://rest.budgetbakers.com/wallet`, paths `/v1/api/...`. Spec: `https://rest.budgetbakers.com/wallet/openapi`; read it instead of guessing field names.
- Premium only, about 300 requests/hour. 409 = first sync still running; 401/403 = bad token. Amount sign decides income vs expense. Lists use keys like `records`, `accounts`, `recordRules`, paginated with `nextOffset`. `POST /records` max 50. `recordState` includes `void`.
- Never call the API against the owner's account from here; it only happens on the phone.

## Conventions

- Match the surrounding code: small functions, short comments that say why, KDoc on non-obvious functions, plain English UI text.
- New pure logic goes in a pure object/file with a JUnit test, using made-up data.
- Schema change: bump the `Store` version and add an `if (old < N)` block in `onUpgrade` (never drop data). New setting: add it to `exportJson`/`importJson`.
- New screen: extend `LockedActivity`, call `applyInsets`, declare it `exported="false"` in the manifest.
- Keep components unexported unless protected by a system permission; `PendingIntent`s immutable.
- Colours via theme attributes and `Tone`, never hard-coded hex in Kotlin. Check light and dark.
- Files use CRLF on disk; the "LF will be replaced by CRLF" warning is harmless. Prefer the Edit tool; script patches go in the scratchpad and must keep line endings.
- Keep this file current in the same commit as a change that makes it wrong.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), as in the owner's other repos (`../extension-kit/CONVENTIONS.md`): `<type>(<scope>): <imperative summary, lower case>`. Scopes: `capture`, `parser`, `upload`, `categories`, `ui`, `settings`, `lock`, `build`. Commits up to `4b37e71` used plain sentences; don't rewrite them. End with the session's `Co-Authored-By` trailer.

## Working with the owner

- Commit only when the owner asks; offer when a coherent set of changes is ready.
- They test on their own phone with real banks; feedback may include screenshots with real values (see Data rules).
- Explain Android Studio steps concretely (menu paths, what to click); they're not an Android specialist.
- Confirm before larger features; small fixes and verifications can just be done.

## Status as of 2026-10-28

- Done: capture and offline queue, catch-up scan, review mode, extension-style categories with Wallet rules and labels, redesigned home and settings, edge-to-edge, AGP 9 migration, BiometricPrompt app lock, configuration cache. 1.2 (versionCode 3) prepares for Play: demo mode, the notification-access disclosure, no battery-exemption permission, upload-key signing, `PRIVACY.md` and `store/`. 1.3 (versionCode 4): "needs you" and "picked up" notifications, daily summary, ask when there is no category, single-rule fallback, remember an account from the Fix dialog, full merchant names in notes. 1.4 (versionCode 5): transfer pairing (replaces the removed "skip money Wallet already has"), skipping ads and notices, the Transfer category fix. 1.5 (versionCode 6): Touch 'n Go Cash ins to GO+, per-app default accounts with held alerts rechecked, editable notes, the record details dialog, ShopeePay, every payee in `counterParty`. 1.5.1 (versionCode 7): no "−0.00" for an unread alert's amount.
- Unverified on a real phone: whether binding with `pairingMode: existing` turns the already-sent side into a transfer (category included); all of 1.3 (notification buttons, Change after the app lock, the 9 pm alarm), demo mode, the disclosure dialog and the battery settings page (the emulator's system image is missing), the whole redesign (light/dark, keyboard, landscape), Learned-from-Wallet counts, labels on created records, app lock with `BiometricPrompt` (including rotation while the prompt is up), the permanently-refused notification permission dialog.
- Waiting on the owner: dummy samples of transfer alerts whose layout differs from the DuitNow ones (`ParserTest` already tries each DuitNow template with other method names such as Instant Transfer and IBG); the Play Console forms and 12-tester closed test (see `store/LISTING.md`); decisions on the USD estimate, the note default, undo after sending, and a wider duplicate check.
