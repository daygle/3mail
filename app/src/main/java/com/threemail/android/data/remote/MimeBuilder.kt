package com.threemail.android.data.remote

import com.threemail.android.domain.model.Attachment
import java.io.File
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Builds an RFC 5322 [MimeMessage] from an [OutgoingMessage]. Shared by the IMAP
 * (SMTP) and Gmail (raw send) transports so message construction - including
 * multipart/alternative HTML bodies, multipart/related inline images, and
 * file attachments - lives in one place.
 */
object MimeBuilder {

    private val session: Session by lazy { Session.getInstance(Properties()) }

    fun build(
        fromEmail: String,
        fromName: String?,
        message: OutgoingMessage
    ): MimeMessage = MimeMessage(session).apply {
        setFrom(InternetAddress(fromEmail, fromName))
        setRecipients(Message.RecipientType.TO, message.to.map { InternetAddress(it.address, it.name) }.toTypedArray())
        setRecipients(Message.RecipientType.CC, message.cc.map { InternetAddress(it.address, it.name) }.toTypedArray())
        setRecipients(Message.RecipientType.BCC, message.bcc.map { InternetAddress(it.address, it.name) }.toTypedArray())
        setSubject(message.subject, "utf-8")
        message.inReplyTo?.let { setHeader("In-Reply-To", angle(it)) }
        message.references?.let { setHeader("References", it) }
        sentDate = Date()

        val inlineAttachments = message.attachments.filter { it.isInline }
        val fileAttachments = message.attachments.filter { !it.isInline }

        when {
            // Inline images: top-level is multipart/related (root = multipart/alternative
            // when an HTML body exists, otherwise text). When file attachments are also
            // present, wrap the multipart/related as the first part of multipart/mixed
            // and append the file parts.
            inlineAttachments.isNotEmpty() -> {
                val related = MimeMultipart("related")
                related.addBodyPart(rootBodyPart(message))
                inlineAttachments.forEach { att ->
                    inlineImagePart(att)?.let { related.addBodyPart(it) }
                }
                if (fileAttachments.isNotEmpty()) {
                    val mixed = MimeMultipart("mixed")
                    mixed.addBodyPart(MimeBodyPart().apply { setContent(related) })
                    fileAttachments.forEach { att ->
                        fileAttachmentPart(att)?.let { mixed.addBodyPart(it) }
                    }
                    setContent(mixed)
                } else {
                    setContent(related)
                }
            }
            message.htmlBody.isNullOrBlank() && fileAttachments.isEmpty() ->
                setText(message.textBody, "utf-8")
            fileAttachments.isEmpty() ->
                setContent(alternativePart(message))
            else -> {
                val mixed = MimeMultipart("mixed")
                mixed.addBodyPart(bodyPart(message))
                fileAttachments.forEach { att ->
                    fileAttachmentPart(att)?.let { mixed.addBodyPart(it) }
                }
                setContent(mixed)
            }
        }
    }

    /** Root part of a multipart/related - multipart/alternative when HTML is available, otherwise plain text. */
    private fun rootBodyPart(message: OutgoingMessage): MimeBodyPart =
        if (message.htmlBody.isNullOrBlank()) {
            MimeBodyPart().apply { setText(message.textBody, "utf-8") }
        } else {
            MimeBodyPart().apply { setContent(alternativePart(message)) }
        }

    /** A multipart/alternative with plain + HTML alternatives. */
    private fun alternativePart(message: OutgoingMessage): MimeMultipart {
        val alternative = MimeMultipart("alternative")
        alternative.addBodyPart(MimeBodyPart().apply { setText(message.textBody, "utf-8") })
        alternative.addBodyPart(MimeBodyPart().apply { setContent(message.htmlBody, "text/html; charset=utf-8") })
        return alternative
    }

    /** The body wrapped as a single part, for embedding inside multipart/mixed. */
    private fun bodyPart(message: OutgoingMessage): MimeBodyPart =
        if (message.htmlBody.isNullOrBlank()) {
            MimeBodyPart().apply { setText(message.textBody, "utf-8") }
        } else {
            MimeBodyPart().apply { setContent(alternativePart(message)) }
        }

    /** MIME part for a regular file attachment; returns null when the local path cannot be read. */
    private fun fileAttachmentPart(attachment: Attachment): MimeBodyPart? {
        val path = attachment.localPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return MimeBodyPart().apply {
            dataHandler = DataHandler(FileDataSource(file))
            fileName = attachment.fileName
        }
    }

    /**
     * MIME part for an inline image. Sets `Content-ID` so that a `cid:...` reference
     * in the HTML body resolves to this part, plus `Content-Disposition: inline`
     * so clients render it inline rather than as an unrelated attachment.
     */
    private fun inlineImagePart(attachment: Attachment): MimeBodyPart? {
        val path = attachment.localPath ?: return null
        val cid = attachment.contentId ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return MimeBodyPart().apply {
            dataHandler = DataHandler(FileDataSource(file))
            fileName = attachment.fileName
            disposition = Part.INLINE
            setContentID("$cid@3mail")
        }
    }

    private fun angle(id: String): String = "<${id.trim().trim('<', '>')}>"
}
