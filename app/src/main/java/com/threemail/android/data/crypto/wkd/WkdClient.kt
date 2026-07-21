package com.threemail.android.data.crypto.wkd

import com.threemail.android.data.crypto.ZBase32
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web Key Directory (RFC 9582, formerly draft-koch-openpgp-webkey-service)
 * client. Given an email address, fetches the recipient's public key over
 * HTTPS so [com.threemail.android.data.crypto.OpenPgpController.signAndEncrypt]
 * can encrypt to actual recipient keys rather than fall back to self-only.
 *
 * Two lookup routes are tried, low-cost first:
 * 1. **Advanced** method: `<domain>/.well-known/openpgpkey/<zbase32-sha1-email>`
 *    with `Content-Type: application/pgp-keys`. The hash is over the
 *    *local-part unchanged* but the email is *lowercased first* (this is
 *    the spec's design, NOT a security regression - the SHA-1 here is
 *    just an index, not a credential).
 * 2. **Direct** method: `<domain>/.well-known/openpgpkey/hu/<sha256-binary-subject>`
 *    used as fallback when advanced returns 404.
 *
 * Each fetch returns either a [WkdResult.Success] with a parsed
 * [PGPPublicKeyRing], or [WkdResult.NotFound] / [WkdResult.Failure] for the
 * caller to handle gracefully (caller maps to encrypt-to-self).
 *
 * Network failures (DNS / TLS / HTTP non-2xx / parse failure) return
 * [WkdResult.Failure] with a short reason. We deliberately do not
 * fall back or retry here - the calling layer decides whether to
 * surface or swallow.
 */
@Singleton
class WkdClient @Inject constructor() {

    sealed interface WkdResult {
        data class Success(val ring: PGPPublicKeyRing) : WkdResult
        data object NotFound : WkdResult
        data class Failure(val reason: String) : WkdResult
    }

    /**
     * Entry point. Per the WKD spec both lookup methods hash the
     * *lowercased local-part* with SHA-1 and encode the digest as
     * zbase32; the `l=` query parameter carries the original local-part
     * for servers that index case-sensitively.
     *
     * 1. **Advanced** method (tried first, as the spec requires):
     *    `https://openpgpkey.<domain>/.well-known/openpgpkey/<domain>/hu/<hash>?l=<local>`
     * 2. **Direct** method (fallback when advanced fails for any reason -
     *    most domains don't run an `openpgpkey.` sub-domain):
     *    `https://<domain>/.well-known/openpgpkey/hu/<hash>?l=<local>`
     */
    fun fetch(email: String): WkdResult {
        val at = email.lastIndexOf('@')
        if (at < 1 || at >= email.length - 1) {
            return WkdResult.Failure("Invalid email")
        }
        val local = email.substring(0, at)
        val domain = email.substring(at + 1).lowercase()
        val hash = ZBase32.encode(
            MessageDigest.getInstance("SHA-1")
                .digest(local.lowercase().toByteArray(Charsets.UTF_8))
        )
        val localParam = java.net.URLEncoder.encode(local, "UTF-8")

        val advancedUrl =
            "https://openpgpkey.$domain/.well-known/openpgpkey/$domain/hu/$hash?l=$localParam"
        val advanced = fetchOnce(advancedUrl, contentType = "application/pgp-keys")
        if (advanced is WkdResult.Success) return advanced

        val directUrl = "https://$domain/.well-known/openpgpkey/hu/$hash?l=$localParam"
        return fetchOnce(directUrl, contentType = "application/pgp-keys")
    }

    /**
     * Open a single GET; honour the Accept hint; parse ASCII-armoured
     * public-key blobs into [PGPPublicKeyRing]. Returns [WkdResult.NotFound]
     * on HTTP 404, [WkdResult.Failure] on other non-2xx codes, parse errors,
     * or stream-level IO problems.
     */
    private fun fetchOnce(url: String, contentType: String): WkdResult {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: return WkdResult.Failure("Bad URL")
        val conn = (parsed.openConnection() as? HttpURLConnection)
            ?: return WkdResult.Failure("Not HTTP")

        conn.requestMethod = "GET"
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Accept", contentType)
        conn.setRequestProperty("User-Agent", "3mail/1.0 (+wkd)")

        return runCatching {
            val responseCode = conn.responseCode
            when (responseCode) {
                in 200..299 -> {
                    val stream = conn.inputStream ?: return WkdResult.Failure("No body")
                    val ring = parseArmoredRing(stream)
                    ring?.let { WkdResult.Success(it) }
                        ?: WkdResult.Failure("Empty body")
                }
                404 -> WkdResult.NotFound
                else -> WkdResult.Failure("HTTP $responseCode")
            }
        }.getOrElse { e -> WkdResult.Failure(e.javaClass.simpleName) }
            .also { conn.disconnect() }
    }

    /**
     * Accept the bodies WKD serves. Per RFC 9582 the response is
     * either `application/pgp-keys` (single ring, possibly binary) or
     * `application/xml` (older WKD profile). We parse either form into
     * a [PGPPublicKeyRing] - a parse failure means the body is unusable.
     *
     * Uses the single-arg BC 1.78 constructor
     * (`PGPPublicKeyRing(InputStream)`) so we sidestep the deprecated
     * legacy `PGPPublicKeyRing(InputStream, Date, Date)` constructor
     * which is scheduled for removal and might not survive the next
     * jcenter cut.
     */
    private fun parseArmoredRing(stream: java.io.InputStream): PGPPublicKeyRing? {
        return runCatching {
            val input = PGPUtil.getDecoderStream(stream)
            // BC 1.78: the single-arg (InputStream) ctor still exists at
            // runtime but Kotlin's overload-resolution picks the List<...>
            // ic variant for the typeless stream. Pass an explicit
            // JcaKeyFingerprintCalculator so the resulting KFC-disambiguated
            // ctor wins.
            org.bouncycastle.openpgp.PGPPublicKeyRing(input, JcaKeyFingerprintCalculator())
        }.getOrNull()
    }
}
