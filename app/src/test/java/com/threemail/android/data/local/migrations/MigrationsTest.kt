package com.threemail.android.data.local.migrations

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises the schema [androidx.room.migration.Migration]s directly against a
 * real (Robolectric-provided) SQLite database.
 *
 * Room's own MigrationTestHelper needs exported schema JSON to validate the
 * post-migration schema, but this project keeps `exportSchema = false` (see
 * ThreeMailDatabase), so we can't diff against a generated bundle. Instead we
 * run each migration's SQL against a minimal starting table and assert the
 * observable outcome - which is what actually breaks when a migration is wrong.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationsTest {

    /**
     * Opens an in-memory database whose `accounts` table matches the columns the
     * 7->8 migration touches. The migration only ALTERs this table, so a full v7
     * schema isn't needed here.
     */
    private fun openV7Database(): SupportSQLiteDatabase {
        val context = RuntimeEnvironment.getApplication() as Context
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE accounts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "email TEXT NOT NULL, " +
                        "incomingServer TEXT, " +
                        "incomingPort INTEGER NOT NULL DEFAULT 993)"
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // No-op: tests drive the migration explicitly.
            }
        }
        return openWithCallback(context, 7) {
            "CREATE TABLE accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "email TEXT NOT NULL, " +
                "incomingServer TEXT, " +
                "incomingPort INTEGER NOT NULL DEFAULT 993)"
        }
    }

    /**
     * Opens an in-memory database whose `accounts` table matches the v9 schema,
     * i.e. everything up to but not including the 9->10 useStartTls column
     * add. Driving migrations against the real v9 schema catches mistakes
     * where the new ALTER would conflict with an unrelated column change.
     */
    private fun openV9Database(): SupportSQLiteDatabase {
        val context = RuntimeEnvironment.getApplication() as Context
        return openWithCallback(context, 9) {
            "CREATE TABLE accounts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "email TEXT NOT NULL, " +
                "displayName TEXT NOT NULL, " +
                "accountType TEXT NOT NULL, " +
                "incomingServer TEXT, " +
                "incomingPort INTEGER NOT NULL, " +
                "useEncryption INTEGER NOT NULL, " +
                "password TEXT, " +
                "isActive INTEGER NOT NULL, " +
                "syncEnabled INTEGER NOT NULL, " +
                "calendarSyncEnabled INTEGER NOT NULL, " +
                "pushEnabled INTEGER NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "outgoingServer TEXT, " +
                "outgoingPort INTEGER NOT NULL DEFAULT 587)"
        }
    }

    private fun openWithCallback(
        context: Context,
        version: Int,
        createSql: () -> String
    ): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(createSql())
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // No-op: tests drive the migration explicitly.
            }
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // null name => in-memory database
            .callback(callback)
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    @Test
    fun `migration 7 to 8 adds outgoing server and port columns`() {
        val db = openV7Database()
        try {
            MIGRATION_7_8.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(accounts)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIdx))
                }
            }

            assertTrue("outgoingServer column should be added", columns.contains("outgoingServer"))
            assertTrue("outgoingPort column should be added", columns.contains("outgoingPort"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 7 to 8 defaults existing rows to null server and port 587`() {
        val db = openV7Database()
        try {
            db.execSQL(
                "INSERT INTO accounts (email, incomingServer, incomingPort) " +
                    "VALUES ('user@example.com', 'imap.example.com', 993)"
            )

            MIGRATION_7_8.migrate(db)

            db.query(
                "SELECT outgoingServer, outgoingPort FROM accounts WHERE email = 'user@example.com'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("existing rows keep a null outgoing server", cursor.isNull(0))
                assertEquals("existing rows default to submission port 587", 587, cursor.getInt(1))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 8 to 9 creates the outbox table with expected columns`() {
        val db = openV7Database()
        try {
            MIGRATION_8_9.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(outbox_messages)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIdx))
                }
            }

            assertTrue("outbox table should exist with an accountId column", columns.contains("accountId"))
            assertTrue("outbox table should carry the subject", columns.contains("subject"))
            // `references` is reserved, so the column is named referencesHeader to
            // match Room's generated schema for OutboxMessageEntity.
            assertTrue("references stored as referencesHeader", columns.contains("referencesHeader"))
            assertTrue("attempt bookkeeping column present", columns.contains("attemptCount"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 8 to 9 outbox accepts an insert`() {
        val db = openV7Database()
        try {
            MIGRATION_8_9.migrate(db)

            db.execSQL(
                "INSERT INTO outbox_messages " +
                    "(accountId, toJson, ccJson, bccJson, subject, textBody, attachmentsJson, createdAt) " +
                    "VALUES (1, '[]', '[]', '[]', 'hi', 'body', '[]', 123)"
            )

            db.query("SELECT COUNT(*) FROM outbox_messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 9 to 10 adds useStartTls column with default 0`() {
        val db = openV9Database()
        try {
            db.execSQL(
                "INSERT INTO accounts (" +
                    "email, displayName, accountType, incomingServer, incomingPort, useEncryption, " +
                    "isActive, syncEnabled, calendarSyncEnabled, pushEnabled, createdAt" +
                    ") VALUES (" +
                    "'user@example.com', 'user', 'IMAP', 'imap.example.com', 993, 1, 1, 1, 1, 1, 123)"
            )

            MIGRATION_9_10.migrate(db)

            // New column exists.
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(accounts)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIdx))
                }
            }
            assertTrue("useStartTls column should be added", columns.contains("useStartTls"))

            // Existing rows retain the legacy semantics: useStartTls=0 so the
            // AccountRepository mapper maps them to Security.SSL_TLS (because
            // useEncryption was true at the time) or Security.NONE. STARTTLS
            // must NEVER be silently enabled on upgrade.
            db.query("SELECT useStartTls FROM accounts WHERE email = 'user@example.com'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing rows default to 0 (STARTTLS off)", 0, cursor.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 10 to 11 creates folder_favorites side table`() {
        // Open any in-memory schema without `folder_favorites` - the migration
        // just creates the side table, so starting at v9 keeps the test
        // honest about the minimal preconditions and avoids duplicating
        // openV10Database for one CREATE TABLE.
        val db = openV9Database()
        try {
            MIGRATION_10_11.migrate(db)

            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(nameIdx))
                }
            }
            assertTrue("folder_favorites table should be created", tables.contains("folder_favorites"))

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(folder_favorites)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIdx))
                }
            }
            assertEquals(
                "composite key (accountId, serverId) is the only schema-level constraint",
                setOf("accountId", "serverId"),
                columns
            )
        } finally {
            db.close()
        }
    }

    /**
     * Drives the full v9 -> v11 -> v12 upgrade path so we exercise MIGRATION_11_12
     * the way a real user upgrading from before drag-reorder would see it:
     * existing ROWIDs survive (so insertion-order tie-breaks in the DAO query
     * are meaningful after the backfill), and the migration's DEFAULT 0 is
     * applied uniformly to every pre-existing row.
     */
    @Test
    fun `migration 11 to 12 adds position column with default 0`() {
        val db = openV9Database()
        try {
            // Bring forward to v11: create folder_favorites + an account so
            // we can insert favorites with a valid FK.
            MIGRATION_10_11.migrate(db)
            db.execSQL(
                "INSERT INTO accounts (" +
                    "email, displayName, accountType, incomingServer, incomingPort, useEncryption, " +
                    "isActive, syncEnabled, calendarSyncEnabled, pushEnabled, createdAt" +
                    ") VALUES (" +
                    "'user@example.com', 'user', 'IMAP', 'imap.example.com', 993, 1, 1, 1, 1, 1, 123)"
            )
            db.execSQL("INSERT INTO folder_favorites (accountId, serverId) VALUES (1, 'A')")
            db.execSQL("INSERT INTO folder_favorites (accountId, serverId) VALUES (1, 'B')")
            db.execSQL("INSERT INTO folder_favorites (accountId, serverId) VALUES (1, 'C')")

            // Pre-migration assertion: no `position` column yet.
            val preColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(folder_favorites)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    preColumns.add(cursor.getString(nameIdx))
                }
            }
            assertEquals(
                "before migration: only (accountId, serverId) columns exist",
                setOf("accountId", "serverId"),
                preColumns
            )

            MIGRATION_11_12.migrate(db)

            // Post-migration: position column was added.
            val postColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(folder_favorites)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    postColumns.add(cursor.getString(nameIdx))
                }
            }
            assertTrue(
                "position column should be added by MIGRATION_11_12",
                postColumns.contains("position")
            )

            // Every pre-existing row is backfilled to position=0. The DAO
            // query uses `ORDER BY position ASC, rowid ASC` so ties at
            // position=0 fall back to FIFO insertion order.
            val preMigrationBackfilled = mutableListOf<Pair<String, Int>>()
            db.query("SELECT serverId, position FROM folder_favorites ORDER BY rowid ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    preMigrationBackfilled.add(cursor.getString(0) to cursor.getInt(1))
                }
            }
            assertEquals(
                "all pre-existing favorites receive position=0 from DEFAULT",
                listOf("A" to 0, "B" to 0, "C" to 0),
                preMigrationBackfilled
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration 14 to 15 adds per-account personalization columns with behaviour-preserving defaults`() {
        // The v9 accounts schema is a fine starting point: MIGRATION_14_15 only
        // ALTERs `accounts`, so it doesn't depend on any columns added between
        // v9 and v14.
        val db = openV9Database()
        try {
            db.execSQL(
                "INSERT INTO accounts (" +
                    "email, displayName, accountType, incomingServer, incomingPort, useEncryption, " +
                    "isActive, syncEnabled, calendarSyncEnabled, pushEnabled, createdAt" +
                    ") VALUES (" +
                    "'user@example.com', 'user', 'IMAP', 'imap.example.com', 993, 1, 1, 1, 1, 1, 123)"
            )

            MIGRATION_14_15.migrate(db)

            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(accounts)").use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(nameIdx))
                }
            }
            assertTrue("signature column should be added", columns.contains("signature"))
            assertTrue("syncIntervalMinutes column should be added", columns.contains("syncIntervalMinutes"))
            assertTrue("notificationsEnabled column should be added", columns.contains("notificationsEnabled"))

            // Existing rows keep behaviour: no signature, follow-the-default
            // sync cadence (0), and notifications on.
            db.query(
                "SELECT signature, syncIntervalMinutes, notificationsEnabled " +
                    "FROM accounts WHERE email = 'user@example.com'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("existing rows default to empty signature", "", cursor.getString(0))
                assertEquals("existing rows default to 0 (global cadence)", 0, cursor.getInt(1))
                assertEquals("existing rows keep notifications on", 1, cursor.getInt(2))
            }
        } finally {
            db.close()
        }
    }

    /**
     * Open a v12-shaped `messages` table with no indices attached - each
     * test then seeds the specific index layout it cares about (broken
     * state for the production-crash repro, partial-correct state for the
     * idempotency check). Other tables are absent on purpose; this helper
     * only exercises the messages-table index paths of [MIGRATION_12_13].
     */
    private fun openV12MessagesSchema(): SupportSQLiteDatabase {
        val context = RuntimeEnvironment.getApplication() as Context
        return openWithCallback(context, 12) {
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "accountId INTEGER NOT NULL, " +
                "folderId INTEGER NOT NULL, " +
                "messageId TEXT NOT NULL, " +
                "threadId TEXT, " +
                "date INTEGER NOT NULL, " +
                "isRead INTEGER NOT NULL DEFAULT 0)"
        }
    }

    /**
     * Reads `PRAGMA index_list('messages')` and asserts that
     * `indexName` is in the result with `unique = 1`. SQLite's
     * index_list returns the `unique` column as a 1/0 integer; a
     * regression where the migration accidentally wrote
     * `CREATE INDEX` instead of `CREATE UNIQUE INDEX` would re-trigger
     * the production `Migration didn't properly handle` crash, so this
     * assertion catches that regression even when the index *name* is
     * identical and well-formed.
     */
    private fun indexIsUnique(db: SupportSQLiteDatabase, indexName: String): Boolean {
        db.query("PRAGMA index_list('messages')").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val uniqueIdx = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == indexName) {
                    return cursor.getInt(uniqueIdx) == 1
                }
            }
        }
        return false
    }

    private fun messageIndexNames(db: SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages'"
        ).use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names.add(cursor.getString(nameIdx))
            }
        }
        return names
    }

    private fun indexColumns(db: SupportSQLiteDatabase, indexName: String): List<String> {
        val cols = mutableListOf<String>()
        db.query("PRAGMA index_info('$indexName')").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                cols.add(cursor.getString(nameIdx))
            }
        }
        return cols
    }

    /**
     * Drives the v12 -> v13 repair on a database whose `messages` table
     * carries the broken 5-index layout that real users on the old
     * 8f9ac6d-era code shipped with. The migration must:
     *  - drop the orphan standalone `index_messages_folderId`
     *  - drop the v6-era auto-generated unique
     *    `index_messages_accountId_folderId_messageId`
     *  - create the new unique composite
     *    `index_messages_folderId_accountId_messageId` with columns in
     *    that exact order so Room's expected fingerprint matches.
     */
    @Test
    fun `migration 12 to 13 cleans up broken messages indices from 8f9ac6d-era installs`() {
        val db = openV12MessagesSchema()
        try {
            // Seed the broken state:
            //   * the v6-era unique composite on (accountId, folderId, messageId) -
            //     Room auto-generated this from the previous entity's
            //     `Index(value = ["accountId","folderId","messageId"], unique=true)`
            //   * the v7-era standalone `Index(value = ["folderId"])` that the
            //     pre-fix MIGRATION_6_7 added without dropping the old.
            //   * the three auto-generated non-unique indices (accountId+threadId,
            //     date, isRead) that Room emits unchanged.
            db.execSQL(
                "CREATE UNIQUE INDEX index_messages_accountId_folderId_messageId " +
                    "ON messages(accountId, folderId, messageId)"
            )
            db.execSQL(
                "CREATE INDEX index_messages_accountId_threadId " +
                    "ON messages(accountId, threadId)"
            )
            db.execSQL("CREATE INDEX index_messages_date ON messages(date)")
            db.execSQL("CREATE INDEX index_messages_folderId ON messages(folderId)")
            db.execSQL("CREATE INDEX index_messages_isRead ON messages(isRead)")

            // Sanity: the test environment is the broken layout the user
            // hits in production. Without MIGRATION_12_13 the post-
            // migration schema check would fail with five indices
            // including the orphan folderId and the wrong-order unique.
            assertEquals(
                "pre-migration setup must be the broken-v12 layout",
                setOf(
                    "index_messages_accountId_folderId_messageId",
                    "index_messages_accountId_threadId",
                    "index_messages_date",
                    "index_messages_folderId",
                    "index_messages_isRead"
                ),
                messageIndexNames(db)
            )

            MIGRATION_12_13.migrate(db)

            // Post-migration: the orphan and the old column-order unique
            // are gone, and the new ordered unique is in place.
            assertEquals(
                "post-migration indices match the current entity's Room-generated fingerprint",
                setOf(
                    "index_messages_folderId_accountId_messageId",
                    "index_messages_accountId_threadId",
                    "index_messages_date",
                    "index_messages_isRead"
                ),
                messageIndexNames(db)
            )

            // The composite UNIQUE index has columns in folderId-leading
            // order - any other order would still satisfy uniqueness
            // (column order is irrelevant to uniqueness constraints) but
            // would NOT match Room's expected fingerprint and would
            // silently re-throw the IllegalStateException. The actual
            // uniqueness behaviour is verified by the duplicate-insert
            // rejection below; here we only assert the structural
            // column-ordering invariant that Room's
            // `room_master_table` checksum actually checks.
            assertEquals(
                "the new unique composite's column ordering must match Room's expected DDL",
                listOf("folderId", "accountId", "messageId"),
                indexColumns(db, "index_messages_folderId_accountId_messageId")
            )

            // Unique still works: two rows that differ in some column
            // other than the composite key insert cleanly, two rows
            // that conflict on (folderId, accountId, messageId) don't.
            db.execSQL(
                "INSERT INTO messages (accountId, folderId, messageId, date, isRead) " +
                    "VALUES (1, 10, 'm1', 1000, 0)"
            )
            db.execSQL(
                "INSERT INTO messages (accountId, folderId, messageId, date, isRead) " +
                    "VALUES (1, 11, 'm1', 2000, 0)"
            )
            db.query("SELECT COUNT(*) FROM messages").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            // Tighten the rejection assertion: catch only `RuntimeException`
            // (which is what Robolectric's SQLite throws) and require the
            // message to mention the UNIQUE / constraint signal so a
            // completely-unrelated throw (FK violation, table missing,
            // etc.) cannot silently satisfy the test. Using a bare
            // `try { fail() } catch (Exception) {}` would let a
            // regression where the index gets dropped leave the test
            // green because the failing insert would still throw.
            var rejected = false
            var rejectionReason = ""
            try {
                db.execSQL(
                    "INSERT INTO messages (accountId, folderId, messageId, date, isRead) " +
                        "VALUES (1, 10, 'm1', 3000, 0)"
                )
            } catch (e: RuntimeException) {
                rejected = true
                rejectionReason = (e.cause?.message ?: e.message ?: "<no message>")
            }
            assertTrue(
                "unique composite must reject a duplicate " +
                    "(folderId=10, accountId=1, messageId='m1'); " +
                    "rejected=$rejected reason=\"$rejectionReason\"",
                rejected && (
                    rejectionReason.contains("UNIQUE", ignoreCase = true) ||
                        rejectionReason.contains("constraint failed", ignoreCase = true) ||
                        rejectionReason.contains("unique", ignoreCase = true)
                    )
            )
        } finally {
            db.close()
        }
    }

    /**
     * Idempotency + CREATE-stem check: running [MIGRATION_12_13] on a
     * database that has every v13 index EXCEPT the new unique composite
     * exercises the `CREATE UNIQUE INDEX IF NOT EXISTS` branch and
     * tolerates the empty `DROP INDEX IF EXISTS` steps without parade.
     * Also asserts the *column order* of the resulting unique
     * composite: a regression that creates it as
     * `(accountId, folderId, messageId)` would still pass the
     * name-only check, but Room's `room_master_table` checksum
     * specifically compares the column sequence, so the wrong order
     * re-triggers the production crash.
     */    @Test
    fun `migration 12 to 13 creates the new unique composite even when the rest of v13 is already in place`() {
        val db = openV12MessagesSchema()
        try {
            // Seed three of the four v13 indices, leaving out
            // `index_messages_folderId_accountId_messageId` so the
            // CREATE branch has to fire.
            db.execSQL(
                "CREATE INDEX index_messages_accountId_threadId " +
                    "ON messages(accountId, threadId)"
            )
            db.execSQL("CREATE INDEX index_messages_date ON messages(date)")
            db.execSQL("CREATE INDEX index_messages_isRead ON messages(isRead)")

            MIGRATION_12_13.migrate(db)

            assertEquals(
                "idempotent migration produces the correct v13 layout",
                setOf(
                    "index_messages_folderId_accountId_messageId",
                    "index_messages_accountId_threadId",
                    "index_messages_date",
                    "index_messages_isRead"
                ),
                messageIndexNames(db)
            )
            assertEquals(
                "the new unique composite's column ordering must match Room's expected DDL " +
                    "(folderId leading satisfies the FK-index requirement AND the checksum)",
                listOf("folderId", "accountId", "messageId"),
                indexColumns(db, "index_messages_folderId_accountId_messageId")
            )
            assertTrue(
                "the new composite must be UNIQUE - " +
                    "a regression to plain CREATE INDEX would re-trigger the production crash",
                indexIsUnique(db, "index_messages_folderId_accountId_messageId")
            )
        } finally {
            db.close()
        }
    }
}
