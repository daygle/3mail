package com.threemail.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds a standalone FTS4 virtual table (`messages_fts`) over the cached `messages`
 * table so the search screen can run real MATCH queries instead of LIKE wildcards.
 *
 * The triggers below must be present both for migrated v4 databases AND on fresh
 * v5 installs; the latter is taken care of by
 * [com.threemail.android.data.local.ThreeMailDatabase.freshInstallCallback].
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        FtsTriggers.install(db)
    }
}

/**
 * Adds a per-account `pushEnabled` column so IMAP IDLE push can be disabled
 * for a single account without turning off the global push master switch or
 * disabling mail sync. Existing rows default to 1 - i.e. optical backwards
 * compatibility: every account that was enrolled in push stays enrolled.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE accounts ADD COLUMN pushEnabled INTEGER NOT NULL DEFAULT 1"
        )
    }
}

/**
 * Reorders the unique composite index on `messages` so `folderId` is the
 * leading column. This both preserves the original uniqueness guarantee
 * (`(accountId, folderId, messageId)` triples are still unique - column order
 * is irrelevant to uniqueness) AND silences the KSP FK-index warning, because
 * Room's check only requires the FK child column to be at position 0 of some
 * `Index` annotation.
 *
 * Why not just add a redundant standalone `Index(value = ["folderId"])`?
 * That works in most places but the warning was observed to persist under
 * incremental-KSP-cache-replay on this host's gradle 8.7 config-cache path.
 * Folding folderId into the existing unique composite removes any ambiguity
 * and gives a single index that serves both uniqueness and FK enforcement.
 *
 * Steps:
 *  1. Drop the v6-era auto-generated index
 *     `index_messages_accountId_folderId_messageId` - Room's default name
 *     for the previous `(accountId, folderId, messageId) unique` annotation.
 *  2. Create the new ordered index
 *     `index_messages_folderId_accountId_messageId` to match the v7
 *     generated schema.
 *
 * `IF EXISTS` / `IF NOT EXISTS` make this safe on:
 *  - fresh v7 installs (the DROP is a no-op - the old index never existed),
 *  - resumed migrations (partial application is resumable).
 *
 * The post-migration `room_master_table` checksum must match the v7
 * generated schema. Room's KSP processor emits the same DOWNSTREAM checksum
 * as long as the index DDL line above matches the generated DDL.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Drop indexes left over from the previous design.
        //   * index_messages_accountId_folderId_messageId : the v6-era
        //     unique composite Room auto-generated from the previous
        //     `(accountId, folderId, messageId) unique` annotation. We're
        //     replacing it with a reordered composite.
        //   * index_messages_folderId : created by the prior MIGRATION_6_7
        //     in commit 8f9ac6d, which added a standalone
        //     `Index(value = ["folderId"])`. The v2 design folds folderId
        //     into the reordered unique composite instead, so this leftover
        //     becomes an orphan - Room's v7 schema won't declare it. Drop
        //     here so users coming from 8f9ac6d don't carry dead B-tree
        //     maintenance on every `messages` write.
        db.execSQL(
            "DROP INDEX IF EXISTS index_messages_accountId_folderId_messageId"
        )
        db.execSQL(
            "DROP INDEX IF EXISTS index_messages_folderId"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_messages_folderId_accountId_messageId " +
                "ON messages(folderId, accountId, messageId)"
        )
    }
}

/**
 * Adds per-account outgoing (SMTP submission) server + port columns. Before
 * this, the SMTP host was guessed from the incoming server (with a
 * `smtp.gmail.com` fallback), so generic IMAP providers whose SMTP host didn't
 * follow the naming convention couldn't send mail. Existing rows migrate with
 * `outgoingServer = NULL` (keep the guess) and the standard submission port
 * `587`, so behaviour is unchanged for accounts that were already working.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN outgoingServer TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE accounts ADD COLUMN outgoingPort INTEGER NOT NULL DEFAULT 587")
    }
}

/**
 * Adds the `outbox_messages` table backing the offline send queue. Compose
 * persists outgoing mail here and [com.threemail.android.sync.SendMailWorker]
 * drains it, so a send survives network loss / process death.
 *
 * `IF NOT EXISTS` keeps the migration resumable and a no-op on a fresh v9
 * install (where Room creates the table from the entity anyway).
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS outbox_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "accountId INTEGER NOT NULL, " +
                "toJson TEXT NOT NULL, " +
                "ccJson TEXT NOT NULL, " +
                "bccJson TEXT NOT NULL, " +
                "subject TEXT NOT NULL, " +
                "textBody TEXT NOT NULL, " +
                "htmlBody TEXT, " +
                "attachmentsJson TEXT NOT NULL, " +
                "inReplyTo TEXT, " +
                "referencesHeader TEXT, " +
                "createdAt INTEGER NOT NULL, " +
                "attemptCount INTEGER NOT NULL DEFAULT 0, " +
                "lastAttemptAt INTEGER, " +
                "lastError TEXT)"
        )
    }
}

/**
 * Adds per-account STARTTLS support. The legacy `useEncryption` boolean
 * covered only implicit SSL/TLS on the IMAPS port; STARTTLS-on-143 is what
 * most self-hosted providers expose, and without a column for it users had
 * to either accept cleartext or use a port the server didn't listen on.
 *
 * Adding the column (rather than replacing `useEncryption` with a TEXT enum)
 * is deliberate: SQLite column-type swaps force a table-rebuild migration
 * which drops and re-creates every index, FK, and trigger, and risks losing
 * cascading data. The [com.threemail.android.data.repository.AccountRepository]
 * mapper translates the (useEncryption, useStartTls) pair back into the
 * domain [com.threemail.android.domain.model.Security] enum and enforces the
 * "exactly one of the two is true" invariant in Kotlin, which is where it
 * belongs.
 *
 * Existing rows keep `useStartTls = 0` so behaviour is preserved: a row
 * that was on SSL/TLS still maps to `Security.SSL_TLS`, and a row that was
 * cleartext still maps to `Security.NONE`.
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE accounts ADD COLUMN useStartTls INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * Adds the `folder_favorites` side table for locally-tracked favorite folders.
 * Stored as a side table rather than an `isFavorite` column on `folders`
 * because [com.threemail.android.data.local.dao.FolderDao.insertAll] uses
 * [androidx.room.OnConflictStrategy.REPLACE], which would silently reset a
 * column-based flag to the freshly-fetched `false` default on every server
 * folder refresh.
 *
 * The composite primary key `(accountId, serverId)` matches the unique index
 * on [com.threemail.android.data.local.entity.FolderEntity], so the natural
 * cross-table identifier lines up. The `ON DELETE CASCADE` on the account FK
 * ensures removing an account (which already cascades its folders) also clears
 * its favorite entries. There is intentionally NO FK to `folders`: a server
 * sync that removes a folder row should not erase the user's favorite intent,
 * which simply re-attaches the next time the folder comes back.
 */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `folder_favorites` (" +
                "`accountId` INTEGER NOT NULL, " +
                "`serverId` TEXT NOT NULL, " +
                "PRIMARY KEY(`accountId`, `serverId`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_folder_favorites_accountId` ON `folder_favorites`(`accountId`)"
        )
    }
}

