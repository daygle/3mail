package com.threemail.android.data.local.migrations

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
