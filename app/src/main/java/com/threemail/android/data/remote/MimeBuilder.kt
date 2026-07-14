package com.threemail.android.data.remote

import java.io.File
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Builds an RFC 5322 [MimeMessage] from an [OutgoingMessage]. Shared by the IMAP
 * (SMTP) and Gmail (raw send) transports so message construction — including
 * multipart/alternative HTML bodies and attachments — lives in one place.
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

        val hasHtml = !message.htmlBody.isNullOrBlank()
        val hasAttachments = message.attachments.isNotEmpty()

        when {
            !hasHtml && !hasAttachments -> setText(message.textBody, "utf-8")
            !hasAttachments -> setContent(alternativePart(message))
            else -> {
                val mixed = MimeMultipart("mixed")
                mixed.addBodyPart(bodyPart(message))
                message.attachments.forEach { attachment ->
                    val path = attachment.localPath ?: return@forEach
                    val file = File(path)
                    if (!file.exists()) return@forEach
                    mixed.addBodyPart(MimeBodyPart().apply {
                        dataHandler = DataHandler(FileDataSource(file))
                        fileName = attachment.fileName
                    })
                }
                setContent(mixed)
            }
        }
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

    private fun angle(id: String): String = "<${id.trim().trim('<', '>')}>"
}
