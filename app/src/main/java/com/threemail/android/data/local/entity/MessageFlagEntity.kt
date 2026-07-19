package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-message side-table holding local-only flags that need to survive
 * `OnConflictStrategy.REPLACE` inserts from server sync.
 *
 * Why this lives in a side-table rather than on [MessageEntity]:
 * the sync path uses [androidx.room.OnConflictStrategy.REPLACE] when
 * upserting messages, which discards any locally-set column flag on
 * the same row. Per-message UI affordances like "this message was
 * sent encrypted" therefore live here, keyed by `(accountId, messageId)`
 * (the RFC 5322 Message-ID is the natural cross-server handle).
 *
 * Currently we only track one flag (`isEncrypted`) but the schema is
 * shaped to extend to e.g. `wantsEncryption` / `deliveryFailureNotice`
 * without further migrations - just add a new column. Mirrors the
 * [FolderFavoriteEntity] pattern of "side-table of local-only state
 * keyed by server-stable identifier".
 *
 * Cascading delete on the account FK matches the parent table's
 * policy so removing an account also clears its flag rows.
 */
@Entity(
    tableName = "message_flags",
    primaryKeys = ["accountId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Mirrors FolderFavoriteEntity: Room requires the FK child column to be
    // covered by an explicit Index annotation, even when SQLite's COMPOSITE
    // primary key already creates an implicit b-tree on the leading column.
    // Failing to declare this surfaces as a KSP warning at compile time and
    // (in some Room versions) a schema-checksum mismatch when the auto-generated
    // DDL is compared against the migration SQL.
    indices = [Index(value = ["accountId"])]
)
data class MessageFlagEntity(
    val accountId: Long,
    val messageId: String,
    /** True when this message was sent (or, on ingest, received) as PGP/MIME. */
    val isEncrypted: Boolean = false
)
