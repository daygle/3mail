package com.threemail.android.data.crypto

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verification outcome of a decrypted/verified message's signature. Kept so the
 * message-detail screen's status-badge code compiles unchanged.
 */
enum class SignatureStatus { NONE, VALID, UNVERIFIED, KEY_MISSING, INVALID }

/**
 * Result of an OpenPGP operation. Surface preserved so `ComposeViewModel` and
 * `MessageDetailViewModel` keep compiling against the same API.
 */
sealed interface PgpResult {
    data class Success(
        val data: ByteArray,
        val signature: SignatureStatus = SignatureStatus.NONE,
        val signerUserId: String? = null
    ) : PgpResult

    data class NeedUserInteraction(val pendingIntent: android.app.PendingIntent) : PgpResult
    data class Error(val message: String) : PgpResult
    data object Unavailable : PgpResult
}

/**
 * OpenPGP is temporarily disabled.
 *
 * The in-app Bouncy Castle 1.78 crypto migration left this module unable to
 * compile (wrong BC constructor/API shapes across key generation, signing, and
 * decryption). To restore a green build it was reverted to this inert stub:
 * every operation reports [PgpResult.Unavailable], the compose "Encrypt" toggle
 * stays hidden ([isKeychainInstalled] returns false), and mail is sent and read
 * as plaintext exactly as before OpenPGP was introduced.
 *
 * The supporting crypto helpers (`AutocryptHeader`, `PgpMimeBuilder`,
 * `WkdClient`, `ZBase32`) remain in the tree, dormant, so reintroducing OpenPGP
 * later is a matter of restoring this controller against a working toolchain
 * rather than rebuilding everything.
 */
@Singleton
class OpenPgpController @Inject constructor() {

    /** No OpenPGP provider is wired up, so the compose encrypt affordance stays hidden. */
    fun isKeychainInstalled(): Boolean = false

    /** OpenPGP disabled: the message view falls back to showing the raw body. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult = PgpResult.Unavailable
}
