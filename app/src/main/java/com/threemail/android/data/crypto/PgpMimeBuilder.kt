package com.threemail.android.data.crypto

/**
 * PGP/MIME outbound builder (RFC 3156 + RFC 1847).
 *
 * The wire format is:
 * ```
 * Content-Type: multipart/encrypted; boundary="<boundary>"; protocol="application/pgp-encrypted"
 * MIME-Version: 1.0
 *
 * --<boundary>
 * Content-Type: application/pgp-encrypted
 * Content-Disposition: attachment
 *
 * Version: 1
 *
 * --<boundary>
 * Content-Type: application/octet-stream
 * Content-Description: OpenPGP encrypted message
 * Content-Transfer-Encoding: 7bit
 *
 * <base64-encoded OpenPGP ciphertext, encoding the *full* inner MIME tree>
 *
 * --<boundary>--
 * ```
 *
 * The inner MIME tree (a `multipart/mixed` for attachments, or
 * `multipart/related` for inline images, or `text/plain` for body-only)
 * is built by the regular [com.threemail.android.data.remote.MimeBuilder]
 * then rendered to bytes; those bytes are the plaintext input to
 * [OpenPgpController.signAndEncrypt]. The resulting cipher bytes are
 * the ASCII-armoured body of the second PGP/MIME part.
 *
 * This drop's scope is the envelope only - the controller invocation
 * happens in [com.threemail.android.data.repository.MailActions] / compose
 * flow / send worker.
 */
object PgpMimeBuilder {

    /**
     * Builds the multipart/encrypted envelope around an [encryptedBytes]
     * blob (typically the ASCII-armoured OpenPGP output of
     * [OpenPgpController.signAndEncrypt]). Returns the wire bytes ready
     * to be set as the message body.
     */
    fun buildEnvelope(encryptedBytes: ByteArray, boundary: String = defaultBoundary()): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("Content-Type: multipart/encrypted; boundary=\"$boundary\"; protocol=\"application/pgp-encrypted\"\r\n".toByteArray())
        out.write("MIME-Version: 1.0\r\n".toByteArray())
        out.write("\r\n".toByteArray())

        // Pre-encryption part: application/pgp-encrypted with body "Version: 1"
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Type: application/pgp-encrypted\r\n".toByteArray())
        out.write("Content-Disposition: attachment\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write("Version: 1\r\n".toByteArray())
        out.write("\r\n".toByteArray())

        // PGP MESSAGE part: the actual cipher.
        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Type: application/octet-stream\r\n".toByteArray())
        out.write("Content-Description: OpenPGP encrypted message\r\n".toByteArray())
        out.write("Content-Transfer-Encoding: 7bit\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        // Treat the cipher bytes as text content for MIME conformance. The
        // ASCII-armoured form is already 7-bit-safe; binary ciphertext
        // (rare for S/MIME interop) would need a separate code path with
        // Content-Transfer-Encoding: base64.
        out.write(String(encryptedBytes, Charsets.UTF_8).toByteArray())
        out.write("\r\n".toByteArray())

        // Closing boundary.
        out.write("--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    /**
     * Generate a deterministic-looking boundary that won't collide with
     * anything in the inner tree. Random-hex is fine - 32 hex chars
     * are RFC 2046 compliant (must not appear in any enclosed part).
     */
    private fun defaultBoundary(): String {
        val sb = StringBuilder("=_NextPart_3mail_")
        val rng = java.security.SecureRandom()
        for (i in 0 until 16) {
            sb.append(((rng.nextInt() and 0xF) + 0x30).toChar())
        }
        return sb.toString()
    }
}
