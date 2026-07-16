package com.threemail.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.dao.CalendarEventDao
import com.threemail.android.data.local.dao.FolderDao
import com.threemail.android.data.local.dao.MessageDao
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.CalendarEventEntity
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.data.local.entity.MessageSearchEntity
import com.threemail.android.data.local.migrations.FtsTriggers
import com.threemail.android.data.local.migrations.MIGRATION_4_5
import com.threemail.android.data.local.migrations.MIGRATION_5_6
import com.threemail.android.data.local.migrations.MIGRATION_6_7

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        MessageEntity::class,
        CalendarEventEntity::class,
        MessageSearchEntity::class
    ],
    version = 7,
    // exportSchema intentionally OFF: Room 2.8.4 ships pre-generated
    // SchemaBundle/FieldBundle/EntityBundle/DatabaseBundle serializer classes
    // whose compiled ABI is incompatible with the serialization-core version
    // that lands on the KSP 2.2.10-2.0.2 daemon classpath. Setting this to
    // true causes an AbstractMethodError at kspDebugKotlin time on every
    // classloader / AGP / KSP combination tried across rounds 3-6 (4e6a3df,
    // 98d09d7, 9d38566, 4eedb87). Migrations still work because they are
    // version-number-keyed and don't depend on the JSON export history. To
    // re-enable in the future, upgrade Room to a release that re-compiles
    // its bundled serializer classes against a serialization ABI matching
    // whatever version the chosen KSP / Kotlin combo ships on the daemon.
    exportSchema = false
)
@TypeConverters(FolderTypeConverter::class)
abstract class ThreeMailDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao
    abstract fun messageDao(): MessageDao
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        @Volatile
        private var INSTANCE: ThreeMailDatabase? = null

        /**
         * Runs once on a fresh install (i.e. when v5 is the user's starting
         * schema). Migrations run their own FTS setup via [MIGRATION_4_5];
         * this callback covers the cold-start case so the FTS table and its
         * sync triggers exist regardless of how the user arrived at v5.
         */
        private val freshInstallCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Idempotent: messages is empty so the backfill is a no-op.
                FtsTriggers.install(db)
            }
        }

        fun getInstance(context: Context): ThreeMailDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ThreeMailDatabase::class.java,
                    "threemail_database"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .addCallback(freshInstallCallback)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
