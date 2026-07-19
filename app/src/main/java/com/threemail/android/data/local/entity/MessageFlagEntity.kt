package com.threemail.android.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

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
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageFlagEntity(
    @PrimaryKey
    val accountId: Long,
    @PrimaryKey
    val messageId: String,
    /** True when this message was sent (or, on ingest, received) as PGP/MIME. */
    val isEncrypted: Boolean = false
)
