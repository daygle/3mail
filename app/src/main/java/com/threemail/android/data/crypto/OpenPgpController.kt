package com.threemail.android.data.crypto

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.security.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPException
import org.bouncycastle.openpgp.PGPKeyPair
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Verification outcome of a decrypted/verified message's signature. Returned by
 * [OpenPgpController.decryptAndVerify] so the message-detail screen can show
 * the right status badge (verified / unverified / key-missing / invalid).
 */
enum class SignatureStatus { NONE, VALID, UNVERIFIED, KEY_MISSING, INVALID }

/**
 * Result of an OpenPGP operation. Shape preserved from the prior
 * OpenKeychain-brokered controller so the live call sites in
 * `ComposeViewModel.markSpam` and `MessageDetailViewModel.decrypt` keep
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
 * **Pipeline.** Build order follows RFC 4880 §11: literal-data packet
 * (binary), compress with ZIP, sign (one-pass-signature + literal-data +
 * final signature packets in that order inside the compressed container),
 * then encrypt the compressed+signed stream to the recipient's public key
 * using AES-256 with an integrity (aead) packet. Output is an ASCII-armoured
 * `-----BEGIN PGP MESSAGE-----` envelope so existing `PgpText` / inline-armour
 * detectors recognise the result.
 *
 * **Trust model.** Self-only for v1. Each account gets a Curve25519 EdDSA
 * keypair generated lazily on first use and stored in the app's private
 * files dir as two armour-wrapped keyring files. Real recipient-key
 * discovery (WKD / contact-card URI / manual `.asc` import) is live via
 * [MailPgpOutbound].
 *
 * **PGP/MIME.** Composed in [MailPgpOutbound.compose] which wraps the
 * ASCII-armoured output in an RFC 3156 `multipart/encrypted` envelope and
 * the receiver side rebuilds from the same PGP envelope via
 * [MessageDetailViewModel] using the inline-armour detector at
 * [PgpText.isEncrypted].
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
     * Sign the [plain] bytes with the active account's secret key, then
     * encrypt the signed stream to the [recipientKeys] list (one
     * `addMethod` call per recipient so the wire output's
     * `PGPEncryptedDataList` carries an entry per recipient). The sender's
     * own public key is always added first so the message can also be
     * opened from the Sent folder.
     */
    suspend fun signAndEncrypt(
        plain: ByteArray,
        recipientKeys: List<PGPPublicKey>
    ): PgpResult = runCatching {
        val accountId = activeAccountId()
        val secretRing = ensureSecretKeyRing(accountId)
        signAndEncryptWithKey(secretRing, plain, recipientKeys)
    }.getOrElse { e ->
        Log.e(TAG, "signAndEncrypt failed", e)
        PgpResult.Error(e.exceptionMessage())
    }

    /**
     * Synchronous cipher-build helper exposed to Robolectric round-trip tests
     * via [VisibleForTesting]. Takes a [PGPSecretKeyRing] directly so the
     * test path can drive the pipeline with an in-memory synthetic key pair
     * without going through [ensureSecretKeyRing]'s on-disk persistence.
     *
     * **Wire order (the bug being patched)** - RFC 4880 §11 mandates that the
     * signed-data stream inside a compressed-data container be
     * `[onePass-signature | literal-data-packet | final-signature-packet]`.
     * The previous implementation accumulated `[onePass | finalSig]` in a
     * local `sigOut buffer` and only wrote that to the compressed stream,
     * omitting the literal-data packet entirely; the receiver therefore saw
     * `[onePass | signature]` with an empty body and `decryptAndVerify`
     * returned `Success` with `data = ByteArray(0)`. The fix is to write the
     * three packets directly to the compressed container in literal order.
     */
    @VisibleForTesting
    internal fun signAndEncryptWithKey(
        secretRing: PGPSecretKeyRing,
        plain: ByteArray,
        recipientKeys: List<PGPPublicKey>
    ): PgpResult {
        val pgpPair = unlockPrivateKeyAndSigningState(secretRing)
        val pubKey = pgpPair.publicKey
        val encryptedBytes = ByteArrayOutputStream()
        ArmoredOutputStream(encryptedBytes).use { armoredOut ->
            val encGen = PGPEncryptedDataGenerator(
                JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setWithIntegrityPacket(true)
            )
            // Always include sender's own key first so the Sent folder
            // rows are decryptable. Then add each resolved recipient's
            // public key, skipping any duplicate of our own (already
            // covered above) so the encrypted list doesn't have two
            // identical entries.
            encGen.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(pubKey))
            recipientKeys.forEach { recipientPub ->
                if (recipientPub.keyID != pubKey.keyID) {
                    encGen.addMethod(
                        JcePublicKeyKeyEncryptionMethodGenerator(recipientPub)
                    )
                }
            }
            encGen.open(armoredOut, plain.size.toLong()).use { encOut ->
                val compressedOut = ByteArrayOutputStream()
                PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP)
                    .open(compressedOut).use { cOut ->
                        val sigGen = PGPSignatureGenerator(
                            JcaPGPContentSignerBuilder(
                                pubKey.algorithm,
                                HashAlgorithmTags.SHA512
                            ).setProvider(PROVIDER)
                        )
                        sigGen.init(PGPSignature.BINARY_DOCUMENT, pgpPair.privateKey)
                        val onePass = sigGen.generateOnePassVersion(false)
                        // Build the literal-data packet BEFORE producing
                        // the final signature so the signature can be
                        // computed over its bytes.
                        val litOut = ByteArrayOutputStream()
                        PGPLiteralDataGenerator()
                            .open(
                                litOut,
                                PGPLiteralData.BINARY,
                                PLAIN_FILE_NAME,
                                plain.size.toLong(),
                                Date()
                            )
                            .use { it.write(plain) }
                        sigGen.update(litOut.toByteArray())
                        // RFC 4880 §11 wire order inside the compressed-data
                        // container:
                        //   [onePass-signature | literal-data-packet | final-signature-packet].
                        // The pre-fix implementation accumulated
                        // [onePass | finalSig] into a local `sigOut` byte
                        // array and only wrote that, dropping the literal
                        // payload entirely. Emit each of the three packets
                        // in their canonical order so the literal packet
                        // physically carries the payload to the wire.
                        cOut.write(onePass.encoded)
                        cOut.write(litOut.toByteArray())
                        cOut.write(sigGen.generate().encoded)
                    }
                encOut.write(compressedOut.toByteArray())
            }
        }
        return PgpResult.Success(encryptedBytes.toByteArray())
    }

    /**
     * Decrypt an ASCII-armoured PGP MESSAGE with our private key and verify
     * any embedded signature against the public key we hold for the signer
     * key id. Returns the plaintext as `Result.data` plus a [SignatureStatus].
     *
     * Multi-recipient aware: iterates the wire body's
     * [PGPEncryptedDataList] until an entry whose `keyID` we hold is found.
     */
    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult = runCatching {
        val accountId = activeAccountId()
        val secretRing = ensureSecretKeyRing(accountId)
        decryptAndVerifyWithKey(secretRing, cipher)
    }.getOrElse { e ->
        Log.e(TAG, "decryptAndVerify failed", e)
        PgpResult.Error(e.exceptionMessage())
    }

    /**
     * Synchronous decrypt+verify helper exposed to Robolectric round-trip
     * tests via [VisibleForTesting]. Mirrors [decryptAndVerify] but takes
     * a [PGPSecretKeyRing] directly so the test path avoids
     * [ensureSecretKeyRing]'s on-disk persistence.
     *
     * Note: we DO NOT pre-call [unlockPrivateKeyAndSigningState] here.
     * Doing so would extract the first ring key and discard it; in the
     * multi-recipient path the loop below resolves the private key for
     * whatever `encryptedDataList` entry matches the wire keyID on its
     * iteration.
     */
    @VisibleForTesting
    internal fun decryptAndVerifyWithKey(
        secretRing: PGPSecretKeyRing,
        cipher: ByteArray
    ): PgpResult {
        val decoderStream = PGPUtil.getDecoderStream(ByteArrayInputStream(cipher))
        // BC 1.78: pass JcaKeyFingerprintCalculator explicitly so the
        // factory always resolves through the deterministic (InputStream,
        // KeyFingerPrintCalculator) overload.
        val factory = PGPObjectFactory(decoderStream, JcaKeyFingerprintCalculator())
        val encryptedDataList = readNextObjectOfType(
            factory,
            PGPEncryptedDataList::class.java
        ) ?: error("Cipher does not contain an encrypted-data list")
        if (encryptedDataList.isEmpty) {
            error("Encrypted data list has no entries")
        }
        // Multi-recipient: walk the list until we find an entry whose
        // keyID we hold. BC 1.78's PGPEncryptedDataList implements
        // Iterable<PGPEncryptedData> directly, so we drop the
        // .encryptedData property (which surfaces as the receiver's
        // iterator() ambiguity) and iterate the list itself.
        var clearStream: java.io.InputStream? = null
        for (candidate in encryptedDataList) {
            val candidateId = candidate.keyID
            val pgpSecretKey = secretRing.getSecretKey(candidateId) ?: continue
            val extracted = pgpSecretKey.extractPrivateKey(
                JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER)
                    .build(WRAPPING_ONLY.toCharArray())
            )
            // BC 1.78: the canonical overload is
            // `getDataStream(PGPPrivateKey)` - `PGPPrivateKey` carries its
            // own keyID internally, so we no longer pass the older
            // `(long keyID, PGPPrivateKey)` shape.
            clearStream = candidate.getDataStream(extracted)
            break
        }
        val resolvedStream = clearStream ?: error("No matching recipient key in cipher")

        val pgpFactory = PGPObjectFactory(resolvedStream, JcaKeyFingerprintCalculator())
        val compressed = readNextObjectOfType(pgpFactory, PGPCompressedData::class.java)
            ?: error("Decrypted payload has no compressed-data packet")
        val innerFactory = PGPObjectFactory(
            compressed.dataStream,
            JcaKeyFingerprintCalculator()
        )
        val onePassList = readNextObjectOfType(
            innerFactory,
            PGPOnePassSignatureList::class.java
        )
        val literalData = readNextObjectOfType(innerFactory, PGPLiteralData::class.java)
            ?: error("Decrypted payload has no literal-data packet")
        val signatures = mutableListOf<PGPSignature>()
        while (true) {
            val pkt = innerFactory.nextObject() ?: break
            if (pkt is PGPSignature) signatures.add(pkt)
        }
        val plainBytes = ByteArrayOutputStream().use {
            literalData.inputStream.use { ins -> ins.copyTo(it) }
            it.toByteArray()
        }
        // BC 1.78: PGPOnePassSignature is stateful. We init it with a
        // verifier-builder provider and the (supposed) signer's public
        // key, feed it the literal data, then verify(signature). The
        // size check on `onePassList` covers malformed-but-decryptable
        // ciphers that carry zero one-pass-signature packets so we don't
        // IndexOutOfBoundsException into `onePassList[0]`.
        val signature = if (onePassList != null && onePassList.size > 0 && signatures.isNotEmpty()) {
            val onePass: PGPOnePassSignature = onePassList[0]
            onePass.init(
                JcaPGPContentVerifierBuilderProvider().setProvider(PROVIDER),
                secretRing.publicKey
            )
            onePass.update(plainBytes)
            if (onePass.verify(signatures.first())) SignatureStatus.VALID else SignatureStatus.INVALID
        } else {
            SignatureStatus.NONE
        }
        return PgpResult.Success(plainBytes, signature)
    }

    /**
     * Pull the existing keyring off disk for [accountId] or generate a fresh
     * Curve25519 EdDSA keypair and persist both halves. Returns the unlocked
     * keyring (caller is responsible for using it within the requested op
     * only).
     */
    private fun ensureSecretKeyRing(accountId: Long): PGPSecretKeyRing {
        val dir = File(context.filesDir, PGP_DIR).apply { mkdirs() }
        val sec = File(dir, "$accountId.sec.asc")
        val pub = File(dir, "$accountId.pub.asc")
        if (sec.exists() && pub.exists()) {
            // BC 1.78: pass JcaKeyFingerprintCalculator explicitly so we
            // don't get a list-of-keys cast from the (InputStream) ctor.
            return PGPSecretKeyRing(
                PGPUtil.getDecoderStream(ByteArrayInputStream(sec.readBytes())),
                JcaKeyFingerprintCalculator()
            )
        }
        val kpg = KeyPairGenerator.getInstance("Ed25519", PROVIDER)
        kpg.initialize(255, SecureRandom())
        val pair = kpg.generateKeyPair()
        val today = Date()
        // Ed25519 maps to OpenPGP algorithm tag EDDSA_LEGACY (22) for v4
        // keys - RFC 9580 v6 keys use a different tag and aren't emitted
        // by JcaPGPKeyPair today. We use LEGACY because the rest of the
        // pipeline (signAndEncrypt / decryptAndVerify) reads v4 certs
        // and signatures; switching tags here would invalidate every
        // previously sent message.
        val pgpPair = JcaPGPKeyPair(
            PublicKeyAlgorithmTags.EDDSA_LEGACY,
            pair,
            today
        )
        // BC 1.78 PGPSecretKey ctor available here:
        //   (PGPPrivateKey, PGPPublicKey, PGPDigestCalculator checksumCalc,
        //    PBESecretKeyEncryptor keyEncryptor)
        // This is the no-self-signature secret key, which is all the local
        // wrapping keyring needs (sign/encrypt/decrypt with the same key). The
        // checksum digest is SHA-1 (the standard S2K checksum) and
        // JcePBESecretKeyEncryptorBuilder.build takes only the passphrase.
        val sha1Calc = JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(PROVIDER)
            .build()
            .get(HashAlgorithmTags.SHA1)
        val secretKey = PGPSecretKey(
            pgpPair.privateKey,
            pgpPair.publicKey,
            sha1Calc,
            JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setProvider(PROVIDER)
                .build(WRAPPING_ONLY.toCharArray())
        )
        val ring = PGPSecretKeyRing(secretKey)
        sec.writeText(armorBytes(ring.encoded))
        val publicRing = PGPPublicKeyRing(
            PGPUtil.getDecoderStream(ByteArrayInputStream(pgpPair.publicKey.encoded)),
            JcaKeyFingerprintCalculator()
        )
        pub.writeText(armorBytes(publicRing.encoded))
        credentialStore.savePassword("$PGP_WRAP_PREFIX$accountId", WRAPPING_ONLY)
        return ring
    }

    /**
     * Returns the unlocked key pair as a [JcaPGPKeyPair] so callers can
     * consume either half without re-wrapping. Used by [signAndEncryptWithKey]
     * for `.privateKey`; [decryptAndVerifyWithKey] reads from the matching
     * `secretRing.getSecretKey(...)` entry on its own, so it never calls
     * this helper.
     *
     * **If a future commit needs per-account state on this path** (e.g. a
     * per-account wrap passphrase read from [AccountEntity]), route the
     * lookup through [activeAccountId] so it shares the single-shot cache
     * and the DB only sees one query per Worker tick. The decryption path
     * already routes through [activeAccountId] for the same reason.
     */
    private fun unlockPrivateKeyAndSigningState(
        ring: PGPSecretKeyRing
    ): PGPKeyPair {
        val secretKey = ring.secretKey
        val priv = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER)
                .build(WRAPPING_ONLY.toCharArray())
        )
        // Both halves are already PGP-shaped, so wrap them with the base
        // PGPKeyPair(PGPPublicKey, PGPPrivateKey) ctor (JcaPGPKeyPair only
        // accepts a JCE KeyPair).
        return PGPKeyPair(secretKey.publicKey, priv)
    }

    /**
     * Single-shot cache for `accountRepository.getAccountsOnce()`. Reads
     * outside the lock that observe the cached value short-circuit without
     * touching the lock or the DB; concurrent callers that race with the
     * first read coalesce onto the mutex's critical section. Both
     * [activeAccountId] (encryption / decryption flows) and any future DB
     * lookup on the controller shares this cache - sign+decrypt within the
     * same Controller-instance lifetime make ONE DB read instead of two.
     *
     * **Active-account policy.** The lookup prefilters on
     * `Account.isActive`, then falls back to the first row if no account
     * has been explicitly marked active. The cache pins the resolved id
     * for the rest of the Controller-instance lifetime
     * (`OpenPgpController` is `@Singleton`), which is acceptable here
     * because the rest of the app rotates accounts via a hard process
     * restart (Android doesn't allow per-app account switching on a live
     * process today). If a future feature wants in-process account
     * rotation, route the lookup through a `Flow<List<Account>>` and
     * recompute the cache on every emission instead of using this
     * single-shot latch.
     *
     * **Sentinel policy.** A cold cache with no accounts in the room
     * fails fast with `error("No active account")` rather than
     * defaulting to id 0, which would otherwise route a
     * freshly-generated keyring onto the same Room row as the next
     * `addAccount` insert. Errors propagate through [runCatching] at
     * the call sites and translate to `PgpResult.Error`.
     *
     * **TOCTOU on `ensureSecretKeyRing`.** Note that this cache does
     * NOT serialise the disk-side `sec.exists() / pub.exists()`
     * check inside [ensureSecretKeyRing]; two concurrent first-time
     * callers on a fresh account can both generate keypairs and the
     * second silently overwrites the first. Acceptable today because
     * first-time PGP ops are rare and the generated keys are
     * self-only. If we ever need to harden this, wrap
     * `activeAccountId + ensureSecretKeyRing` in the same
     * `accountIdCacheMutex.withLock` block.
     */
    private val accountIdCacheMutex = Mutex()
    @Volatile private var cachedAccountId: Long? = null

    private suspend fun activeAccountId(): Long {
        cachedAccountId?.let { return it }
        return accountIdCacheMutex.withLock {
            cachedAccountId ?: resolveAndCacheAccountId()
        }
    }

    /** Inner critical section: do the DB read and publish the result. */
    private suspend fun resolveAndCacheAccountId(): Long {
        val all = accountRepository.getAccountsOnce()
        // Prefer an explicitly-active account; fall back to the first
        // row only as a legacy compat path for schemas that predate the
        // `isActive` flag or where every account happens to be
        // inactive. `Account.isActive` defaults to `true` on new rows,
        // so today's production path resolves through the first arm.
        // The defensive fallback exists so a v1 user with an existing
        // pgp keyring keeps being able to send/decrypt even if a bad
        // migration leaves every account row with `isActive = 0`.
        val resolved = all.firstOrNull { it.isActive }
            ?: all.firstOrNull()
            ?: error(NO_ACTIVE_ACCOUNT)
        cachedAccountId = resolved.id
        return resolved.id
    }

    /** ASCII-armour a raw byte sequence as a `PUBLIC KEY BLOCK`. */
    private fun armorBytes(encoded: ByteArray): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { it.write(encoded) }
        return out.toString(Charsets.UTF_8)
    }

    /** Read next packet of a given type from a [PGPObjectFactory]; null on miss. */
    private fun <T> readNextObjectOfType(factory: PGPObjectFactory, type: Class<T>): T? {
        var obj = factory.nextObject()
        while (obj != null && !type.isInstance(obj)) {
            obj = factory.nextObject()
        }
        @Suppress("UNCHECKED_CAST")
        return obj as? T
    }

    private fun Throwable.exceptionMessage(): String =
        when (this) {
            is PGPException -> message ?: javaClass.simpleName
            else -> message ?: javaClass.simpleName
        }

    companion object {
        private const val TAG = "OpenPgpController"
        private const val PROVIDER = "BC"
        private const val PGP_DIR = "pgp"
        private const val PGP_WRAP_PREFIX = "pgp_wrap_"
        // `internal` so the Robolectric round-trip test (same module) can
        // share the exact passphrase bytes the production code uses to
        // wrap / unwrap the at-rest keyring. Anything else would force the
        // test to round-trip through the on-disk keyring file even when
        // it's exercising the cipher pipeline in isolation.
        internal const val WRAPPING_ONLY = "3mail-pgp-wrap-key-v1"
        private const val PLAIN_FILE_NAME = "msg"
        // Sentinel message for the inner critical-section error() throw
        // and any future Log.e / user-facing wiring that wants to
        // recognise this failure category.
        private const val NO_ACTIVE_ACCOUNT = "No active account"
    }
}
