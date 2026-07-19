package com.threemail.android.data.crypto

/**
 * Autocrypt header parser (RFC 8180). Both the `Autocrypt` header (carried
 * in mail the user sends) and `Autocrypt-Gossip` (carried in mail the user
 * receives so senders can opportunistically share keys without their own
 * Autocrypt-compliant client) use the same key=value; semicolon format.
 *
 * Fields we care about:
 *  - `addr`        - the email address the key relates to (required).
 *  - `keydata`     - base64-armoured OpenPGP public key (required,
 *                    non-empty in well-formed input).
 *  - `type`        - OpenPGP for valid keys (we ignore other values).
 *  - `prefer-encrypt` - "mutual" only; means the sender prefers to only
 *                       send encrypted if I also have an autocrypt-compliant
 *                       MUA. We surface the raw value so the UX can decide.
 *
 * Headers we deliberately drop on parse failure: malformed `addr`, empty
 * `keydata`, `type != openpgp`. The parser never throws - bad headers
 * produce `null` so the email pipeline logs once and continues.
 *
 * The `keydata` string is parsed but not converted into a [WkdResult] of
 * an [org.bouncycastle.openpgp.PGPPublicKeyRing] here - that's the caller's
 * job (the importer path lives in
 * [com.threemail.android.data.crypto.OpenPgpController] / [com.threemail.android.data.crypto.wkd.WkdClient]).
 */
object AutocryptHeader {

    data class Parsed(val email: String, val keyDataBase64: String, val preferEncrypt: String? = null)

    /**
     * Parse `headerValue` (the substring after the colon of an
     * `Autocrypt:` or `Autocrypt-Gossip:` header). Returns `null` on
     * malformed input or unknown `type`. Case-insensitive on field names.
     */
    fun parse(headerValue: String?): Parsed? {
        if (headerValue.isNullOrBlank()) return null
        val fields = headerValue.split(';').map { it.trim() }
        var addr: String? = null
        var keyData: String? = null
        var typeIsOpenPgp = true
        var prefer: String? = null
        for (field in fields) {
            val idx = field.indexOf('=')
            if (idx < 0) continue
            val key = field.substring(0, idx).trim().lowercase()
            val value = field.substring(idx + 1).trim()
            when (key) {
                "addr" -> addr = value
                "keydata" -> keyData = value
                "type" -> typeIsOpenPgp = value.equals("openpgp", ignoreCase = true)
                "prefer-encrypt" -> prefer = value
                else -> Unit // unknown keys: forward-compat, drop
            }
        }
        if (!typeIsOpenPgp) return null
        if (addr.isNullOrBlank() || keyData.isNullOrBlank()) return null
        return Parsed(email = addr.lowercase(), keyDataBase64 = keyData, preferEncrypt = prefer)
    }
}
