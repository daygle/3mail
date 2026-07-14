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
    val isStarred: Boolean = false,
    val isDraft: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val uid: Long = 0,
    val syncedAt: Long = 0
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
    val remoteId: String? = null
)
