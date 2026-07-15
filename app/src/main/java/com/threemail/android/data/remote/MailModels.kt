package com.threemail.android.data.remote

import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.MailMessage

/** Body + attachments extracted from a single message. */
data class MessageBody(
    val html: String?,
    val plain: String?,
    val attachments: List<Attachment>
)

/** Result of an incremental fetch, carrying a monotonic cursor for the next sync. */
data class RemoteFetch(
    val messages: List<MailMessage>,
    val nextCursor: Long
)

/** A message to send or save as a draft. */
data class OutgoingMessage(
    val to: List<EmailAddress>,
    val cc: List<EmailAddress> = emptyList(),
    val bcc: List<EmailAddress> = emptyList(),
    val subject: String,
    val textBody: String,
    val htmlBody: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: String? = null
)
