# AGENTS.md

Android app "Bank to Wallet" (package `app.banksync`): reads bank-app notifications (CIMB OCTO MY, Ryt Bank, TNG eWallet, MAE/Maybank2u, ShopeePay in the Shopee app, ...) and adds them as records in BudgetBakers Wallet through its REST API. Sibling of the Chrome extension in `../bank-to-wallet-sync` (same owner, API and category logic). Used by the owner, and heading for Google Play (closed testing first; see `store/LISTING.md`).

Priorities, in order: battery and data (nothing runs unless a notification arrives or an upload is due), never losing or duplicating a transaction (offline queue with retry), then a clean Material 3 UI.

## Data and security rules (read first)

- **Never read, print, log or copy the owner's Wallet API token**, `local.properties`, keystores, `keystore.properties` or any `.env`. The token lives only on the phone, AES-GCM encrypted with a Keystore key (`Settings.kt`).
- Test data is **synthetic only**: made-up merchants, amounts and account numbers. Ask for dummy alerts in the bank's format, never real ones. If the owner shares real transactions, don't repeat or store the values.
- Never call the Wallet API against the owner's account from here, and never drive the owner's phone over adb (it can be connected); use the emulator with demo data.
- Release APK size, test counts and build output are fine to report.

## Layout

Single module `app/`. Kotlin, **Android Views with Material Components 1.12 (Material 3), not Compose**. minSdk 26, target/compile 36. Everything is in `app/src/main/java/app/banksync/`.

**Background path** (framework APIs only):

