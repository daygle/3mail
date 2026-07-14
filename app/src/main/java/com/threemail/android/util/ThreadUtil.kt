package com.threemail.android.util

/**
 * Derives a stable conversation/thread identifier from RFC 5322 headers so that
 * replies group with their original message across IMAP accounts (which, unlike
 * Gmail, expose no native thread id).
 */
object ThreadUtil {

    private val ANGLE = Regex("<([^>]+)>")

    /**
     * @param messageId the message's own Message-ID header (may include angle brackets)
     * @param references the raw References header (space/newline separated ids)
     * @param inReplyTo the raw In-Reply-To header
     */
    fun deriveThreadId(messageId: String?, references: String?, inReplyTo: String?): String? {
        firstId(references)?.let { return it }
        firstId(inReplyTo)?.let { return it }
        return normalize(messageId)
    }

    private fun firstId(header: String?): String? {
        if (header.isNullOrBlank()) return null
        ANGLE.find(header)?.let { return it.groupValues[1].trim() }
        return header.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun normalize(messageId: String?): String? {
        if (messageId.isNullOrBlank()) return null
        return ANGLE.find(messageId)?.groupValues?.get(1)?.trim() ?: messageId.trim()
    }
}
