package com.threemail.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A message queued for sending. Compose writes the outgoing mail here first and
 * a WorkManager job drains the queue, so a send survives network loss and
 * process death instead of being lost when the immediate SMTP/Gmail call fails.
 *
 * Recipients and attachments are stored as JSON (see `OutboxRepository`), matching
 * how `MessageEntity` persists address/attachment lists.
 */
@Entity(tableName = "outbox_messages")
data class OutboxMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Long,
    val toJson: String,
    val ccJson: String,
    val bccJson: String,
    val subject: String,
    val textBody: String,
    val htmlBody: String? = null,
    val attachmentsJson: String,
    val inReplyTo: String? = null,
    // `references` is a reserved SQL keyword; use an explicit column name so the
    // hand-written migration matches Room's generated schema exactly.
    @ColumnInfo(name = "referencesHeader")
    val references: String? = null,
    /** Send-as identity override: display name / address for the From header. Null => account default. */
    val fromName: String? = null,
    val fromAddress: String? = null,
    /** When true, add a Disposition-Notification-To (read-receipt request) header. */
    val requestReadReceipt: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null
)
