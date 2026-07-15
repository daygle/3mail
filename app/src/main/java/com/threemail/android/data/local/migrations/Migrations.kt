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
 * disabling mail sync. Existing rows default to 1 — i.e. optical backwards
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
 * Adds a non-unique index on `messages.folderId` so the FK enforcement scans
 * Room runs when a row in `folders` is updated or deleted can use it. The
 * existing unique composite `(accountId, folderId, messageId)` cannot satisfy
 * this — composite indexes only help lookups whose leading column matches, and
 * the enforced scan filters purely by `folderId`.
 *
 * `CREATE INDEX IF NOT EXISTS` is safe because:
 *  - Fresh installs: the index is already created by Room from the @Index
 *    annotation, so this statement is a no-op.
 *  - Resumed migrations: a partially-applied state won't crash on retry.
 *
 * The index name is Room's default convention (`index_<table>_<column>`) so
 * `MigrationTestHelper` will see the post-migration schema match the
 * generated v7 schema and update `room_master_table` cleanly.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_messages_folderId ON messages(folderId)"
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
 * change the tokenizer there, update this SQL the same way — Room's generated
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
