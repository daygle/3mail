package com.threemail.android.data.crypto

import android.app.PendingIntent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Verification outcome of a decrypted/verified message's signature. */
enum class SignatureStatus { NONE, VALID, UNVERIFIED, KEY_MISSING, INVALID }

/**
 * Result of an OpenPGP operation. Shape preserved verbatim from the original
 * OpenKeychain-backed controller so callers keep compiling against the same
 * sealed surface.
 *
 * Note for restorers: `PgpResult.Success.data` is a `ByteArray`, so two
 * `Success` instances with equal-bytes payloads are NOT equal under Kotlin's
 * default data-class semantics (ByteArray equality is reference-based). Add a
 * test or override `equals`/`hashCode` via `Arrays.contentEquals` if you need
 * value equality.
 */
sealed interface PgpResult {
    data class Success(
        val data: ByteArray,
        val signature: SignatureStatus = SignatureStatus.NONE,
        val signerUserId: String? = null
    ) : PgpResult

    data class NeedUserInteraction(val pendingIntent: PendingIntent) : PgpResult
    data class Error(val message: String) : PgpResult

    /** OpenKeychain isn't installed / couldn't be bound. */
    data object Unavailable : PgpResult
}

/**
 * Stub for the OpenKeychain-backed OpenPGP integration. See PR #25 history
 * (commits 1745110 / 3135744 / 685351d / <this>) for the full
 * upstream-coordinate investigation; no published coordinate for the
 * `openpgp-api` artifact resolves at this time so the real
 * OpenKeychain-brokered implementation has been stubbed out.
 *
 * Until a working upstream coordinate resurfaces, every operation funnels
 * through `PgpResult.Unavailable` / `false` and the rest of the app
 * degrades to plaintext transparently: the compose screen hides the Encrypt
 * toggle when `isKeychainInstalled()` is false, the message-detail screen
 * never enters its decrypt path, and existing tests (none of which
 * reference `OpenPgpController` or `PgpResult`) compile unchanged.
 *
 * Restoring the real implementation: re-add `openpgp-api` to
 * gradle/libs.versions.toml, declare jitpack.io in
 * settings.gradle.kts dependencyResolutionManagement.repositories, then
 * paste the original `OpenPgpController.kt` body back over this stub. The
 * public method signatures (`isKeychainInstalled`, `signAndEncrypt`,
 * `decryptAndVerify`) and the `PgpResult` shape are preserved, so the call
 * sites in `ComposeViewModel.kt` and `MessageDetailViewModel.kt` need no
 * edits.
 */
@Singleton
class OpenPgpController @Inject constructor(
    @ApplicationContext context: Context
) {

    fun isKeychainInstalled(): Boolean = false

    suspend fun signAndEncrypt(plain: ByteArray, recipients: List<String>): PgpResult =
        PgpResult.Unavailable

    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult =
        PgpResult.Unavailable
}