/**
 * Adds a per-favorite `position INTEGER NOT NULL DEFAULT 0` column to the
 * `folder_favorites` side table so the drawer's Favorites shortcut list can
 * be reordered by long-press drag.
 *
 * Existing rows default to `position = 0`; ordering falls back to ROWID
 * (insertion order / FIFO) via the DAO's `ORDER BY position ASC, rowid ASC`
 * query. No bulk backfill SQL is needed - ties at `position = 0` break by
 * rowid which always preserves insertion order.
 *
 * Why contiguous 0..N-1 integers and not sparse? With a favourites list of
 * typical size 3-10, minimizing writes per reorder is a premature
 * optimization - the simpler invariant "every favourite has a unique
 * 0..N-1 slot" is easier to reason about and to test. The reorder write is
 * wrapped in a [androidx.room.Transaction] inside
 * [com.threemail.android.data.local.dao.FolderDao.reorderFavorites], which
 * reassigns positions in one atomic UPDATE batch on drop release.
 *
 * `IF NOT EXISTS` is not valid for `ALTER TABLE … ADD COLUMN` (it's only
 * legal on `CREATE` statements) so this is a plain ALTER - but the column
 * has no FK, no index, no default side-effects, so partial migrations are
 * safe to resume.
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE folder_favorites ADD COLUMN position INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * Repair migration for users whose `messages` table carries the broken-v7
 * index layout that predates the fix in [MIGRATION_6_7].
 *
 * Background: a previous commit (8f9ac6d era) shipped a `MIGRATION_6_7`
 * that *added* a standalone `Index(value = ["folderId"])` without
 * dropping the v6 auto-generated unique composite on
 * `(accountId, folderId, messageId)`. That revision reached users on
 * `v7` with **both** indices coexisting plus the legacy column-order
 * composite. The followup fixed `MIGRATION_6_7` to drop both, but
 * [androidx.room.migration.Migration]s only fire for the
 * (oldVersion, newVersion) pair they declare, so users who already
 * passed v7 - including anyone who jumped from v7 straight to v11 /
 * v12 - carry the broken state forward. None of MIGRATION_7_8 through
 * MIGRATION_11_12 touch `messages` indices, so opening the DB at
 * v12 against the current Room schema re-validates with the entity's
 * 4-index layout and aborts with
 * `Migration didn't properly handle: messages`.
 *
 * Strategy: idempotent drop + create. Every statement tolerates either
 * the broken (5-index) state or the already-correct (4-index) state
 * - it is safe to re-run if the user later downgrades and re-upgrades,
 * and safe for users on a fresh v13 install (the `IF EXISTS` /
 * `IF NOT EXISTS` clauses turn into no-ops).
 *
 * Order matters only for safety: drop the old unique composite
 * (`accountId, folderId, messageId`) and the orphan `folderId`
 * standalone before creating the new ordered composite. SQLite
 * enforces foreign keys on the **parent** table (`folders.id`) - an
 * index on the **child** table's `folderId` is purely a perf
 * optimization - so dropping a child-column index never violates FK
 * constraints, and the new unique composite subsumes the FK-rowid
 * coverage that Room requires.
 *
 * The Room-generated DDL for v13 expects exactly:
 *  - `index_messages_folderId_accountId_messageId` UNIQUE
 *    on `(folderId, accountId, messageId)`
 *  - `index_messages_accountId_threadId`
 *  - `index_messages_date`
 *  - `index_messages_isRead`
 * so the post-migration `room_master_table` checksum must match.
 */
