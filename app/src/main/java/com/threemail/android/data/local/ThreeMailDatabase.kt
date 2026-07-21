package com.threemail.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.dao.CalendarEventDao
import com.threemail.android.data.local.dao.CalendarListDao
import com.threemail.android.data.local.dao.FolderDao
import com.threemail.android.data.local.dao.MessageDao
import com.threemail.android.data.local.dao.MessageFlagDao
import com.threemail.android.data.local.dao.OutboxDao
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.CalendarEntryEntity
import com.threemail.android.data.local.entity.CalendarEventEntity
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.FolderFavoriteEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.data.local.entity.MessageFlagEntity
import com.threemail.android.data.local.entity.MessageSearchEntity
import com.threemail.android.data.local.entity.OutboxMessageEntity
import com.threemail.android.data.local.migrations.FtsTriggers
import com.threemail.android.data.local.migrations.MIGRATION_4_5
import com.threemail.android.data.local.migrations.MIGRATION_5_6
import com.threemail.android.data.local.migrations.MIGRATION_6_7
import com.threemail.android.data.local.migrations.MIGRATION_7_8
import com.threemail.android.data.local.migrations.MIGRATION_8_9
import com.threemail.android.data.local.migrations.MIGRATION_9_10
import com.threemail.android.data.local.migrations.MIGRATION_10_11
import com.threemail.android.data.local.migrations.MIGRATION_11_12
import com.threemail.android.data.local.migrations.MIGRATION_12_13
import com.threemail.android.data.local.migrations.MIGRATION_13_14
import com.threemail.android.data.local.migrations.MIGRATION_14_15
import com.threemail.android.data.local.migrations.MIGRATION_15_16
import com.threemail.android.data.local.migrations.MIGRATION_16_17
import com.threemail.android.data.local.migrations.MIGRATION_17_18
import com.threemail.android.data.local.migrations.MIGRATION_18_19
import com.threemail.android.data.local.migrations.MIGRATION_19_20
import com.threemail.android.data.local.migrations.MIGRATION_20_21
import com.threemail.android.data.local.migrations.MIGRATION_21_22

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        FolderFavoriteEntity::class,
        MessageEntity::class,
        CalendarEventEntity::class,
        CalendarEntryEntity::class,
        MessageSearchEntity::class,
        OutboxMessageEntity::class,
        MessageFlagEntity::class
    ],
    version = 22,
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
    abstract fun calendarListDao(): CalendarListDao
    abstract fun outboxDao(): OutboxDao
    abstract fun messageFlagDao(): MessageFlagDao

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
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
                    .addCallback(freshInstallCallback)
                    // No destructive fallback is configured. The v11 -> v12 ->
                    // v13 path is fully covered by MIGRATION_11_12 and
                    // MIGRATION_12_13 (the latter being the idempotent repair
                    // for the broken-index v12 state shipped by the pre-fix
                    // 8f9ac6d path), so every supported DB reaches v13 by
                    // migration. A previous `fallbackToDestructiveMigrationFrom(11, 12)`
                    // was removed: Room rejects declaring a destructive
                    // fallback FROM a version that also has a registered
                    // migration STARTING at it (11 -> MIGRATION_11_12, 12 ->
                    // MIGRATION_12_13), so build() threw IllegalArgumentException
                    // and crashed the app on first DB access. It also could
                    // never have served its intended purpose: destructive
                    // fallback only fires when no migration path exists, not
                    // when a registered migration throws at runtime.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
