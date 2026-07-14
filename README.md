# 3mail

A modern, full-featured Android mail client supporting IMAP and Gmail (OAuth2).

## Features

- Multiple account support (IMAP + Gmail via OAuth2)
- Modern Material 3 UI with Jetpack Compose — dynamic color (Material You), light/dark/system themes, sender avatars, swipe-to-archive/delete, navigation drawer with folders
- Full message reading: HTML bodies rendered in a WebView (remote images blocked by default), plain-text fallback, on-demand body fetch
- Compose, reply, reply-all, and forward with quoting and `Re:`/`Fwd:` handling
- Attachments: view and download incoming attachments; attach files to outgoing mail
- Two-way sync of read/star flags and delete/archive/move actions (local + IMAP server)
- Conversation threading derived from `References`/`In-Reply-To` headers
- Drafts saved to the server's Drafts folder
- Native Gmail sync via the Gmail REST API (labels as folders, server-side threads, label-based read/star), with IMAP/SMTP for all other accounts — selected automatically per account behind a `MailRemote` abstraction
- Rich-text compose: bold/italic/lists/links toolbar, sent as multipart/alternative (plain + HTML)
- Built-in Google Calendar: agenda view of upcoming events and create/delete events
- Configurable signature, sync frequency, notifications, theme, and empty-trash-on-open in Settings
- Incremental background sync with WorkManager (UID cursor for IMAP, internalDate cursor for Gmail)
- Notifications for new mail
- Full-text search across cached messages
- Encrypted credential storage (Android Keystore via EncryptedSharedPreferences)
- Optional **Empty trash on launch / quit** (Settings → Trash) — server-first `EXPUNGE` then local Room prune, per active IMAP/Gmail account, dispatched through WorkManager so it survives process death on background
- **Google Calendar** integration (Drawer → Calendar) — month-grid + per-day agenda, full event create/edit/delete via Google Calendar API, Room-cached for offline viewing, periodic WorkManager sync rolling ~6 months back / ~12 weeks forward
- **Google Calendar** with full read/write scope, material-3 month grid, day agenda, and event create/edit (Material 3 `DatePicker`/`TimePicker`). Per-account `calendarSyncEnabled` flag and a `CalendarSyncWorker` keep the cache warm in the background

## Tech Stack

- Kotlin + Jetpack Compose
- Hilt (dependency injection)
- Room (local database)
- WorkManager (background sync)
- JavaMail / IMAP for mail sync
- Google Sign-In (OAuth2) for Gmail

## Setup

1. Open the project in Android Studio (latest stable version recommended).
2. Generate the Gradle wrapper if it does not exist:
   ```bash
   gradle wrapper
   ```
   Or let Android Studio handle it automatically.
3. Replace `YOUR_WEB_CLIENT_ID` in `app/src/main/res/values/strings.xml` with your OAuth 2.0 Web Client ID from the [Google Cloud Console](https://console.cloud.google.com/).
4. Build and run on an Android device or emulator.

## Architecture

- `data/` — local database, remote IMAP/Gmail clients, repositories
- `domain/` — domain models
- `ui/` — Jetpack Compose screens, ViewModels, and components
- `sync/` — WorkManager background sync
- `notifications/` — notification channels and helpers
- `di/` — Hilt modules

## Security Notes

- IMAP passwords are stored via `EncryptedSharedPreferences` backed by an Android Keystore master key (see `data/security/CredentialStore.kt`), not as plaintext columns in the database.
- Gmail uses OAuth2 access tokens fetched on demand; app passwords are no longer recommended by Google.
- HTML message bodies load with remote images blocked by default to limit tracking pixels.

## Next Steps

- Multi-account calendar tab strip (currently shows the first calendar-enabled Gmail account)
- Native Gmail API sync for labels and server-side threading (currently threading is header-derived and works across all IMAP accounts)
- Rich-text compose and inline images
- Contact autocomplete for recipients
- Unified inbox across accounts
- Instrumented UI tests