val MIGRATION_12_13: Migration = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        repairMessageIndices(db)
    }
}

/**
 * Repair migration for users stuck on a broken v13 schema (e.g. from
 * development builds) where the indices on `messages` do not match the
 * entity's declaration. Identical to [MIGRATION_12_13].
 */
val MIGRATION_13_14: Migration = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        repairMessageIndices(db)
    }
}

/**
 * Adds the per-account personalization columns backing the account settings
 * screen: a per-account `signature`, an optional `syncIntervalMinutes` override
 * (0 = follow the global default), and a per-account `notificationsEnabled`
 * toggle.
 *
 * All three are additive `ALTER TABLE … ADD COLUMN`s with defaults that
 * preserve existing behaviour:
 *  - `signature = ''` → the composer treats blank as "no signature" (no global fallback).
 *  - `syncIntervalMinutes = 0` → every account keeps the app-wide sync cadence
 *    until the user sets a per-account override.
 *  - `notificationsEnabled = 1` → accounts keep notifying exactly as before,
 *    still gated by the global master switch.
 *
 * `ADD COLUMN` cannot take `IF NOT EXISTS`, but none of the columns carry an
 * index, FK, or trigger, so a partially-applied migration is safe to resume.
 */
val MIGRATION_14_15: Migration = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN signature TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE accounts ADD COLUMN syncIntervalMinutes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE accounts ADD COLUMN notificationsEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

/**
 * Adds send-as identities and read-receipt support:
 *  - `accounts.identitiesJson` (default `[]`) stores extra send-as aliases.
 *  - `outbox_messages` gains `fromName` / `fromAddress` (identity override for
 *    the From header) and `requestReadReceipt` (adds a Disposition-Notification-To
 *    header when the message is sent).
 *
 * All additive `ALTER TABLE … ADD COLUMN`s with behaviour-preserving defaults:
 * no identities configured and no receipt requested keeps sending exactly as
 * before, from the account's primary address.
 */
val MIGRATION_15_16: Migration = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN identitiesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE outbox_messages ADD COLUMN fromName TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE outbox_messages ADD COLUMN fromAddress TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE outbox_messages ADD COLUMN requestReadReceipt INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Adds `folders.isHidden` backing the folder-visibility management screen.
 * Default `0` keeps every existing folder visible, so the drawer looks
 * identical until the user hides something.
 */
