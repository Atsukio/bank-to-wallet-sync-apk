# AGENTS.md

Android app "Bank to Wallet" (package `app.banksync`): reads bank-app notifications (e.g. CIMB OCTO MY, Ryt Bank, TNG eWallet, MAE/Maybank2u) and adds them as records in BudgetBakers Wallet through its REST API. Sibling of the Chrome extension in `../bank-to-wallet-sync` (same owner, same Wallet API, same category logic). Sideloaded for the owner's own use, and being prepared for Google Play (closed testing first); see `store/LISTING.md`.

Priorities, in order: battery and data (nothing runs unless a notification arrives or an upload is due), never losing or duplicating a transaction (offline queue with retry), then a clean Material 3 UI.

## Data and security rules (read first)

- **Never read, print, log or copy the owner's Wallet API token**, `local.properties`, keystores or any `.env`. The token lives only on the phone, AES-GCM encrypted with a Keystore key (`Settings.kt`).
- Test data is **synthetic only**: made-up merchants, amounts and account numbers. Never ask for real notifications; ask for dummy ones in the bank's format. If the owner pastes or screenshots real transactions, don't repeat or store the values.
- Release APK size, test counts and build output are fine to report.

## Layout

Single module `app/`. Kotlin, **Android Views with Material Components 1.12 (Material 3), not Compose**. minSdk 26, target/compile 36.

| File | Role |
| --- | --- |
| `BankNotificationListener.kt` | `NotificationListenerService`. Package check first (only apps with a rule), ignore words, parse, pick account and category, insert into `Store`, schedule upload. `catchUp()` scans notifications still in the shade. `handle` is `synchronized` so a scan and a live alert can't both insert. |
| `Parser.kt` | Regex-only `Parser.parse(text)` → `Parsed(amount signed, payee, currency)` or null when unsure (then the row is UNPARSED, fixed by hand). `recordTime` uses the alert's own time when plausible. `walletText` decides what Wallet gets at upload: only a sender ("from X") is a Wallet payee; shops ("at X") and people paid ("to X") go in the note (`paidTo`). `note` builds the Wallet note (who was paid, ref, foreign amount; no card/account number; nothing to say means no note); the stored `note` column keeps the full alert for the Fix dialog and category picking. |
| `Rules.kt` | `Rule` (bank app → Wallet account, optional `match` text such as last 4 digits), `RuleMatcher`, `IgnoreWords`, `CatchUp.cutoff`, `CategoryRule`/`CategoryMatcher`. Pure, unit-tested. |
| `Categorizer.kt` | Pure category picking ported from the extension: `Merchant` keys, `Categorizer.pick`, Wallet rule matching, history guess. Unit-tested. |
| `Changelog.kt` | User-facing release notes, newest first, shipped in the app. "What's new" shows once after an update (`seenVersion`; never on a fresh install); Settings › About shows them all. Unit-tested. |
| `WalletCache.kt` | `wallet_cache.json` in `filesDir`: Wallet record rules, categories and categorised history, used offline at capture time. `refresh` only runs when an upload is already online (rules ≤ hourly, history ≤ 12 h) or forced after a token check. |
| `Store.kt` | SQLite queue `queue.db` (version 4). Statuses `PENDING`, `SYNCED`, `FAILED`, `UNPARSED`, `REVIEW`. Raw text is dropped once synced; history purged after `keepDays`. `get` is always the real queue (listener, uploader, `ReviewReceiver`); screens use `shown`, which is the in-memory demo queue while `Demo` runs. |
| `Demo.kt` | Demo mode for trying the app and for Play reviewers: made-up rules, categories and alerts (run through the real `Parser`) in an in-memory queue. Never saved, never touches real settings, nothing uploaded; screens guard `Uploader.schedule`, `WalletCache` and `rememberCategory` with `Demo.active`. Started from the menu or the guide's first step. |
| `Uploader.kt` | JobScheduler job (`UploadJobService`): network constraint (Wi-Fi only optional), batches of 50, backoff, `uncertain` handling via `WalletApi.recordExists` because Wallet has no duplicate protection. |
| `WalletApi.kt` | `HttpURLConnection` client: post records, accounts, categories, record rules, history, `recordExists`. |
| `Settings.kt` | SharedPreferences wrapper, encrypted token, rules, category rules, `categoryMemory`, export/import JSON. |
| `Notifier.kt` | Three channels: alerts (token, failed sends, and one counting "not understood" alerts, cleared once none are left), "ask before sending" review notifications with Send/Discard (`ReviewReceiver`), and a silent "picked up" one per captured transaction (`capturedOn`, the latest replaces the last, no details when `hideDetails`). Private on the lock screen. Event-driven only. |
| `Permissions.kt` | Notification access, listener rebind, battery. Every "turn on access" button goes through `askAccess`, the prominent disclosure Play requires (what is read, where it goes, "Agree and continue"). Battery opens the app's info page (Battery › Unrestricted); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was removed because Play restricts it, so don't add it back. |
| `PauseTileService.kt` | Quick Settings tile to pause capture. |
| `LockedActivity.kt` | Base of every screen: app lock (`BiometricPrompt`, BIOMETRIC_WEAK or device credential, 60 s grace, process-wide `started`/`locking`), `enableEdgeToEdge()`, `applyInsets`, notification permission flow. |
| `MainActivity.kt` | Home: hero status card (worst problem first), check cards, count tiles, recent activity (tap a row to review/edit/fix/retry; eye button sets `hideDetails` to mask payees, amounts and categories). |
| `SettingsActivity.kt` | Auto-saving settings: switches save immediately, text fields in `onPause`. Wallet token card, bank apps, categories, capture, uploads, privacy. |
| `OnboardingActivity.kt` | Setup guide; the step reached is saved. |
| `Dialogs.kt` / `Pickers.kt` | Record/fix/test dialogs, `CategoryChooser`, app and account pickers, list rows. |
| `Ui.kt` | `Tone` (GOOD/NEUTRAL/WARN/BAD → M3 container roles), `themeColor`, `ImageView.badge`. |
| `res/values{,-night}/themes.xml` | Teal M3 palette and the `Widget.BankSync.*` styles. Change both files together. |
| `app/src/test/` | JUnit 4: `ParserTest`, `RuleMatcherTest`, `CategorizerTest`, `ChangelogTest`, `DemoTest`. |
| `PRIVACY.md` | Privacy policy; its public URL goes in Play Console. Keep it true to what the code does, and update it in the same change as anything that alters what is read, stored or sent. |
| `store/` | Play listing text and form answers (`LISTING.md`), `play-icon-512.png`, `feature-graphic-1024x500.png`, and `make-graphics.mjs`, which renders both from the extension's icon with its Puppeteer and sharp (`CHROME_PATH` to Brave; Edge exits silently here). |
| `.claude/skills/` | Google's Android skills (github.com/android/skills), git-ignored, local only. Many assume Compose; adapt them to Views. |

