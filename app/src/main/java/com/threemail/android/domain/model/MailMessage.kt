package com.threemail.android.domain.model

data class MailMessage(
    val id: Long = 0,
    val accountId: Long,
    val folderId: Long,
    val messageId: String,
    val threadId: String? = null,
    val subject: String,
    val from: List<EmailAddress>,
    val to: List<EmailAddress>,
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val date: Long,
    val bodyPreview: String = "",
    val bodyHtml: String? = null,
    val bodyPlain: String? = null,
    val isRead: Boolean = false,
    /**
     * Populated by remote ingest from the server's \Flagged flag. The
     * user-visible star feature has been removed from the UI, but this
     * field is intentionally retained so incoming starred mail keeps
     * round-tripping through Room. Do not delete.
     */
    val isStarred: Boolean = false,
    val isDraft: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val uid: Long = 0,
    /** Provider-native handle used for remote operations: IMAP UID (as string) or Gmail message id. */
    val remoteId: String = "",
    val syncedAt: Long = 0,
    /**
     * True when this message was sent (or, after a future receiver-side
     * enhancement, ingested) as PGP/MIME encrypted content. Sourced from
     * the `message_flags` side-table - kept off [MessageEntity] so the
     * `OnConflictStrategy.REPLACE` from server sync doesn't wipe it.
     * Default false so older call sites that don't yet read the flag
     * surface look like they always have plaintext mail.
     */
    val isEncrypted: Boolean = false
)

data class EmailAddress(
    val name: String = "",
    val address: String
) {
    override fun toString(): String {
        return if (name.isBlank()) address else "$name <$address>"
    }
}

data class Attachment(
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val localPath: String? = null,
    val remoteId: String? = null,
    /** True when this attachment is referenced from the HTML body via a Content-ID (cid:) URI. */
    val isInline: Boolean = false,
    /** The local part of a `cid:...@3mail` reference; required when [isInline] is true. */
    val contentId: String? = null
)