val MIGRATION_16_17: Migration = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE folders ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Adds the per-account folder-role override column backing the
 * `account_settings_folder_roles` UI section. Defaults to `"{}"` so existing
 * accounts keep the heuristic-only path in
 * [com.threemail.android.data.remote.imap.ImapClient.fetchFolders]; users
 * opt in by picking a folder for any role on the settings screen.
 *
 * The column is additive with no FK / index / trigger side-effects, so a
 * partially-applied migration is safe to resume.
 */
val MIGRATION_17_18: Migration = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE accounts ADD COLUMN folderRolesJson TEXT NOT NULL DEFAULT '{}'"
        )
    }
}

/**
 * Adds the per-account Autocrypt / WKD peer-key cache column backing the
 * opportunistic-encryption flow. Defaults to `"{}"` so existing accounts
 * start with an empty cache and fall back to WKD / encrypt-to-self until
 * a sender's Autocrypt header lands. The column is additive with no FK
 * / index / trigger so partial application is safe to resume.
 */
val MIGRATION_18_19: Migration = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE accounts ADD COLUMN autocryptKeysJson TEXT NOT NULL DEFAULT '{}'"
        )
    }
}

/**
 * Adds the `message_flags` side-table backing the
 * "Sent encrypted" badge. Per-message flags (currently just
 * `isEncrypted`) live in a side-table rather than as a column on
 * [com.threemail.android.data.local.entity.MessageEntity] so the
 * `OnConflictStrategy.REPLACE` from server sync doesn't wipe them.
 *
 * The schema is shaped for future extension - new flag columns can be
 * added without a 21 -> 22 migration when we're ready for them.
 * Mirrors [com.threemail.android.data.local.entity.FolderFavoriteEntity]'s
 * "side-table of local-only state keyed by server-stable identifier"
 * pattern.
 *
 * Composite primary key `(accountId, messageId)` matches the unique
 * identifier on [com.threemail.android.data.local.entity.MessageEntity]
 * so future JOINs are a natural match. Cascading FK on account mirrors
 * the rest of the schema: account deletion clears the rows.
 */
val MIGRATION_19_20: Migration = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `message_flags` (" +
                "`accountId` INTEGER NOT NULL, " +
                "`messageId` TEXT NOT NULL, " +
                "`isEncrypted` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`accountId`, `messageId`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        // Explicit named index for the FK child column. Room's KSP-generated
        // DDL emits `index_message_flags_accountId` because the
        // MessageFlagEntity annotation declares
        // `indices = [Index(value = ["accountId"])]`; SQLite's composite
        // primary key only creates an *implicit* b-tree on the leading
        // column, which Room's `room_master_table` checksum does NOT accept
        // as a substitute. Without this line users upgrading from v19 -> v20
        // crash at open with "Migration didn't properly handle:
        // message_flags". The `IF NOT EXISTS` keeps it idempotent on fresh
        // v20 installs (Room creates the equivalent index from the
        // auto-generated CREATE TABLE so this becomes a no-op for them).
        // Mirrors MIGRATION_10_11 which carries the same shape for
        // folder_favorites.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_flags_accountId` " +
                "ON `message_flags`(`accountId`)"
        )
    }
}

private fun repairMessageIndices(db: SupportSQLiteDatabase) {
    db.execSQL(
        "DROP INDEX IF EXISTS index_messages_accountId_folderId_messageId"
    )
    db.execSQL(
        "DROP INDEX IF EXISTS index_messages_folderId"
    )
    // In the unlikely case a future schema re-runs this migration
    // after a corrective prior attempt already created the right
    // index in the wrong shape, drop it too before re-creating so
    // the column-ordering invariant is enforced exactly once.    db.execSQL("DROP INDEX IF EXISTS index_messages_folderId_accountId_messageId")
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_messages_folderId_accountId_messageId " +
                "ON messages(folderId, accountId, messageId)"
    )
}