| File | Role |
| --- | --- |
| `BankNotificationListener.kt` | `NotificationListenerService`: package check, then the capture pipeline below. `catchUp()` scans the shade; `handle` is `synchronized`. `recheck(context, pkg)` reruns alerts held for want of an account after a link or default is added. |
| `Parser.kt` | Regex-only `parse(text)` → `Parsed(amount signed, payee, currency)`, or null when unsure (row becomes UNPARSED). `cashIn` (Touch 'n Go Cash in), `isNotice`, `recordTime` (an hour with no AM/PM picks the half within 2 h of posting), `walletText` (payee → `counterParty`; the note: fuller merchant name, ref, foreign amount, never card numbers). |
| `Rules.kt` | `Rule` (bank app → Wallet account, optional `match` such as last 4 digits, `isDefault` one per app), `RuleMatcher` (match, then default unless `endings` finds an unlinked ending of the user's own, then app-wide rule, then an app's only rule), `GoPlus` (a TNG rule for an account named "GO+" receives Cash ins only), `IgnoreWords`, `CatchUp`, `CategoryRule`/`CategoryMatcher`. |
| `Categorizer.kt` / `WalletCache.kt` | Category picking ported from the extension (`Merchant` keys, Wallet rules, history guess), on `wallet_cache.json` (rules ≤ hourly, history ≤ 12 h, refreshed only when an upload is online anyway). |
| `Transfers.kt` | Matches two halves of a transfer (same amount the other way, other linked account, same currency, within 5 min); `plan` decides how each half is sent. |
| `Store.kt` | SQLite `queue.db` (version 7). Statuses `PENDING`, `SYNCED`, `FAILED`, `UNPARSED`, `REVIEW`. `noteEdit` (user's note; null follows the alert, blank sends none), `walletId`, `pairId`, `transferTo`, `patch` (queued Edit in Wallet changes, or `SentEdit.DELETE` for Remove from Wallet; a refused one keeps its reason in `error` and isn't sent until `retryEdit` or a new edit). `get` is the real queue; screens use `shown` (the demo queue while `Demo` runs). |
| `Uploader.kt` | JobScheduler job: network constraint, batches of 50, backoff, `uncertain` check via `WalletApi.recordExists` (Wallet has no duplicate protection), transfers per `Transfers.plan` (`pairingMode` `existing`/`new`, never a category). Then `sendEdits`: PATCH or DELETE `/records`, 10 a request, safe to resend; "not found" on a removal counts as done. `hold`/`release`: uploads pause while a record sheet is open, for the batch delay from the last tap (0 = no pause). |
| `WalletApi.kt` | `HttpURLConnection` client: records (post, patch, delete), accounts, categories, record rules, history, `recordExists`. |
| `Notifier.kt` / `ReviewReceiver` / `Summary.kt` | Channels: alerts (token, failed, one counting "not understood"), "ask before sending" with Send/Discard, a silent "picked up" per capture (Change, Don't send; becomes "Sent to Wallet"), the opt-in 9 pm summary (one inexact non-waking alarm). Private on the lock screen; event-driven only. |
| `Settings.kt` | SharedPreferences wrapper: encrypted token, rules, category rules, `categoryMemory`, every option; `exportJson`/`importJson`. |

**Screens:**

| File | Role |
| --- | --- |
| `LockedActivity.kt` | Base of every screen: app lock (`BiometricPrompt`, 60 s grace, process-wide state), edge-to-edge, `applyInsets`, notification permission flow. |
| `MainActivity.kt` | Home: hero status card (worst problem first; compact when all is well), check cards, count tiles that filter the list (`Filter`), Send/Retry buttons only when useful, pull down to scan, ⋮ menu (scan, hide amounts, clear history, demo, guide, test, export/import). Debug builds open straight into the demo with `EXTRA_DEMO`. |
| `HomeList.kt` | Recent activity as `MainActivity` extensions: grouped by day (`Days`), each row `describe`d into a `Look` (who; category and time; amount plain out, green in, grey for transfers; a `RowStatus` pill only when not simply sent; bank icon, else symbol or initial). A transfer is one row with its halves under a chevron. |
| `HomeSheet.kt` | What tapping or holding a row opens (`ActionSheet`): details (`Dialogs.facts`) and status-dependent actions (send now, change, don't send yet, not a transfer, retry, discard, fix, Edit in Wallet, Remove from Wallet, Try again / Forget my change / Keep it in Wallet, Remove from this phone). |
| `ActionSheet.kt` | The bottom sheet itself: header, note (sending paused, or `SentEdit.whyNot`), facts, actions. Closing it calls `Uploader.release`. |
| `SettingsActivity.kt` | Auto-saving with a "Saved" snackbar (text fields on Done/Next or focus loss). Token, bank apps, categories, capture, uploads, privacy, wallpaper colours (Android 12+). |
| `OnboardingActivity.kt` | Setup guide (step saved): welcome, token, access, battery, bank apps, a few switches, summary. |
| `Dialogs.kt` / `Pickers.kt` | Record, fix, edit-sent and test dialogs, `CategoryChooser`, app and account pickers. Bank apps grouped by app with the default marked; each rule row has a ⋮ menu (`RowAction`); Remove offers Undo. |
| `Permissions.kt` | Notification access via `askAccess` (the prominent disclosure Play requires), listener rebind, battery page. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was removed because Play restricts it; don't add it back. |
| `Demo.kt` | Made-up rules and alerts (through the real `Parser`) in an in-memory queue; nothing saved or uploaded (screens guard `Uploader.schedule`, `WalletCache`, `rememberCategory` with `Demo.active`). Sent demo records get fake `walletId`s so editing can be tried. |
| `Ui.kt` / `Motion.kt` | `Tone`, `Chip` (status pill colours; amber `chip_you` in `colors.xml`), `themeColor`, `AlertDialog.danger`; animations that play only when something changed and follow the system animation scale. |
| `PauseTileService.kt` | Quick Settings tile to pause capture. |

**Pure, unit-tested** (JUnit 4 in `app/src/test/`, made-up data): `Parser`, `Rules`, `Categorizer`, `CategorySearch`, `Transfers`, `SentEdit` (Edit in Wallet diff and request items), `RowStatus`, `Days`, `Summary`, `Changelog` (release notes shipped in the app; "What's new" once after an update), `Demo`.

**Elsewhere:** `res/values{,-night}/` (teal M3 palette and `Widget.BankSync.*` styles; change both; Android 12+ uses wallpaper colours unless turned off, via `App` and `DynamicColors`), `PRIVACY.md` (keep it true to the code, updated in the same change), `store/` (listing, form answers, graphics via `make-graphics.mjs`, screenshots), `tools/screenshots.ps1`, `.claude/skills/` (Google's Android skills, local only, mostly Compose: adapt to Views).

## Build and test (Windows)

The launcher needs JDK 17; Gradle runs on JetBrains Java 21 via the daemon toolchain.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --no-daemon --console=plain
```

- Results: `app/build/test-results/testDebugUnitTest/*.xml`; APKs in `app/build/outputs/apk/`. Report failures with their output. Don't run `clean`.
- `lintDebug` after UI or manifest changes. Checks for deliberate choices are off in `app/build.gradle.kts`, so what's left in `app/build/reports/lint-results-debug.xml` is worth reading. Decorative images get `android:contentDescription="@null"`.
- **See a UI change:** `.\tools\screenshots.ps1` builds, starts the `Medium_Phone_API_37.0` emulator, opens the app in demo mode and saves the Play screenshots to `store/screenshots/` (light and dark, 1080x2160). Look at them with the image reader; add a step for a new screen. Discard the result (`git checkout -- store/screenshots`) unless the screenshots should change.
- CI (`.github/workflows/ci.yml`) runs the unit tests, lint and a debug build on every push; the owner pushes.
- Only Gradle output counts; VS Code doesn't understand the Android build. After build file changes the owner runs **File › Sync Project with Gradle Files** in Android Studio.

## Toolchain rules (AGP 9)

- AGP 9.4.1, Gradle 9.6.0, **built-in Kotlin**: no `org.jetbrains.kotlin.android`, no `kotlinOptions`/`kotlin { }`; the JVM target follows `compileOptions` (17). No AGP 8 compatibility flags in `gradle.properties`.
- Configuration cache is on: build logic reads files only through Gradle providers.
- Dependencies: Material, `androidx.biometric`, `androidx.swiperefreshlayout` (UI only); JUnit and `org.json` for tests. Ask before adding a library; never add one to the background path.
- Release: R8 with resource shrinking, no keep rules needed. Signed with the upload key described in `keystore.properties` (git-ignored, never opened); without it the debug key, which Play rejects. Play takes the `.aab` from `bundleRelease`.
- **Versioning:** for a release, bump `versionCode`/`versionName` in `app/build.gradle.kts` and add the entry at the top of `Changelog.releases` in plain English for users. Play release notes (≤ 500 characters) live in `store/LISTING.md`.

## How things work

**Capture:** linked app only → ignore words → `Parser.isNotice` (no amount, or ad/offer/reminder wording: dropped) → duplicate check (hash, 2 min) → `RuleMatcher.pick` (none → UNPARSED) → `Parser.parse` (null → UNPARSED) → transfer match (`pairId`, no category) → `WalletCache.pick` → PENDING with a "picked up" notification, or REVIEW (review mode, or `askNoCategory` with no category). A TNG Cash in skips parsing: ignored if `cashInIgnored`, a transfer to the linked GO+ account, or held asking to ignore them. → `Uploader.schedule` after the batch delay (2 min default; 0 sends at once).

**Categories:** remembered choice (`categoryMemory`) → app category rules → Wallet record rules (longest keyword, same account) → history guess (transfers excluded). Wallet refuses its Transfer category on normal records: it's dropped from lists, and an upload error naming a category adds it to `refusedCategories` and re-picks.

**Edits and races:** `Store.edit`/`approve` only touch PENDING/REVIEW and return false once sent ("Too late"); `ReviewReceiver` uses the same guards. Sent records change only through `patch` (newest wins; `editDone`/`editFailed` touch only the patch that was sent).

**Uploads:** no response marks the batch `uncertain`, and `recordExists` checks Wallet (account, amount, ±2 min) before resending. 401/403 sets `authError`. Per-item results apply per row.

**App lock / edge-to-edge:** `checkLock` in `onStart` after 60 s away; content hidden until success; any error finishes the screen. Every screen calls `applyInsets(root, scroll)`; app bars use `fitsSystemWindows`; screens with text fields use `adjustResize`; no `statusBarColor` in themes.

## Wallet API notes

- Base `https://rest.budgetbakers.com/wallet`, paths `/v1/api/...`. Read the spec (`/wallet/openapi`) instead of guessing; it's long, so ask about specific endpoints.
- Premium only, about 300 requests/hour. 409 = first sync running; 401/403 = bad token. Amount sign = income/expense. Lists paginate with `nextOffset`. POST max 50; PATCH and DELETE (`{"ids": [...]}`) max 10, answers per item (`results`, `errorType`); a transfer's two halves must be deleted together.

## Conventions

- Match the surrounding code: small functions, short comments that say why, KDoc on non-obvious functions, plain English UI text.
- New pure logic goes in a pure object with a JUnit test.
- Schema change: bump the `Store` version, add an `if (old < N)` block in `onUpgrade`, never drop data. New setting: add it to `exportJson`/`importJson`.
- New screen: extend `LockedActivity`, call `applyInsets`, `exported="false"`. Components stay unexported unless a system permission protects them; `PendingIntent`s immutable.
- Colours via theme attributes, `Tone` or `colors.xml`, never hex in Kotlin. Check light and dark.
- Files are LF everywhere (`.gitattributes`; only `.bat` is CRLF). Prefer the Edit tool; script patches go in the scratchpad.
- Keep this file current in the same commit as a change that makes it wrong, and short: details belong in KDoc.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `<type>(<scope>): <imperative summary, lower case>`. Scopes: `capture`, `parser`, `upload`, `categories`, `ui`, `settings`, `lock`, `build`. End with the session's `Co-Authored-By` trailer. Don't rewrite history.

## Working with the owner

- Take the lead on tooling, refactors, tests and docs, and commit that work. Ask before user-visible product changes, larger features, new app libraries, pushing or releasing; offer to commit product work when a coherent set is ready.
- They test on their own phone with real banks; screenshots may contain real values (see the data rules).
- Explain Android Studio steps concretely (menu paths, what to click); they're not an Android specialist.

## Status as of 2026-10-30

- Released history is in `Changelog.kt`. 1.6 (versionCode 12) is not on Play yet; new changes join its entry until the owner says it's ready.
- Checked on the emulator only (demo mode): the 1.6 home screen and sheet, light and dark.
- Unverified on a real phone: Edit in Wallet and Remove from Wallet (`PATCH`, and `DELETE` with a body, through HttpURLConnection; Wallet's answers), the sheet's upload pause, whether `pairingMode: existing` turns the sent side into a transfer, notification buttons and Change after the app lock, the 9 pm alarm, the disclosure dialog and battery page, app lock rotation, labels on created records.
- Waiting on the owner: dummy samples of non-DuitNow transfer alerts; the Play Console forms and 12-tester closed test; decisions on the USD estimate, the note default and a wider duplicate check.
