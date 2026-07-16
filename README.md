# 3mail

A modern, full-featured Android mail client supporting IMAP (push) and Gmail (OAuth2), with a built-in Google Calendar tab.

[![Android CI](https://github.com/<user>/3mail/actions/workflows/android.yml/badge.svg)](.github/workflows/android.yml)

<!-- Replace `<user>` with the GitHub owner to make the badge live. -->


## Features

- **Multi-account**: IMAP (with IDLE push notifications) and Gmail via OAuth2 — selected per account behind a `MailRemote` abstraction, so each account picks the right transport automatically.
- **Native Gmail sync**: Gmail REST API for labels-as-folders, server-side threads, and label-based read/star; IMAP/SMTP for everything else.
- **Modern UI**: Material 3 + Jetpack Compose with dynamic color (Material You), light/dark/system themes, sender avatars, swipe-to-archive/delete, and a folder navigation drawer.
- **Full message reading**: HTML bodies rendered in a `WebView` (remote images blocked by default), plain-text fallback, and on-demand body fetch.
- **Compose, reply, reply-all, and forward** with quoting and `Re:`/`Fwd:` handling.
- **Rich-text compose**: bold/italic/lists/links toolbar, sent as multipart/alternative (plain + HTML).
- **Attachments**: view and download incoming attachments; attach files to outgoing mail.
- **Two-way sync**: read/star flags and delete/archive/move actions mirror between local Room and the IMAP/Gmail server.
- **Conversation threading** derived from `References`/`In-Reply-To` headers, plus server-side Gmail threads when available.
- **Drafts** saved to the server's Drafts folder.
- **Full-text search** across cached messages.
- **Google Calendar** integration — read/write via the Google Calendar API, Material 3 month grid, per-day agenda, event create/edit via `DatePicker`/`TimePicker`. Room-cached for offline viewing, kept warm by a periodic `CalendarSyncWorker` (~6 months back / ~12 weeks forward). Per-account `calendarSyncEnabled` flag.
- **Notifications** for new mail, plus a launcher-badge counter.
- **Push for IMAP**: a foreground `ImapIdleService` keeps an idle connection per account, drives immediate sync on `IdleEvent.NewMail`, and survives process death via `START_STICKY`.
- **Incremental background sync**: `MailSyncWorker` with UID pagination for IMAP and `internalDate` cursor for Gmail.
- **Encrypted credential storage** via `EncryptedSharedPreferences` backed by Android Keystore (see `data/security/CredentialStore.kt`).
- **Configurable signature**, sync frequency, push, notifications, theme, and **Empty trash on launch / quit** (server-first `EXPUNGE` then local Room prune, dispatched through WorkManager so it survives process death on background).
- **Launcher badge** counter for unread mail.

## Tech Stack

- **Kotlin** 2.2.10 + **Jetpack Compose** (Compose BOM 2024.09.00)
- **Hilt** 2.60.1 for dependency injection (with `hilt-work` and `hilt-navigation-compose`)
- **Room** 2.8.4 (local database, schemas versioned under `app/schemas/`)
- **WorkManager** 2.9.0 for background sync
- **DataStore** 1.1.1 (preferences)
- **JavaMail (`android-mail`)** 1.6.7 + Apache Commons Net for IMAP / SMTP
- **Google Sign-In** (OAuth2) + **Gmail REST API** + **Google Calendar API** for Gmail/Calendar features
- **Coil** 2.7.0 for Compose image loading (avatars, attachments)
- **AndroidX Security Crypto** for encrypted credential storage
- **JUnit 4** + **Robolectric** 4.12.2 + **kotlinx-coroutines-test** for JVM unit tests
- **Android Gradle Plugin** 9.3.0, Gradle 9.6.1, Java 17, KSP 2.2.10-2.0.2

## Setup

1. **Android Studio**: open the project (latest stable recommended).
2. **Android SDK levels** (set in `app/build.gradle.kts`): `minSdk = 26` (Android 8.0), `targetSdk = 34` and `compileSdk = 34` (Android 14).
3. **Gradle wrapper**: the wrapper is committed. If you pull a version where `gradle-wrapper.jar` is missing, regenerate with `gradle wrapper`. CI uses Gradle **9.6.1**.
4. **OAuth Web Client ID**: replace `YOUR_WEB_CLIENT_ID` in `app/src/main/res/values/strings.xml` with your OAuth 2.0 Web Client ID from the [Google Cloud Console](https://console.cloud.google.com/). Required for Gmail sign-in and the Google Calendar API.
5. **Build**: `./gradlew assembleDebug` (or run from Android Studio). JVM unit tests: `./gradlew testDebugUnitTest`.

## Architecture

- `data/local` — Room database, DAOs, entities, and `Migrations.kt` (schemas checked in under `app/schemas/`).
- `data/remote/imap` — JavaMail-backed IMAP client (`ImapClient`, `ImapClientFactory`, `ImapRemote`).
- `data/remote/gmail` — Gmail REST API client, OAuth helper, recoverable-auth handling.
- `data/remote/calendar` — Google Calendar API client.
- `data/remote/idle` — IMAP IDLE loop (`IdleLoop`), events (`IdleEvent`), folder ops (`IdleFolderOps`).
- `data/repository` — `AccountRepository`, `MailRepository`, `MailActions`, `CalendarRepository`.
- `data/security` — `CredentialStore` (EncryptedSharedPreferences + Android Keystore).
- `data/settings` — `SettingsRepository` (DataStore-backed preferences).
- `domain/model` — pure-Kotlin domain types.
- `ui/screens` — Compose screens and ViewModels (inbox, message, compose, search, calendar, account, add-account, settings).
- `ui/components` & `ui/theme` — shared Compose widgets + Material 3 theme/typography/color.
- `ui/navigation` — nav graph (`ThreeMailNavHost`, `Screen`).
- `sync` — `MailSyncWorker`, `CalendarSyncWorker`, `TrashCleanupWorker`, `SyncScheduler`.
- `push` — `PushController` + `ImapIdleService` (foreground IDLE service).
- `notifications` — channels, helpers, launcher badge.
- `di` — Hilt `AppModule`.

## Security Notes

- IMAP passwords are stored via `EncryptedSharedPreferences` backed by an Android Keystore master key (see `data/security/CredentialStore.kt`), not as plaintext columns in the database.
- Gmail uses OAuth2 access tokens fetched on demand; app passwords are no longer recommended by Google.
- HTML message bodies load with remote images blocked by default to limit tracking pixels.
- `local.properties` is intentionally committed with a developer-specific `sdk.dir`; CI overrides it via `echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties`.

## Continuous Integration

`.github/workflows/android.yml` runs on push/PR to `main`:

- JDK 17 (Temurin) + Gradle 9.6.1
- `./gradlew testDebugUnitTest` (Robolectric + JVM tests)
- `./gradlew assembleDebug`
- Uploads `app-debug` APK and unit test reports as artifacts

## Next Steps

- Multi-account calendar tab strip (currently shows the first calendar-enabled Gmail account).
- Inline images in rich-text compose (the rich-text toolbar ships today; inline images do not yet).
- Contact autocomplete for recipients.
- Unified inbox across accounts.
- Instrumented UI tests (Espresso / Compose UI test).

## License

Licensed under the MIT License — see [`LICENSE`](LICENSE) at the repo root. Copyright © 2026 daygle.