## Build and test (Windows)

The command-line launcher needs JDK 17; Gradle itself runs on JetBrains Java 21 via the daemon toolchain (`gradle/gradle-daemon-jvm.properties`, foojay resolver).

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --no-daemon --console=plain
```

- Check results in `app/build/test-results/testDebugUnitTest/*.xml` and the APKs in `app/build/outputs/apk/{debug,release}/`. Report failures with their output.
- Don't run `clean`; the build and configuration caches make reruns take seconds.
- The IDE's red "errors" on words like `val`, `banksync`, `androidx` are a spell checker, not compile errors. Only Gradle output counts.
- After build file changes, the owner should also run **File › Sync Project with Gradle Files** in Android Studio (can't be run from here).

## Toolchain rules (AGP 9)

- AGP 9.4.1, Gradle 9.6.0, **built-in Kotlin** (AGP bundles KGP 2.2.x). Do **not** add `org.jetbrains.kotlin.android` or a `kotlinOptions`/`kotlin { }` block; the JVM target follows `compileOptions` (17).
- `gradle.properties` holds only the jvmargs, build cache, configuration cache, `useAndroidX` and `nonTransitiveRClass`. Don't add AGP 8 compatibility flags (`android.builtInKotlin`, `android.newDsl`, etc.).
- Configuration cache is on: build logic must not read files, env vars or system properties at configuration time without Gradle's providers.
- Dependencies are deliberately few: Material and `androidx.biometric` (UI only), JUnit for tests. The background path (listener, parser, SQLite, JobScheduler, HttpURLConnection) uses framework APIs only. Ask before adding a library; never add one to the background path.
- **Versioning:** when the owner asks for a release (or a batch of user-visible changes is ready to install), bump `versionCode` and `versionName` in `app/build.gradle.kts` and add a matching entry at the top of `Changelog.releases`, in plain English for the owner, not commit messages. Without the bump, "What's new" never shows.
- Release: R8 with resource shrinking, no custom keep rules (none needed, no reflection). Signed with the Play upload key described in `keystore.properties` at the repo root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; git-ignored, read through `providers.fileContents`); without that file it falls back to the debug key, which Play rejects. Never open or print `keystore.properties` or the keystore. Play takes the `.aab` from `bundleRelease`.

## How things work

**Capture:** listener → `RuleMatcher.pick` → ignore words → `Parser.parse` (null → UNPARSED) → `Store.isDuplicate` (hash, 2 min) → `WalletCache.pick` → insert as PENDING, or REVIEW with an "ask before sending" notification → `Uploader.schedule` (no delay by default, so it goes once there's a network; an optional delay in Settings sends bursts as one request).

**Categories:** remembered manual choice (`categoryMemory`, keyed by `Merchant.memoryKey`) → app `CategoryRules` → Wallet record rules (longest keyword, labels, same account) → history guess (same description, else merchant key, same income/expense type, most common). Records with no category are re-picked just before upload. `CategorySource` is stored so the UI can say why.

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

## Status as of 2026-10-24

- Done: capture and offline queue, catch-up scan, review mode, extension-style categories with Wallet rules and labels, redesigned home and settings, edge-to-edge, AGP 9 migration, BiometricPrompt app lock, configuration cache. 1.2 (versionCode 3) prepares for Play: demo mode, the notification-access disclosure, no battery-exemption permission, upload-key signing, `PRIVACY.md` and `store/`.
- Unverified on a real phone: demo mode, the disclosure dialog and the battery settings page (the emulator's system image is missing), the whole redesign (light/dark, keyboard, landscape), Learned-from-Wallet counts, labels on created records, app lock with `BiometricPrompt` (including rotation while the prompt is up), the permanently-refused notification permission dialog.
- Waiting on the owner: dummy CIMB and TNG notification samples for `ParserTest`; the upload keystore and `keystore.properties`; a contact email in `PRIVACY.md` and a public URL for it; demo-mode phone screenshots; the Play Console forms and 12-tester closed test (see `store/LISTING.md`); decisions on the USD estimate, the note default, undo after sending, and a wider duplicate check.
