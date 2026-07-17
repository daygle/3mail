package com.threemail.android.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the production Room builder wired up in [ThreeMailDatabase.getInstance].
 *
 * Room 2.8 validates the migration set inside `build()`: declaring a
 * destructive fallback FROM a schema version that also has a registered
 * migration STARTING at that version throws IllegalArgumentException. A prior
 * `fallbackToDestructiveMigrationFrom(11, 12)` collided with MIGRATION_11_12
 * and MIGRATION_12_13, so `build()` threw and crashed the app the first time
 * Hilt built the database (i.e. on startup, before any UI).
 *
 * [com.threemail.android.data.local.migrations.MigrationsTest] runs each
 * migration's SQL against a hand-rolled SQLite schema but never builds the real
 * ThreeMailDatabase, so it could not catch a bad builder configuration. This
 * test does: it calls the exact production entry point and asserts it builds.
 */
@RunWith(RobolectricTestRunner::class)
class ThreeMailDatabaseTest {

    @After
    fun tearDown() {
        // getInstance() caches a process-wide singleton; null it out (and close
        // any built instance) via reflection so this file-backed database does
        // not leak into other tests that share the Gradle worker's JVM.
        ThreeMailDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? ThreeMailDatabase)?.close()
            set(null, null)
        }
    }

    @Test
    fun `getInstance builds with a valid migration configuration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A conflicting addMigrations()/fallbackToDestructiveMigrationFrom()
        // configuration throws inside build(), so simply reaching a non-null
        // instance proves the builder is valid.
        val db = ThreeMailDatabase.getInstance(context)
        assertNotNull(
            "getInstance() must build; an invalid migration/fallback config throws in build()",
            db
        )
    }
}
