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

    /**
     * PGP/MIME (RFC 3156) envelope detected at the top level of an inbound
     * message. Wraps the OpenPGP ciphertext bytes plus the metadata needed
     * to hand the bytes to [com.threemail.android.data.crypto.OpenPgpController.decryptAndVerify].
     * The plaintext inside the cipher is the *full inner MIME tree* built
     * by [com.threemail.android.data.remote.MimeBuilder] on the sender side
     * (typically a `multipart/alternative` body + `multipart/mixed` for
     * attachments); the caller is responsible for re-walking the decrypted
     * output through [extractParts].
     */
    data class PgpMimeEnvelope(
        /** PGP/MIME version - currently always "1" per RFC 3156. */
        val algorithmVersion: String,
        /**
         * OpenPGP ciphertext. May be inline-ASCII-armoured
         * (Content-Transfer-Encoding: 7bit) or base64-armoured, both of
         * which are decoded by [detectPgpMessage] before being returned.
         */
        val cipherBytes: ByteArray,
        /** Identifier for logs (typically "multipart/encrypted"). */
        val source: String
    )

    /**
     * Returns a [PgpMimeEnvelope] if [topPart] is a well-formed
     * `multipart/encrypted` envelope per RFC 3156 - i.e. exactly two
     * child parts: an `application/pgp-encrypted` control part carrying
     * `Version: 1` plus an `application/octet-stream` part carrying the
     * OpenPGP bytes. Returns null for any other structure - JavaMail MIME
     * messages without PGP/MIME walk past this hook unchanged.
     *
     * The decoder is intentionally permissive: it accepts both 7bit
     * (raw ASCII-armoured bytes) and base64 transport encodings so the
     * few clients that emit base64 (a few corporate MTAs) still round-trip
     * cleanly. Decoding failures fall back to the raw bytes rather than
     * dropping the message.
     */
    fun detectPgpMessage(topPart: Part): PgpMimeEnvelope? {
        return try {
            if (!topPart.isMimeType("multipart/encrypted")) return null
            val tlType = topPart.contentType?.lowercase().orEmpty()
            if ("application/pgp-encrypted" !in tlType) return null
            val multipart = topPart.content as? Multipart ?: return null
            if (multipart.count != 2) return null

            var version: String? = null
            var cipher: ByteArray? = null
            for (i in 0 until multipart.count) {
                val part = multipart.getBodyPart(i) ?: continue
                val ct = part.contentType?.lowercase().orEmpty()
                when {
                    "application/pgp-encrypted" in ct -> {
                        val body = part.content?.toString().orEmpty()
                        val line = body.lines()
                            .firstOrNull { it.startsWith("Version:", ignoreCase = true) }
                        version = line?.removePrefix("Version:")?.trim()
                            ?.removePrefix("v")?.removePrefix("V")?.trim()
                    }
                    "application/octet-stream" in ct -> {
                        val raw = part.inputStream.use { it.readBytes() }
                        val cte = part.getHeader("Content-Transfer-Encoding")
                            ?.firstOrNull()?.lowercase()
                        cipher = if (cte == "base64") {
                            runCatching { java.util.Base64.getMimeDecoder().decode(raw) }
                                .getOrElse { raw }
                        } else {
                            raw
                        }
                    }
                }
            }
            if (version == null || cipher == null) return null
            PgpMimeEnvelope(
                algorithmVersion = version,
                cipherBytes = cipher,
                source = "multipart/encrypted"
            )
        } catch (_: Exception) {
            null
        }
    }

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
