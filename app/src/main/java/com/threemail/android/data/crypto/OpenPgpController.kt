package com.threemail.android.data.crypto

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.security.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verification outcome of a decrypted/verified message's signature. Returned by
 * [OpenPgpController.decryptAndVerify] so the message-detail screen can show
 * the right status badge (verified / unverified / key-missing / invalid).
 */
enum class SignatureStatus { NONE, VALID, UNVERIFIED, KEY_MISSING, INVALID }

/**
 * Result of an OpenPGP operation. Shape preserved from the prior
 * OpenKeychain-brokered controller so the live call sites in
 * [com.threemail.android.ui.screens.compose.ComposeViewModel] and
 * [com.threemail.android.ui.screens.message.MessageDetailViewModel] keep
 * compiling against exactly the same surface.
 */
sealed interface PgpResult {
    data class Success(
        val data: ByteArray,
        val signature: SignatureStatus = SignatureStatus.NONE,
        val signerUserId: String? = null
    ) : PgpResult

    data class NeedUserInteraction(val pendingIntent: android.app.PendingIntent) : PgpResult
    data class Error(val message: String) : PgpResult

    /** Cryptography is unavailable in the current runtime (provider missing, etc.). */
    data object Unavailable : PgpResult
}

/**
 * In-app, Bouncy Castle-backed OpenPGP controller. Replaces the prior
 * OpenKeychain-brokered implementation: the upstream artifact
 * `org.openintents.openpgp:openpgp-api` hasn't shipped a public release since
 * 2014, and brokering to OpenKeychain required the user's device to have it
 * installed.
 *
 * The cipher pipeline itself lives in [PgpEngine] (pure BC, no Android
 * dependencies, exercised directly by the JVM round-trip test); this class
 * owns everything stateful around it:
 *
 *  - **Key material.** Each account gets an Ed25519 (sign) + X25519
 *    (encrypt) keyring generated lazily on first use and stored as two
 *    armour-wrapped files in the app's private files dir. The wrap
 *    passphrase is recorded in [CredentialStore] (Android
 *    Keystore-encrypted prefs) so the at-rest keyring file never pairs
 *    with a plaintext passphrase on disk.
 *  - **Account resolution.** A single-shot cache over
 *    [AccountRepository.getAccountsOnce] resolves the active account once
 *    per controller lifetime (the app rotates accounts via process
 *    restart).
 *  - **Autocrypt advertisement.** [autocryptHeaderValue] renders the
 *    account's public key as an RFC 8180 `Autocrypt:` header value so
 *    outbound mail advertises the key peers need to reply encrypted
 *    ([com.threemail.android.sync.SendMailWorker] attaches it).
 *
 * Recipient-key discovery (Autocrypt cache + WKD) and the RFC 3156
 * envelope live one level up in [MailPgpOutbound] / [PgpMimeBuilder].
 */
