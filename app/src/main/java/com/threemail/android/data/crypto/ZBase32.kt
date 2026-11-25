package com.threemail.android.data.crypto

import java.io.ByteArrayOutputStream

/**
 * zbase32 encoder (RFC 6189) used by WKD SHA-1 email-hash to URL paths.
 * Different alphabet from RFC 4648 base32 - the y-b-n-d-r-f-g-... ordering
 * is a human-friendly variant that minimises visual ambiguity by skipping
 * I, L, O, U, to avoid 1/0/letter confusion.
 *
 * Output is lowercase, no padding characters (the WKD spec elides the
 * trailing padding so decoded bytes are an exact multiple of 8). The
 * caller is responsible for truncating the encoded string at the byte
 * length appropriate for the input.
 */
object ZBase32 {

    /**
     * zbase32 alphabet from RFC 6189 \u00a72 (WKD uses `ybndrfg8ejkmcpqxot1uwisza345h769`).
     */
    private const val ALPHABET = "ybndrfg8ejkmcpqxot1uwisza345h769"

    /**
     * Reverse map for [decode]. Entries default to -1 so a character outside
     * the alphabet is rejected instead of silently decoding as `y` (alphabet
     * index 0), which used to turn garbage input into valid-looking bytes.
     */
    private val DECODE_TABLE: IntArray = IntArray(128) { -1 }.also { table ->
        var idx = 0
        for (ch in ALPHABET) {
            table[ch.code] = idx++
        }
    }

    /**
     * Encode [data] as zbase32. Returns a lowercase string with padding
     * characters stripped; lengths are exact multiples of `ceil(8 * data.size / 5)`.
     */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                out.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return out.toString()
    }

    /**
     * Decode a zbase32 string. Padding is not expected (WKD strips it) so
     * input is len % 5 == 0 OR len % 8 == 0 is supported as RFC 6189 \u00a72.
     * Invalid input returns null.
     */
    fun decode(encoded: String): ByteArray? {
        if (encoded.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (ch in encoded) {
            val v = if (ch.code < 128) DECODE_TABLE[ch.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
