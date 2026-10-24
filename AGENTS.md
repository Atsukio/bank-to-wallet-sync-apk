# AGENTS.md

Android app "Bank to Wallet" (package `app.banksync`): reads bank-app notifications (e.g. CIMB OCTO MY, Ryt Bank, TNG eWallet, MAE/Maybank2u) and adds them as records in BudgetBakers Wallet through its REST API. Sibling of the Chrome extension in `../bank-to-wallet-sync` (same owner, same Wallet API, same category logic). Sideloaded for the owner's own use; not on Google Play.

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
| `Parser.kt` | Regex-only `Parser.parse(text)` → `Parsed(amount signed, payee, currency)` or null when unsure (then the row is UNPARSED, fixed by hand). `recordTime` uses the alert's own time when plausible. `note` builds the Wallet note at upload (payee, ref, foreign amount; no card/account number, the Wallet account already says that); the stored `note` column keeps the full alert for the Fix dialog and category picking. |
| `Rules.kt` | `Rule` (bank app → Wallet account, optional `match` text such as last 4 digits), `RuleMatcher`, `IgnoreWords`, `CatchUp.cutoff`, `CategoryRule`/`CategoryMatcher`. Pure, unit-tested. |
| `Categorizer.kt` | Pure category picking ported from the extension: `Merchant` keys, `Categorizer.pick`, Wallet rule matching, history guess. Unit-tested. |
| `WalletCache.kt` | `wallet_cache.json` in `filesDir`: Wallet record rules, categories and categorised history, used offline at capture time. `refresh` only runs when an upload is already online (rules ≤ hourly, history ≤ 12 h) or forced after a token check. |
| `Store.kt` | SQLite queue `queue.db` (version 4). Statuses `PENDING`, `SYNCED`, `FAILED`, `UNPARSED`, `REVIEW`. Raw text is dropped once synced; history purged after `keepDays`. |
| `Uploader.kt` | JobScheduler job (`UploadJobService`): network constraint (Wi-Fi only optional), batches of 50, backoff, `uncertain` handling via `WalletApi.recordExists` because Wallet has no duplicate protection. |
| `WalletApi.kt` | `HttpURLConnection` client: post records, accounts, categories, record rules, history, `recordExists`. |
| `Settings.kt` | SharedPreferences wrapper, encrypted token, rules, category rules, `categoryMemory`, export/import JSON. |
| `Notifier.kt` | Two channels: alerts, and "ask before sending" review notifications with Send/Discard (`ReviewReceiver`). Event-driven only. |
| `Permissions.kt` | Notification access, listener rebind, battery exemption; both are settings screens, not runtime prompts. |
| `PauseTileService.kt` | Quick Settings tile to pause capture. |
| `LockedActivity.kt` | Base of every screen: app lock (`BiometricPrompt`, BIOMETRIC_WEAK or device credential, 60 s grace, process-wide `started`/`locking`), `enableEdgeToEdge()`, `applyInsets`, notification permission flow. |
| `MainActivity.kt` | Home: hero status card (worst problem first), check cards, count tiles, bank apps, recent activity (tap a row to review/edit/fix/retry). |
| `SettingsActivity.kt` | Auto-saving settings: switches save immediately, text fields in `onPause`. Wallet token card, categories, capture, uploads, privacy. |
| `OnboardingActivity.kt` | Setup guide; the step reached is saved. |
| `Dialogs.kt` / `Pickers.kt` | Record/fix/test dialogs, `CategoryChooser`, app and account pickers, list rows. |
| `Ui.kt` | `Tone` (GOOD/NEUTRAL/WARN/BAD → M3 container roles), `themeColor`, `ImageView.badge`. |
| `res/values{,-night}/themes.xml` | Teal M3 palette and the `Widget.BankSync.*` styles. Change both files together. |
| `app/src/test/` | JUnit 4: `ParserTest`, `RuleMatcherTest`, `CategorizerTest`. |
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
- Release: R8 with resource shrinking, no custom keep rules (none needed, no reflection). It is **signed with the debug key**; a real keystore is still to do.

## How things work

**Capture:** listener → `RuleMatcher.pick` → ignore words → `Parser.parse` (null → UNPARSED) → `Store.isDuplicate` (hash, 2 min) → `WalletCache.pick` → insert as PENDING, or REVIEW with an "ask before sending" notification → `Uploader.schedule` (with the configured delay so bursts go as one request).

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

- Done: capture and offline queue, catch-up scan, review mode, extension-style categories with Wallet rules and labels, redesigned home and settings, edge-to-edge, AGP 9 migration, BiometricPrompt app lock, configuration cache.
- Unverified on a real phone: the whole redesign (light/dark, keyboard, landscape), Learned-from-Wallet counts, labels on created records, app lock with `BiometricPrompt` (including rotation while the prompt is up), the permanently-refused notification permission dialog.
- Waiting on the owner: dummy CIMB and TNG notification samples for `ParserTest`; a release keystore; decisions on the USD estimate, the note default, undo after sending, and a wider duplicate check.
