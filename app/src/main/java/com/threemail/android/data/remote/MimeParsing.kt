package com.threemail.android.data.remote

import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.util.MailText
import javax.mail.Address
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.InternetAddress

/**
 * Provider-agnostic MIME part walking shared by the POP3 transport (and
 * available to any future transport). Mirrors the private helpers inside
 * [com.threemail.android.data.remote.imap.ImapClient]; kept as a standalone
 * object so IMAP's proven implementation stays untouched while POP3 reuses the
 * same recursive extraction logic.
 */
object MimeParsing {

    /** Recursively walks [part], appending HTML/plain text and collecting attachment metadata. */
    fun extractParts(
        part: Part,
        html: StringBuilder,
        plain: StringBuilder,
        attachments: MutableList<Attachment>
    ) {
        try {
            val disposition = part.disposition
            val fileName = part.fileName
            val isAttachment = Part.ATTACHMENT.equals(disposition, ignoreCase = true) ||
                (!fileName.isNullOrBlank() && !part.isMimeType("multipart/*"))

            when {
                isAttachment -> attachments.add(
                    Attachment(
                        fileName = fileName ?: "attachment",
                        mimeType = part.contentType?.substringBefore(";")?.trim() ?: "application/octet-stream",
                        size = part.size.toLong().coerceAtLeast(0),
                        remoteId = fileName
                    )
                )
                part.isMimeType("text/html") -> html.append(part.content?.toString().orEmpty())
                part.isMimeType("text/plain") -> plain.append(part.content?.toString().orEmpty())
                part.isMimeType("multipart/*") -> {
                    val multipart = part.content as? Multipart ?: return
                    for (i in 0 until multipart.count) {
                        extractParts(multipart.getBodyPart(i), html, plain, attachments)
                    }
                }
                part.isMimeType("message/rfc822") -> {
                    (part.content as? Part)?.let { extractParts(it, html, plain, attachments) }
                }
            }
        } catch (_: Exception) {
            // Skip parts we cannot decode rather than failing the whole message.
        }
    }

    /** Finds the first attachment part whose file name matches [targetName]. */
    fun findAttachmentPart(part: Part, targetName: String): Part? {
        return try {
            if (part.isMimeType("multipart/*")) {
                val multipart = part.content as? Multipart ?: return null
                for (i in 0 until multipart.count) {
                    findAttachmentPart(multipart.getBodyPart(i), targetName)?.let { return it }
                }
                null
            } else if (part.fileName == targetName) {
                part
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Short text preview built from the plain part (or stripped HTML) of [message]. */
    fun extractPreview(message: Message): String = try {
        val html = StringBuilder()
        val plain = StringBuilder()
        val attachments = mutableListOf<Attachment>()
        extractParts(message, html, plain, attachments)
        val text = plain.toString().takeIf { it.isNotBlank() }
            ?: MailText.stripHtml(html.toString())
        text.replace(Regex("\\s+"), " ").trim().take(200)
    } catch (e: Exception) {
        ""
    }
}

/**
 * Top-level (not a member of [MimeParsing]) so callers can import and use it as
 * a normal extension without needing the object as a dispatch receiver.
 */
fun Address.toEmailAddress(): EmailAddress {
    val internet = this as? InternetAddress
    return EmailAddress(
        name = internet?.personal ?: "",
        address = internet?.address ?: toString()
    )
}
