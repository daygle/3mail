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
     * key for is found. The account's Autocrypt peer-key cache rides
     * along so peer-signed mail verifies against the signer's cached key
     * instead of reporting "key missing".
     */
    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult = runCatching {
        val account = activeAccount()
        val secretRing = ensureSecretKeyRing(account.id, account.email)
        val peerRings = accountRepository.loadAutocryptPeerKeys(account.id)
            .values
            .mapNotNull(PgpEngine::parsePublicKeyRing)
        decryptAndVerifyWithKey(secretRing, cipher, peerRings)
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
        cipher: ByteArray,
        peerRings: List<org.bouncycastle.openpgp.PGPPublicKeyRing> = emptyList()
    ): PgpResult {
        val outcome = PgpEngine.decryptAndVerify(secretRing, WRAPPING_ONLY.toCharArray(), cipher, peerRings)
        return PgpResult.Success(outcome.plain, outcome.signature, outcome.signerUserId)
    }

    // ── Key management (account-settings UI surface) ──────────────────

    /** Formatted fingerprint of the account's own master key (generating
     *  the keyring on first call if the account doesn't have one yet). */
    suspend fun ownKeyFingerprint(accountId: Long, email: String): String? = runCatching {
        PgpEngine.fingerprintOf(ensureSecretKeyRing(accountId, email))
    }.getOrElse { e ->
        Log.w(TAG, "ownKeyFingerprint unavailable: ${e.message}")
        null
    }

    /**
     * The cached peer keys for [accountId] as `email -> formatted
     * fingerprint` (null fingerprint = cached keydata failed to decode,
     * shown as unparseable so the user knows to re-import).
     */
    suspend fun peerKeyFingerprints(accountId: Long): Map<String, String?> =
        accountRepository.loadAutocryptPeerKeys(accountId).mapValues { (_, keyData) ->
            PgpEngine.parsePublicKeyRing(keyData)?.let(PgpEngine::fingerprintOf)
        }

    /**
     * Manually import a peer public key (ASCII-armoured or raw base64)
     * for [email]. Validates that the keydata decodes and carries an
     * encryption-capable key, then stores it - REPLACING any cached
     * Autocrypt entry: a manual import is the highest-trust source, and
     * [AutocryptLearner] never overwrites existing entries, so the
     * imported key stays authoritative. Returns the formatted
     * fingerprint for the confirmation UI.
     */
    suspend fun importPeerKey(accountId: Long, email: String, keyData: String): Result<String> =
        runCatching {
            val ring = PgpEngine.parsePublicKeyRing(keyData)
                ?: error("Key data did not decode into an OpenPGP public key")
            PgpEngine.encryptionKeyOf(ring)
                ?: error("Key has no encryption-capable (sub)key")
            val cache = accountRepository.loadAutocryptPeerKeys(accountId)
            val merged = LinkedHashMap(cache).apply {
                put(email.trim().lowercase(), keyData.trim())
            }
            accountRepository.replaceAutocryptPeerKeys(accountId, merged)
            PgpEngine.fingerprintOf(ring)
        }

    /** Remove the cached key for [email] from [accountId]'s peer cache. */
    suspend fun removePeerKey(accountId: Long, email: String): Result<Unit> = runCatching {
        val cache = accountRepository.loadAutocryptPeerKeys(accountId)
        val merged = LinkedHashMap(cache).apply { remove(email.trim().lowercase()) }
        accountRepository.replaceAutocryptPeerKeys(accountId, merged)
    }

    /**
     * WKD export of the account's own public key: the binary (non-armoured)
     * transferable public key plus the zbase32 `hu/` filename the WKD spec
     * derives from the lowercased local-part. The caller writes the bytes
     * to a file named [WkdExport.fileName] and uploads it to
     * `https://<domain>/.well-known/openpgpkey/hu/<fileName>` (direct
     * method) - that's all a client can do without controlling the domain.
     */
    data class WkdExport(val fileName: String, val wellKnownPath: String, val keyBytes: ByteArray)

    suspend fun wkdExport(accountId: Long, email: String): WkdExport? = runCatching {
        val at = email.lastIndexOf('@')
        require(at > 0 && at < email.length - 1) { "Not an email address: $email" }
        val local = email.substring(0, at).lowercase()
        val domain = email.substring(at + 1).lowercase()
        val hash = ZBase32.encode(
            java.security.MessageDigest.getInstance("SHA-1")
                .digest(local.toByteArray(Charsets.UTF_8))
        )
        val ring = ensureSecretKeyRing(accountId, email)
        WkdExport(
            fileName = hash,
            wellKnownPath = "https://$domain/.well-known/openpgpkey/hu/$hash",
            keyBytes = PgpEngine.publicKeyRingOf(ring).encoded
        )
    }.getOrElse { e ->
        Log.w(TAG, "wkdExport unavailable: ${e.message}")
        null
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