/**
 * Adds the `calendars` table that backs the Manage Calendars surface and
 * the per-calendar visibility filter used by [com.threemail.android.ui.screens.calendar.CalendarViewModel].
 *
 * Columns chosen to mirror Google's `CalendarListEntry` field-for-field
 * (synced via [com.threemail.android.data.remote.calendar.CalendarApiClient.listCalendars]):
 *  - accountId      : FK -> accounts.id (CASCADE on delete)
 *  - calendarId     : the calendar's stable Google-side identifier (often
 *    an email, like "user@example.com", or a URL for iCal-style subscriptions)
 *  - summary        : the user-visible title
 *  - description    : optional human-readable detail
 *  - timezone       : default for events on this calendar; nullable because
 *    Google sometimes returns null for read-only shared calendars
 *  - isPrimary      : marks Google's "primary" calendar entry; only one per
 *    account isPrimary=1 at any given time
 *  - accessRole     : enum-as-string so the data layer doesn't need Google's
 *    Java type on the classpath for unit-test fakes
 *  - backgroundColor: hex string surfaced by Google (e.g. "#9fc6e7") so the
 *    UI can draw a colour swatch
 *  - isSelected     : user-controllable visibility flag. Default 1 keeps
 *    every freshly-discovered calendar visible until the user explicitly
 *    hides one - the inverse policy (off-by-default) would surface an empty
 *    calendar after first sync for users who haven't visited Manage yet.
 *  - lastSyncedAt   : msec-epoch of the last successful sync fallback; nullable
 *    because there's no stored value before the user's first
 *    syncCalendarList call
 *
 * The composite primary key matches the entity declaration in
 * [com.threemail.android.data.local.entity.CalendarEntryEntity]; the explicit
 * `index_calendars_accountId` covers the FK child column so Room's schema
 * checksum on cross-table relations is satisfied.
 */
val MIGRATION_20_21: Migration = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendars` (" +
                "`accountId` INTEGER NOT NULL, " +
                "`calendarId` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`description` TEXT, " +
                "`timezone` TEXT, " +
                "`isPrimary` INTEGER NOT NULL DEFAULT 0, " +
                "`accessRole` TEXT NOT NULL DEFAULT 'reader', " +
                "`backgroundColor` TEXT, " +
                "`isSelected` INTEGER NOT NULL DEFAULT 1, " +
                "`lastSyncedAt` INTEGER, " +
                "PRIMARY KEY(`accountId`, `calendarId`), " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_calendars_accountId` " +
                "ON `calendars`(`accountId`)"
        )
    }
}

/**
 * Adds the per-account extra-push-folders list backing the opt-in "watch more
 * folders with IMAP IDLE" feature. Defaults to `"[]"` so existing accounts keep
 * INBOX-only push. Additive column with no FK / index / trigger, so a partial
 * application is safe to resume.
 */
val MIGRATION_21_22: Migration = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE accounts ADD COLUMN pushFoldersJson TEXT NOT NULL DEFAULT '[]'"
        )
    }
}

/**
 * Standalone calendar sources (ICS / CalDAV subscriptions not backed by a
 * signed-in mail account).
 *
 * Two pieces:
 *  1. Creates the `calendar_sources` table backing
 *     [com.threemail.android.data.local.entity.CalendarSourceEntity].
 *  2. Rebuilds `calendar_events` so `accountId` becomes nullable and a
 *     nullable `sourceId` FK onto `calendar_sources` is added — a cached
 *     event now belongs to either a Google account or a standalone source.
 *     SQLite can't relax NOT NULL or add an FK via ALTER, so this is the
 *     standard create-copy-drop-rename dance; the four indices are
 *     recreated with Room's canonical `index_<table>_<cols>` names so the
 *     post-migration schema check passes.
 */
