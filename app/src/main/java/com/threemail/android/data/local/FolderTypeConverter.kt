package com.threemail.android.data.local

import androidx.room.TypeConverter
import com.threemail.android.domain.model.FolderType

/**
 * Pins [FolderType] storage to the enum's `name`. Room's default already does
 * this, but having an explicit converter registered on
 * [ThreeMailDatabase] makes the storage contract auditable and prevents a
 * silent regression if a future change introduces a different
 * `@TypeConverters` set (e.g. ordinal storage).
 *
 * Without this, queries that compare against string literals like
 * `WHERE type = 'INBOX'` rely on Room's default behavior, which is implicit.
 */
class FolderTypeConverter {
    @TypeConverter
    fun toFolderType(value: String): FolderType = when (value) {
        "INBOX" -> FolderType.Inbox
        else -> FolderType.valueOf(value)
    }

    @TypeConverter
    fun fromFolderType(value: FolderType): String = value.name
}
