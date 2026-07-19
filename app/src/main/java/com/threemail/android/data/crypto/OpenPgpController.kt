package com.threemail.android.data.crypto

import android.app.PendingIntent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Package name of the OpenKeychain app that, if installed, would have handled
 * all real OpenPGP crypto. Kept as a constant so the UI can label itself
 * consistently and so the binding can be restored if an upstream artifact
 * becomes available again.
 */
const val KEYCHAIN_PACKAGE = "org.sufficientlysecure.keychain"

/** Verification outcome of a decrypted/verified message's signature. */
enum class SignatureStatus { NONE, VALID, UNVERIFIED, KEY_MISSING, INVALID }

/**
 * Result of an OpenPGP operation. The shape is preserved verbatim from the
 * real OpenKeychain-backed implementation so the compose / message-detail
 * view models keep compiling against the stub without change.
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
 * Stub for the OpenKeychain-backed OpenPGP integration.
 *
 * The original implementation delegated crypto to the external OpenKeychain
 * app through the openpgp-api library (org.openintents.openpgp / AIDL). That
 * artifact has had no reliable published coordinate since JCenter sunset:
 * - Maven Central returns no results for `org.sufficientlysecure:openpgp-api` or `org.openintents:openpgp-api`.
 * - JitPack (`com.github.open-keychain:openpgp-api`) reports builds as `ok` /
 *   `Error` across v8..v12 but every tag's module list is empty and the
 *   canonical jar URLs all return 404.
 *
 * Until a working upstream coordinate is found, this stub keeps the public
 * API intact and forces every operation down the
 * `PgpResult.Unavailable` / `false` path. The UI callers already render that
 * branch as "Install OpenKeychain" / hide the encrypt toggle, so the rest of
 * the app degrades to plaintext transparently and continues to compile +
 * test against the same `PgpResult` sealed interface.
 *
 * Restoring the real implementation: re-add `openpgp-api` to
 * gradle/libs.versions.toml, add jitpack.io to
 * settings.gradle.kts dependencyResolutionManagement.repositories, then
 * paste the original OpenPgpController / PgpText body back in front of this
 * stub. Callers should not need any changes.
 */
@Singleton
class OpenPgpController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Suppress("unused")
    private val appContext: Context = context.applicationContext

    /** Always reports the keychain as missing until the upstream library is restored. */
    fun isKeychainInstalled(): Boolean = false

    suspend fun signAndEncrypt(plain: ByteArray, recipients: List<String>): PgpResult =
        PgpResult.Unavailable

    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult =
        PgpResult.Unavailable
}