val MIGRATION_22_23: Migration = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_sources` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`color` TEXT, " +
                "`username` TEXT, " +
                "`password` TEXT, " +
                "`isVisible` INTEGER NOT NULL, " +
                "`syncEnabled` INTEGER NOT NULL, " +
                "`lastSyncedAt` INTEGER, " +
                "`lastError` TEXT)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_events_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`accountId` INTEGER, " +
                "`sourceId` INTEGER, " +
                "`calendarId` TEXT NOT NULL, " +
                "`eventId` TEXT, " +
                "`iCalUID` TEXT, " +
                "`title` TEXT NOT NULL, " +
                "`description` TEXT, " +
                "`location` TEXT, " +
                "`startEpochMs` INTEGER NOT NULL, " +
                "`endEpochMs` INTEGER NOT NULL, " +
                "`allDay` INTEGER NOT NULL, " +
                "`timezone` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`organizer` TEXT, " +
                "`attendeesJson` TEXT NOT NULL, " +
                "`htmlLink` TEXT, " +
                "`syncedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`sourceId`) REFERENCES `calendar_sources`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "INSERT INTO `calendar_events_new` (" +
                "`id`, `accountId`, `sourceId`, `calendarId`, `eventId`, `iCalUID`, " +
                "`title`, `description`, `location`, `startEpochMs`, `endEpochMs`, " +
                "`allDay`, `timezone`, `status`, `organizer`, `attendeesJson`, " +
                "`htmlLink`, `syncedAt`) " +
                "SELECT `id`, `accountId`, NULL, `calendarId`, `eventId`, `iCalUID`, " +
                "`title`, `description`, `location`, `startEpochMs`, `endEpochMs`, " +
                "`allDay`, `timezone`, `status`, `organizer`, `attendeesJson`, " +
                "`htmlLink`, `syncedAt` FROM `calendar_events`"
        )
        db.execSQL("DROP TABLE `calendar_events`")
        db.execSQL("ALTER TABLE `calendar_events_new` RENAME TO `calendar_events`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_calendar_events_accountId_calendarId_eventId` " +
                "ON `calendar_events` (`accountId`, `calendarId`, `eventId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_calendar_events_accountId_startEpochMs` " +
                "ON `calendar_events` (`accountId`, `startEpochMs`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_calendar_events_accountId_endEpochMs` " +
                "ON `calendar_events` (`accountId`, `endEpochMs`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_calendar_events_sourceId` " +
                "ON `calendar_events` (`sourceId`)"
        )
    }
}

/**
 * CalDAV write-back: cached events gain an `etag` (the server's concurrency
 * token for the backing calendar object) so edits can use `If-Match` and
 * detect concurrent modification instead of silently overwriting.
 */
val MIGRATION_23_24: Migration = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE calendar_events ADD COLUMN etag TEXT")
    }
}

/**
 * Idempotently creates the FTS4 virtual table, the keep-in-sync triggers and an
 * initial backfill.  All statements use IF NOT EXISTS so a partial state can be
 * resumed without crashing; the backfill is a no-op on empty `messages`.
 *
 * The `tokenize=unicode61` clause matches the `@Fts4` declaration on
 * [com.threemail.android.data.local.entity.MessageSearchEntity]. If you ever
 * change the tokenizer there, update this SQL the same way - Room's generated
 * schema will pick up the change but the migration is fixed once shipped.
 */
object FtsTriggers {
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING FTS4(
                subject,
                bodyPlain,
                bodyPreview,
                fromJson,
                toJson,
                ccJson,
                tokenize=unicode61
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS messages_fts_insert
            AFTER INSERT ON messages
            BEGIN
                INSERT INTO messages_fts(rowid, subject, bodyPlain, bodyPreview, fromJson, toJson, ccJson)
                VALUES (
                    new.id,
                    new.subject,
                    COALESCE(new.bodyPlain, ''),
                    COALESCE(new.bodyPreview, ''),
                    COALESCE(new.fromJson, ''),
                    COALESCE(new.toJson, ''),
                    COALESCE(new.ccJson, '')
                );
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS messages_fts_update
            AFTER UPDATE ON messages
            BEGIN
                DELETE FROM messages_fts WHERE rowid = old.id;
                INSERT INTO messages_fts(rowid, subject, bodyPlain, bodyPreview, fromJson, toJson, ccJson)
                VALUES (
                    new.id,
                    new.subject,
                    COALESCE(new.bodyPlain, ''),
                    COALESCE(new.bodyPreview, ''),
                    COALESCE(new.fromJson, ''),
                    COALESCE(new.toJson, ''),
                    COALESCE(new.ccJson, '')
                );
            END
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS messages_fts_delete
            AFTER DELETE ON messages
            BEGIN
                DELETE FROM messages_fts WHERE rowid = old.id;
            END
            """.trimIndent()
        )

        // Idempotent backfill.  INSERT … SELECT over an empty `messages` is a
        // no-op, so this also doubles as the fresh-install "warm up".
        db.execSQL(
            """
            INSERT INTO messages_fts(rowid, subject, bodyPlain, bodyPreview, fromJson, toJson, ccJson)
            SELECT id,
                   subject,
                   COALESCE(bodyPlain, ''),
                   COALESCE(bodyPreview, ''),
                   COALESCE(fromJson, ''),
                   COALESCE(toJson, ''),
                   COALESCE(ccJson, '')
            FROM messages
            WHERE id NOT IN (SELECT rowid FROM messages_fts)
            """.trimIndent()
        )
    }
}
