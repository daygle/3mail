# 3mail

A modern, full-featured Android mail client supporting IMAP (push), Gmail (OAuth2), and POP3, with a built-in Google Calendar tab.

[![Android CI](https://github.com/daygle/3mail/actions/workflows/android.yml/badge.svg)](https://github.com/daygle/3mail/actions/workflows/android.yml)
[![Platform: Android 8.0+](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/8.0)
[![Min SDK 26](https://img.shields.io/badge/minSdk-26-3DDC84)](https://developer.android.com/about/versions/8.0)
[![Target SDK 35](https://img.shields.io/badge/targetSdk-35-3DDC84)](https://developer.android.com/about/versions/15)
[![License: MIT](https://img.shields.io/github/license/daygle/3mail)](LICENSE)


## Features

- **Multi-account**: IMAP (with IDLE push notifications), Gmail via OAuth2, and POP3 - selected per account behind a `MailRemote` abstraction, so each account picks the right transport automatically. POP3 is inbox-only with local-only read flags (no server folders or push), and still sends over SMTP.
- **Unified inbox**: an all-accounts inbox view (drawer entry when more than one account is configured) backed by a reactive cross-account query over every INBOX folder.
- **Multi-select triage**: long-press to enter selection mode, then batch archive / delete / mark read-unread / select-all from a contextual app bar; plus a mark-all-read action and Material 3 pull-to-refresh.
- **Configurable swipe & density**: pick the left/right swipe action (none / archive / delete / read-unread), message-list density (comfortable / compact), and body-preview line count in Settings.
- **Send-as identities**: multiple sender aliases per account with per-identity signatures, chosen from the composer's From selector; plus optional read-receipt (Disposition-Notification-To) requests.
- **Folder drawer & visibility**: tap a folder to select and instantly auto-sync its contents, or long-press to add / remove it from favourites via a small dropdown menu. A Manage folders screen hides folders from the drawer while keeping them synced.
- **OpenPGP encryption (currently dormant)**: the OpenKeychain-brokered integration is preserved in source for future restoration, but the active dependency has been dropped and `OpenPgpController` is a stub: every operation funnels through `PgpResult.Unavailable` / `false`, so the compose Encrypt toggle stays hidden and message-view never enters its decrypt path. The app degrades to plaintext transparently until a working upstream `openpgp-api` coordinate resurfaces; see [`data/crypto/OpenPgpController.kt`](app/src/main/java/com/threemail/android/data/crypto/OpenPgpController.kt) for the restoration doc-comment.
- **Native Gmail sync**: Gmail REST API for labels-as-folders, server-side threads, and label-based read; IMAP/SMTP for everything else.
- **Modern UI**: Material 3 + Jetpack Compose with dynamic color (Material You), light/dark/system themes, sender avatars, swipe-to-archive/delete, and a folder navigation drawer.
- **Full message reading**: HTML bodies rendered in a `WebView` (remote images blocked by default), plain-text fallback, and on-demand body fetch.
- **Compose, reply, reply-all, and forward** with quoting and `Re:`/`Fwd:` handling.
- **Rich-text compose**: bold / italic / lists / links toolbar, **inline images** sent as `multipart/related` with `cid:` references and `Content-Disposition: inline`, and the body encoded as multipart/alternative (plain + HTML).
- **Contact autocomplete** for To/Cc/Bcc: tap-to-complete from system contacts, debounced, scoped to the typed-after-last-comma segment. `READ_CONTACTS` is requested on the user's first interaction with a recipient field (not on screen mount).
- **Attachments**: view and download incoming attachments; attach files to outgoing mail.
- **Two-way sync**: read flags and delete/archive/move actions mirror between local Room and the IMAP/Gmail server.
- **Conversation threading** derived from `References`/`In-Reply-To` headers, plus server-side Gmail threads when available.
- **Drafts** saved to the server's Drafts folder.
- **Offline outbox**: composed mail is queued locally and delivered by a `SendMailWorker` with network-constrained retry, so a send survives connectivity loss and process death instead of being lost on a failed immediate call.
- **Full-text search** across cached messages.
- **Google Calendar** integration - read/write via the Google Calendar API, Material 3 month grid, per-day agenda, event create/edit via `DatePicker`/`TimePicker`. **Multi-account filter chip strip** above the grid whenever multiple Google accounts have `calendarSyncEnabled` enabled - scope to one account or aggregate all. Room-cached for offline viewing, kept warm by a periodic `CalendarSyncWorker` (~6 months back / ~12 weeks forward). Per-account `calendarSyncEnabled` flag.
- **Notifications** for new mail, plus a launcher-badge counter.
- **Push for IMAP**: a foreground `ImapIdleService` keeps an idle connection per account, drives immediate sync on `IdleEvent.NewMail`, and survives process death via `START_STICKY`.
- **Incremental background sync**: `MailSyncWorker` with UID pagination for IMAP and `internalDate` cursor for Gmail.
- **Encrypted credential storage** via direct **Android Keystore** (AES-256/GCM, 12-byte IV, per-account email as AAD) over a base64-encoded `SharedPreferences` file (`threemail_credentials_v2`), replacing the deprecated `androidx.security.crypto.EncryptedSharedPreferences` (see [`data/security/CredentialStore.kt`](app/src/main/java/com/threemail/android/data/security/CredentialStore.kt)).
- **Per-account signature**, sync frequency, push, notifications, theme, and **Empty trash on launch / quit** (server-first `EXPUNGE` then local Room prune, dispatched through WorkManager so it survives process death on background).
- **Per-account settings**: each account has its own settings screen (open it from the Accounts list) for a per-account **signature** (blank = no signature on outgoing mail), a **mail-check frequency** override (a dedicated periodic `MailSyncWorker` per account; "Default" follows the app-wide interval), and per-account **sync**, **notifications**, and IMAP **push** toggles. The settings pages are built from a shared Material 3 grouped-card component set (`ui/components/SettingsComponents.kt`).
- **Launcher badge** counter for unread mail.

## Tech Stack

- **Kotlin** 2.3.10 + **Jetpack Compose** (Compose BOM 2026.06.01)
- **Hilt** 2.60.1 for dependency injection. Note: `androidx.hilt:hilt-work` is intentionally **not** used - see [`sync/ThreeMailWorkerFactory.kt`](app/src/main/java/com/threemail/android/sync/ThreeMailWorkerFactory.kt) for the manual `WorkerFactory` dispatch that replaces `@HiltWorker` under KSP2.
- **Room** 2.8.4 (local database at schema version 17; migrations `MIGRATION_4_5` … `MIGRATION_16_17` in [`Migrations.kt`](app/src/main/java/com/threemail/android/data/local/migrations/Migrations.kt)). Schema JSON export is currently disabled (see note in `app/build.gradle.kts`) - so `app/schemas/` holds the last exported versions, 5-7, rather than the live schema.
- **WorkManager** 2.11.2 for background sync (manual worker dispatch via `ThreeMailWorkerFactory`)
- **DataStore** 1.2.1 (preferences)
- **JavaMail (`android-mail`)** 1.6.8 + Apache Commons Net 3.13.0 for IMAP / SMTP
- **Google Sign-In** via **Credential Manager** + **Google Identity Services** (`com.google.android.libraries.identity.googleid`) for the Gmail / Calendar OAuth2 flow
- **Gmail REST API** + **Google Calendar API** for Gmail/Calendar features
- **Coil** 2.7.0 for Compose image loading (avatars, attachments)
- **Direct Android Keystore** (`AES/GCM/NoPadding` with 256-bit key + per-email AAD) + base64-encoded `SharedPreferences` for credential storage. Replaces the deprecated `androidx.security.crypto.EncryptedSharedPreferences` - see [`data/security/CredentialStore.kt`](app/src/main/java/com/threemail/android/data/security/CredentialStore.kt).
- **JUnit 4** + **Robolectric** 4.16.1 + **kotlinx-coroutines-test** for JVM unit tests
- **Android Gradle Plugin** 9.3.0, Gradle 9.6.1, Java 17, KSP 2.3.10

## Setup

1. **Android Studio**: open the project (latest stable recommended).
2. **Android SDK levels** (set in `app/build.gradle.kts`): `minSdk = 26` (Android 8.0), `targetSdk = 35` (Android 15), `compileSdk = 37` (compileSdk is decoupled from targetSdk - several AAR metadata entries in the dependency tree require `minCompileSdk ≥ 36`, but `targetSdk` stays at 35 so runtime behaviour is unchanged).
3. **Gradle wrapper**: `gradle-wrapper.jar` and `gradle-wrapper.properties` are committed; CI pins Gradle **9.6.1** to match.
4. **OAuth Web Client ID**: replace `YOUR_WEB_CLIENT_ID` in `app/src/main/res/values/strings.xml` with your OAuth 2.0 Web Client ID from the [Google Cloud Console](https://console.cloud.google.com/). Required for Gmail sign-in and the Google Calendar API.
5. **Build**: `./gradlew assembleDebug` (or run from Android Studio). JVM unit tests: `./gradlew testDebugUnitTest`. Android lint: `./gradlew lintDebug`.

## Architecture

- `data/local` - Room database (schema v17), DAOs, entities, and `Migrations.kt` (live migrations `MIGRATION_4_5` … `MIGRATION_16_17`). Last exported schema JSON under `app/schemas/` (versions 5-7); export is currently disabled, see the comment in `app/build.gradle.kts`.
- `data/remote/imap` - JavaMail-backed IMAP client (`ImapClient`, `ImapClientFactory`, `ImapRemote`).
- `data/remote/pop3` - JavaMail-backed POP3 client (`Pop3Client`, `Pop3Remote`); shared MIME walking in `data/remote/MimeParsing.kt`.
- `data/remote/gmail` - Gmail REST API client, OAuth helper, recoverable-auth handling.
- `data/remote/calendar` - Google Calendar API client.
- `data/remote/idle` - IMAP IDLE loop (`IdleLoop`), events (`IdleEvent`), folder ops (`IdleFolderOps`).
- `data/repository` - `AccountRepository`, `MailRepository`, `MailActions`, `CalendarRepository`, `OutboxRepository`, `ContactRepository`.
- `data/security` - `CredentialStore` (direct Android Keystore AES-256/GCM with per-email AAD, stored in a regular `SharedPreferences` file `threemail_credentials_v2`).
- `data/settings` - `SettingsRepository` (DataStore-backed preferences).
- `domain/model` - pure-Kotlin domain types.
- `ui/screens` - Compose screens and ViewModels (inbox, message, compose, search, calendar, account, add-account, account-settings, folder-management, settings).
- `ui/components` & `ui/theme` - shared Compose widgets + Material 3 theme/typography/color.
- `ui/navigation` - nav graph (`ThreeMailNavHost`, `Screen`).
- `sync` - `MailSyncWorker`, `CalendarSyncWorker`, `TrashCleanupWorker`, `SendMailWorker`, `SyncScheduler`, and `ThreeMailWorkerFactory` (manual `WorkerFactory` dispatch, see below).
- `push` - `PushController`, `ImapIdleService` (foreground `dataSync` service with one IDLE job per account, fixed-backoff reconnect, and `START_STICKY` resume), and `BootReceiver` (re-arms push after reboot).
- `notifications` - channels (`BadgeNotifier`), helpers (`NotificationHelper`), launcher badge (`LauncherBadge`).
- `di` - Hilt `AppModule`.

### WorkManager wiring

`androidx.hilt:hilt-work` compiles `@HiltWorker` AssistedFactory + multibinding entries against KSP1, so under KSP2 it silently emits nothing, making `HiltWorkerFactory.createWorker()` return null and crashing every worker instantiation with `NoSuchMethodException` on `ReflectiveWorkerFactory` fallback. `androidx.hilt:hilt-work` 1.5.0 has never been published, and KSP 2.3.10 dropped the KSP1 opt-out (`ksp.useKSP2=false` now hard-fails project configuration).

The fix, [`sync/ThreeMailWorkerFactory.kt`](app/src/main/java/com/threemail/android/sync/ThreeMailWorkerFactory.kt), is an explicit `when (workerClassName)` dispatch over the four `CoroutineWorker` subclasses (`MailSyncWorker`, `CalendarSyncWorker`, `SendMailWorker`, `TrashCleanupWorker`), each receiving its dependencies through Dagger `Provider<T>` fields. To add a new worker: declare it with a plain `(Context, WorkerParameters, …deps)` constructor and add a new `when` arm - no annotations, no codegen.

## Security Notes

- IMAP passwords are stored in a regular `SharedPreferences` file (`threemail_credentials_v2`), encrypted with **AES-256/GCM** under a per-device key held in the **Android Keystore** (`AndroidKeyStore` provider, alias `threemail_master_key`). See [`data/security/CredentialStore.kt`](app/src/main/java/com/threemail/android/data/security/CredentialStore.kt). The lowercased account email is bound in as Additional Authenticated Data (AAD) so a ciphertext cannot be moved between accounts, and the 12-byte IV is prepended to the ciphertext (which already includes the 128-bit GCM auth tag) before base64 encoding. This replaced `androidx.security.crypto.EncryptedSharedPreferences`, which Google deprecated.
- IMAP/SMTP connections verify the server's TLS certificate against the hostname (`ssl.checkserveridentity`), which JavaMail leaves off by default, and require STARTTLS to succeed (rather than silently downgrading to plaintext) whenever encryption is enabled for the account. See [`data/remote/imap/ImapClient.kt`](app/src/main/java/com/threemail/android/data/remote/imap/ImapClient.kt).
- Gmail / Google Calendar use OAuth2 via **Credential Manager** + **Google Identity Services** (`com.google.android.libraries.identity.googleid`); the previous `playservices-auth` `GoogleSignInClient` flow has been replaced. App passwords are no longer recommended by Google.
- HTML message bodies load with remote images blocked by default to limit tracking pixels.
- Credential prefs (`threemail_credentials_v2`) are excluded from cloud backup AND device transfer via `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`. The Android Keystore master key isn't transferable to a new device, so a cloud restore of this file would silently fail to decrypt and force the user to re-enter every IMAP password. Other prefs (settings, FTS state) still transfer normally.
- `local.properties` is intentionally committed with a developer-specific `sdk.dir`; CI overrides it via `echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties`.

## Continuous Integration

`.github/workflows/android.yml` runs on push/PR to `main` (ubuntu-latest):

- JDK 17 (Temurin) + Gradle 9.6.1 (via `gradle/actions/setup-gradle@v6`)
- `./gradlew testDebugUnitTest --stacktrace` (Robolectric + JVM tests)
- `./gradlew assembleDebug --stacktrace`
- `./gradlew lintDebug --stacktrace` (currently `continue-on-error: true` - the project has no lint baseline yet, so pre-existing findings shouldn't turn CI red on first run)
- Uploads `app-debug` APK, unit test reports, and `lint-results-*` as workflow artifacts

## Next Steps

- Instrumented UI tests (Espresso / Compose UI test).
- OpenPGP: restore the upstream `openpgp-api` dependency and bring the OpenKeychain-brokered inline-PGP sign/encrypt + decrypt/verify path back online (currently stubbed), then layer PGP/MIME (encrypting attachments + structured bodies) and Autocrypt header exchange on top.

## License

Licensed under the MIT License - see [`LICENSE`](LICENSE) at the repo root. Copyright © 2026 daygle.