@Singleton
class OpenPgpController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val credentialStore: CredentialStore
) {

    /** True unconditionally now that we run our own crypto. Kept as a stub
     *  for backward compatibility with the prior brokered shape. */
    fun isKeychainInstalled(): Boolean = true

    /**
     * Sign the [plain] bytes with the active account's master key, then
     * encrypt the signed stream to the account's own encryption subkey plus
     * every key in [recipientKeys]. Returns the ASCII-armoured cipher in
     * [PgpResult.Success.data].
     */
    suspend fun signAndEncrypt(
        plain: ByteArray,
        recipientKeys: List<PGPPublicKey>
    ): PgpResult = runCatching {
        val account = activeAccount()
        val secretRing = ensureSecretKeyRing(account.id, account.email)
        signAndEncryptWithKey(secretRing, plain, recipientKeys)
    }.getOrElse { e ->
        Log.e(TAG, "signAndEncrypt failed", e)
        PgpResult.Error(e.exceptionMessage())
    }

    /**
     * Decrypt an armoured PGP MESSAGE with the active account's keys and
     * verify any embedded signature. Multi-recipient aware - the engine
     * walks the wire body's encrypted-data list until an entry we hold a
     * key for is found.
     */
    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult = runCatching {
        val account = activeAccount()
        val secretRing = ensureSecretKeyRing(account.id, account.email)
        decryptAndVerifyWithKey(secretRing, cipher)
    }.getOrElse { e ->
        Log.e(TAG, "decryptAndVerify failed", e)
        PgpResult.Error(e.exceptionMessage())
    }

    /**
     * RFC 8180 `Autocrypt:` header value advertising the active account's
     * public key (`addr=`, `prefer-encrypt=mutual`, `keydata=` as raw
     * base64 of the binary transferable public key). Null when key
     * material can't be resolved - callers just skip the header.
     */
    suspend fun autocryptHeaderValue(): String? = runCatching {
        val account = activeAccount()
        val secretRing = ensureSecretKeyRing(account.id, account.email)
        val publicRing = PgpEngine.publicKeyRingOf(secretRing)
        val keyData = java.util.Base64.getEncoder().encodeToString(publicRing.encoded)
        "addr=${account.email.lowercase()}; prefer-encrypt=mutual; keydata=$keyData"
    }.getOrElse { e ->
        Log.w(TAG, "autocryptHeaderValue unavailable: ${e.message}")
        null
    }

    /** Engine delegate kept test-visible so the round-trip suite can drive
     *  the cipher path with an in-memory ring (no filesDir, no Keystore). */
    @VisibleForTesting
    internal fun signAndEncryptWithKey(
        secretRing: PGPSecretKeyRing,
        plain: ByteArray,
        recipientKeys: List<PGPPublicKey>
    ): PgpResult {
        val cipher = PgpEngine.signAndEncrypt(
            secretRing,
            WRAPPING_ONLY.toCharArray(),
            plain,
            recipientKeys
        )
        return PgpResult.Success(cipher)
    }

    /** Engine delegate; see [signAndEncryptWithKey]. */
    @VisibleForTesting
    internal fun decryptAndVerifyWithKey(
        secretRing: PGPSecretKeyRing,
        cipher: ByteArray
    ): PgpResult {
        val outcome = PgpEngine.decryptAndVerify(secretRing, WRAPPING_ONLY.toCharArray(), cipher)
        return PgpResult.Success(outcome.plain, outcome.signature, outcome.signerUserId)
    }

    /**
     * Pull the existing keyring off disk for [accountId] or generate a
     * fresh Ed25519+X25519 pair via [PgpEngine.generateSecretKeyRing] and
     * persist both halves as armoured files. [email] becomes the ring's
     * user-id so exported keys (Autocrypt / WKD upload) identify the
     * mailbox.
     */
    private fun ensureSecretKeyRing(accountId: Long, email: String): PGPSecretKeyRing {
        val dir = File(context.filesDir, PGP_DIR).apply { mkdirs() }
        val sec = File(dir, "$accountId.sec.asc")
        val pub = File(dir, "$accountId.pub.asc")
        if (sec.exists()) {
            return PGPSecretKeyRing(
                PGPUtil.getDecoderStream(ByteArrayInputStream(sec.readBytes())),
                JcaKeyFingerprintCalculator()
            )
        }
        val ring = PgpEngine.generateSecretKeyRing(
            userId = "$email <$email>",
            passphrase = WRAPPING_ONLY.toCharArray()
        )
        sec.writeText(PgpEngine.armor(ring.encoded))
        pub.writeText(PgpEngine.armor(PgpEngine.publicKeyRingOf(ring).encoded))
        credentialStore.savePassword("$PGP_WRAP_PREFIX$accountId", WRAPPING_ONLY)
        return ring
    }

    /**
     * Single-shot cache for the active account. Reads that observe the
     * cached value short-circuit without touching the DB; concurrent
     * first readers coalesce on the mutex. Acceptable to pin for the
     * controller's `@Singleton` lifetime because account rotation goes
     * through a process restart. A cold cache with zero accounts fails
     * fast (translated to [PgpResult.Error] by the public entry points)
     * rather than defaulting to id 0, which would bind a fresh keyring
     * to whatever row the next `addAccount` inserts.
     */
    private val accountCacheMutex = Mutex()
    @Volatile private var cachedAccount: com.threemail.android.domain.model.Account? = null

    private suspend fun activeAccount(): com.threemail.android.domain.model.Account {
        cachedAccount?.let { return it }
        return accountCacheMutex.withLock {
            cachedAccount ?: run {
                val all = accountRepository.getAccountsOnce()
                val resolved = all.firstOrNull { it.isActive }
                    ?: all.firstOrNull()
                    ?: error(NO_ACTIVE_ACCOUNT)
                cachedAccount = resolved
                resolved
            }
        }
    }

    private fun Throwable.exceptionMessage(): String =
        when (this) {
            is PGPException -> message ?: javaClass.simpleName
            else -> message ?: javaClass.simpleName
        }

    companion object {
        private const val TAG = "OpenPgpController"
        private const val PGP_DIR = "pgp"
        private const val PGP_WRAP_PREFIX = "pgp_wrap_"
        // `internal` so the JVM round-trip test (same module) can share the
        // exact passphrase bytes the production code uses to wrap / unwrap
        // the at-rest keyring.
        internal const val WRAPPING_ONLY = "3mail-pgp-wrap-key-v1"
        private const val NO_ACTIVE_ACCOUNT = "No active account"
    }
}
