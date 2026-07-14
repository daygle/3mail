package com.threemail.android.util

import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.MailMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure text helpers for composing replies and forwards. Kept free of any Android
 * dependency so they can be unit-tested on the JVM.
 */
object MailText {

    private val REPLY_PREFIX = Regex("^\\s*re\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val FORWARD_PREFIX = Regex("^\\s*(fwd?|forward)\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun replySubject(subject: String): String =
        if (REPLY_PREFIX.containsMatchIn(subject)) subject else "Re: ${subject.trim()}"

    fun forwardSubject(subject: String): String =
        if (FORWARD_PREFIX.containsMatchIn(subject)) subject else "Fwd: ${subject.trim()}"

    /** Builds the quoted body shown when replying to [original]. */
    fun replyQuote(original: MailMessage): String {
        val when_ = formatQuoteDate(original.date)
        val who = original.from.firstOrNull()?.toString() ?: "someone"
        val quoted = (bestBody(original)).lineSequence().joinToString("\n") { "> $it" }
        return "\n\nOn $when_, $who wrote:\n$quoted"
    }

    /** Builds the forwarded body shown when forwarding [original]. */
    fun forwardQuote(original: MailMessage): String {
        val header = buildString {
            append("\n\n---------- Forwarded message ----------\n")
            append("From: ${original.from.joinToString { it.toString() }}\n")
            append("Date: ${formatQuoteDate(original.date)}\n")
            append("Subject: ${original.subject}\n")
            append("To: ${original.to.joinToString { it.toString() }}\n\n")
        }
        return header + bestBody(original)
    }

    private fun bestBody(message: MailMessage): String =
        message.bodyPlain?.takeIf { it.isNotBlank() }
            ?: message.bodyHtml?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }
            ?: message.bodyPreview

    /** Very small HTML-to-text fallback for quoting; not a full renderer. */
    fun stripHtml(html: String): String =
        html.replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), "")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Recipients used for reply-all: everyone except our own address. */
    fun replyAllRecipients(original: MailMessage, selfAddress: String): Pair<List<EmailAddress>, List<EmailAddress>> {
        val self = selfAddress.trim().lowercase()
        val to = (original.from + original.to)
            .filter { it.address.trim().lowercase() != self }
            .distinctBy { it.address.lowercase() }
        val cc = original.cc
            .filter { it.address.trim().lowercase() != self }
            .filter { addr -> to.none { it.address.equals(addr.address, ignoreCase = true) } }
            .distinctBy { it.address.lowercase() }
        return to to cc
    }

    private fun formatQuoteDate(timestamp: Long): String =
        SimpleDateFormat("EEE, MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date(timestamp))
}
