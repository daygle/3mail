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
