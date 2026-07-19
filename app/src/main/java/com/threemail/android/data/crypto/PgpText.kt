package com.threemail.android.data.crypto

/** Helpers for recognizing and extracting inline (ASCII-armored) PGP blocks. */
object PgpText {
    private const val MESSAGE_HEADER = "-----BEGIN PGP MESSAGE-----"
    private const val MESSAGE_FOOTER = "-----END PGP MESSAGE-----"

    /** True if [text] contains an inline PGP encrypted-message block. */
    fun isEncrypted(text: String?): Boolean = text?.contains(MESSAGE_HEADER) == true

    /**
     * Extract the armored PGP block (header..footer inclusive) from [text], so
     * surrounding quoting/whitespace doesn't confuse the decryptor. Falls back
     * to the whole string if the markers aren't both found.
     */
    fun extractArmored(text: String): String {
        val start = text.indexOf(MESSAGE_HEADER)
        val end = text.indexOf(MESSAGE_FOOTER)
        return if (start >= 0 && end > start) text.substring(start, end + MESSAGE_FOOTER.length) else text
    }
}
