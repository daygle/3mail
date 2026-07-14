# 3mail

A modern, full-featured Android mail client supporting IMAP and Gmail (OAuth2).

## Features (MVP)

- Multiple account support (IMAP + Gmail)
- Modern Material 3 UI with Jetpack Compose
- Inbox, compose, reply, forward
- Offline caching with Room
- Background sync with WorkManager
- Push notifications for new mail
- Full-text search across cached messages

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

- OAuth tokens and IMAP passwords are stored in the local Room database in this MVP. For production, migrate sensitive credentials to Android Keystore / EncryptedSharedPreferences.
- Gmail requires OAuth2; app passwords are no longer recommended by Google.

## Next Steps

- Add proper OAuth2 token refresh flow
- Implement Gmail-specific sync via Gmail API
- Add attachment download / upload
- Add threading and conversation view
- Add swipe actions in the inbox list
