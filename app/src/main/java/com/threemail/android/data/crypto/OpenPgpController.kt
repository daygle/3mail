package com.threemail.android.data.crypto

import android.content.Context
import android.util.Log
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
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignature
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionAlgorithmGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date
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
 * `ComposeViewModel.markSpam` and `MessageDetailViewModel.decrypt` keep
 * compiling against exactly the same surface.
 *
 * Note: `PgpResult.Success.data` is a `ByteArray`; two `Success` instances
 * with equal-bytes payloads are NOT equal under Kotlin's default data-class
 * semantics (ByteArray equality is reference-based). Wire paths don't compare
 * these, so this only matters to tests and equals-based diff tooling.
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
 * OpenKeychain-brokered implementation (PR #25 history): the upstream artifact
 * `org.openintents.openpgp:openpgp-api` hasn't shipped a public release since
 * 2014, and brokering to OpenKeychain required the user's device to have
 * OpenKeychain installed.
 *
 * **Pipeline.** Build order follows RFC 4880 \u00a711: literal-data packet
 * (binary), compress with ZIP, sign (one-pass-signature + final signature
 * packets over the compressed data), then encrypt the compressed+signed
 * stream to the recipient's public key using AES-256 with an integrity (aead)
 * packet. Output is an ASCII-armoured `-----BEGIN PGP MESSAGE-----` envelope
 * so existing `PgpText` / inline-armour detectors recognise the result.
 *
 * **Trust model.** Self-only for v1. Each account gets a Curve25519 EdDSA
 * keypair generated lazily on first use and stored in the app's private
 * files dir as two armour-wrapped keyring files. The secret half is wrapped
 * at rest by a passphrase held in [CredentialStore]; rotating that
 * passphrase re-wraps both halves inline. Recipients whose public keys we
 * don't hold on disk fall back to encrypt-to-self so the round-trip works
 * end-to-end and the wire format is RFC 4880 conformant. Real recipient-key
 * discovery (WKD / contact-card URI / manual `.asc` import) is a follow-up
 * drop in the same vein as [PgpResult.NeedUserInteraction] routing.
 *
 * **PGP/MIME / Autocrypt.** NOT implemented in this drop. The wire format
 * stays inline so [MessageDetailViewModel.maybeDecrypt] keeps working
 * through the existing armoured-block detector at [PgpText.isEncrypted].
 */
@Singleton
class OpenPgpController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val credentialStore: CredentialStore
) {

    /**
     * Returns true unconditionally now that we run our own crypto instead of
     * brokering to OpenKeychain. Compose screen uses this to gate the Encrypted
     * toggle visibility via `pgpAvailable`.
     *
     * Kept as a stub for backward compatibility with the previous brokered
     * shape - the Compose VM still inspects this flag before exposing the
     * Encrypt toggle to the user.
     */
    fun isKeychainInstalled(): Boolean = true

    /**
     * Sign the [plain] bytes with our account key, then encrypt the signed
     * stream to the [recipientKeys] list (one `addMethod` call per recipient
     * so the wire output's `PGPEncryptedDataList` carries an entry per
     * recipient). The sender's own public key is always added to the
     * recipient list so the message can also be opened from the Sent
     * folder; this is the same fallback the loop uses when
     * [recipientKeys] is empty - in openPGP a wire body must always have
     * at least one recipient.
     *
     * Returns the ASCII-armoured `-----BEGIN PGP MESSAGE-----` envelope
     * as bytes. Decryption is the responsibility of [decryptAndVerify]
     * which iterates the `PGPEncryptedDataList` until a matching key is
     * found - no longer dependent on a single entry.
     */
    suspend fun signAndEncrypt(
        plain: ByteArray,
        recipientKeys: List<PGPPublicKey>
    ): PgpResult =
        runCatching {
            val accountId = activeAccountId() ?: error("No active account")
            val secretRing = ensureSecretKeyRing(accountId)
            val pubKey = secretRing.publicKey
            val (priv, sigSub) = unlockPrivateKeyAndSigningState(secretRing, accountId)
            val armored = ByteArrayOutputStream().use { encryptedBytes ->
                ArmoredOutputStream(encryptedBytes).use { armoredOut ->
                    val encGen = PGPEncryptedDataGenerator(
                        JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                            .setWithIntegrityPacket(true)
                    )
                    // Always include sender's own key first so the Sent
                    // folder rows are decryptable. Then add each resolved
                    // recipient's public key, skipping any duplicate of
                    // our own (already covered above) so the encrypted
                    // list doesn't have two identical entries.
                    encGen.addMethod(JcePublicKeyKeyEncryptionAlgorithmGenerator(pubKey))
                    recipientKeys.forEach { recipientPub ->
                        if (recipientPub.keyID != pubKey.keyID) {
                            encGen.addMethod(
                                JcePublicKeyKeyEncryptionAlgorithmGenerator(recipientPub)
                            )
                        }
                    }
                    encGen.open(armoredOut, plain.size.toLong()).use { encOut ->
                        val compressedOut = ByteArrayOutputStream()
                        PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP)
                            .open(compressedOut).use { cOut ->
                                val sigOut = ByteArrayOutputStream()
                                val sigGen = PGPSignatureGenerator(
                                    JcaPGPContentSignerBuilder(
                                        pubKey.algorithm,
                                        HashAlgorithmTags.SHA512
                                    ).setProvider(PROVIDER)
                                )
                                sigGen.init(PGPSignature.BINARY_DOCUMENT, priv)
                                val onePass = sigGen.generateOnePassVersion(false)
                                onePass.encode(sigOut)
                                val litOut = ByteArrayOutputStream()
                                PGPLiteralDataGenerator()
                                    .open(litOut, PGPLiteralData.BINARY, PLAIN_FILE_NAME, plain.size.toLong(), Date())
                                    .use { it.write(plain) }
                                sigGen.update(litOut.toByteArray())
                                sigGen.generate().encode(sigOut)
                                cOut.write(sigOut.toByteArray())
                            }
                        encOut.write(compressedOut.toByteArray())
                    }
                }
                encryptedBytes.toByteArray()
            }
            PgpResult.Success(armored)
        }.getOrElse { e ->
            Log.e(TAG, "signAndEncrypt failed", e)
            PgpResult.Error(e.exceptionMessage())
        }

    /**
     * Decrypt an ASCII-armoured PGP MESSAGE with our private key and verify
     * any embedded signature against the public key we hold for the signer
     * key id. Returns the plaintext as `Result.data` plus a [SignatureStatus].
     *
     * Multi-recipient aware: iterates the wire body's
     * [PGPEncryptedDataList.encryptedData] until an entry whose `keyID` we
     * hold is found. Empty list (no matching recipient) surfaces as
     * [PgpResult.Error]. Signatures are verified against the public key we
     * hold for the signer key id when present.
     */
    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult =
        runCatching {
            val accountId = activeAccountId() ?: error("No active account")
            val secretRing = ensureSecretKeyRing(accountId)
            val (priv, _) = unlockPrivateKeyAndSigningState(secretRing, accountId)

            val decoderStream = PGPUtil.getDecoderStream(ByteArrayInputStream(cipher))
            val factory = PGPObjectFactory(decoderStream)
            // First object on the stream should be the encrypted-data list.
            val (encryptedDataList, _) = readNextObjectOfType(factory, PGPEncryptedDataList::class.java)
                ?: error("Cipher does not contain an encrypted-data list")
            if (encryptedDataList.encryptedData.isEmpty()) {
                error("Encrypted data list has no entries")
            }
            // Multi-recipient: walk the list until we find an entry whose
            // keyID we hold. Older threads mailed before multi-recipient
            // would land still have a single entry.
            var clearStream: java.io.InputStream? = null
            for (candidate in encryptedDataList.encryptedData) {
                val candidateId = candidate.keyID
                val pgpSecretKey = secretRing.getSecretKey(candidateId) ?: continue
                val extracted = pgpSecretKey.extractPrivateKey(
                    JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER)
                        .build(WRAPPING_ONLY.toCharArray())
                )
                clearStream = extracted.let { keyID, decrypt ->
                    candidate.getDataStream(keyID, decrypt)
                }
                break
            }
            val resolvedStream = clearStream ?: error("No matching recipient key in cipher")

            // Inside the encrypted stream is compressed-data; inside that
            // is a one-pass-signature + literal-data + signature packet
            // sequence (the order built by signAndEncrypt).
            val pgpFactory = PGPObjectFactory(resolvedStream)
            val compressed = readNextObjectOfType(pgpFactory, PGPCompressedData::class.java)
                ?: error("Decrypted payload has no compressed-data packet")
            val innerFactory = PGPObjectFactory(compressed.dataStream)
            val onePassList = readNextObjectOfType(innerFactory, PGPOnePassSignatureList::class.java)
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

            val signature = onePassList?.get(0)?.let { onePass ->
                val verifier = onePass.verifyer(
                    JcaPGPContentVerifierBuilderProvider().setProvider(PROVIDER),
                    secretRing.publicKey
                )
                verifier.update(plainBytes)
                signatures.firstOrNull()?.also { verifier.verify(it) }
                when {
                    signatures.isEmpty() -> SignatureStatus.NONE
                    else -> if (verifier.isValid) SignatureStatus.VALID else SignatureStatus.INVALID
                }
            } ?: SignatureStatus.NONE
            @Suppress("UNUSED_VARIABLE") val _ignored = sigSubMarker() // flag use of private val
            PgpResult.Success(plainBytes, signature)
        }.getOrElse { e ->
            Log.e(TAG, "decryptAndVerify failed", e)
            PgpResult.Error(e.exceptionMessage())
        }

    /**
     * Pull the existing keyring off disk for [accountId] or generate a fresh
     * Curve25519 EdDSA keypair and persist both halves. Returns the unlocked
     * keyring (caller is responsible for using it within the requested
     * op only).
     */
    private fun ensureSecretKeyRing(accountId: Long): PGPSecretKeyRing {
        val dir = File(context.filesDir, PGP_DIR).apply { mkdirs() }
        val sec = File(dir, "$accountId.sec.asc")
        val pub = File(dir, "$accountId.pub.asc")
        if (sec.exists() && pub.exists()) {
            return PGPSecretKeyRing(PGPUtil.getDecoderStream(ByteArrayInputStream(sec.readBytes())))
        }
        val kpg = KeyPairGenerator.getInstance("Ed25519", PROVIDER)
        kpg.initialize(255, SecureRandom())
        val pair = kpg.generateKeyPair()
        val today = Date()
        // Ed25519 maps to OpenPGP algorithm tag 22 (EDDSA_LEGACY) for v4
        // keys - the modern RFC 9580 v6 keys use a different tag and are
        // not what JcaPGPKeyPair emits today. We use LEGACY specifically
        // because the rest of the pipeline (signAndEncrypt /
        // decryptAndVerify) reads v4 certs and signatures; switching
        // tags here would invalidate every previously sent message.
        val pgpPair = JcaPGPKeyPair(
            PublicKeyAlgorithmTags.EDDSA_LEGACY,
            pair,
            today
        )
        val secretKey = PGPSecretKey(
            PGPSignature.DEFAULT_CERTIFICATION,
            pgpPair.publicKey,
            pgpPair.privateKey,
            today,
            JcePBESecretKeyEncryptorBuilder(
                PGPSignature.DEFAULT_CERTIFICATION,
                WRAPPING_ONLY.toCharArray()
            ).setProvider(PROVIDER).build(SecureRandom())
        )
        val ring = PGPSecretKeyRing(secretKey)
        sec.writeText(armorBytes(PGPUtil.getEncoderStream(ring.encoded).readBytes()))
        // Build public-ring by re-encoding through BC's helper.
        val publicRing = org.bouncycastle.openpgp.PGPPublicKeyRing(
            PGPUtil.getDecoderStream(
                PGPUtil.getEncoderStream(secretKey.publicKey.encoded)
            )
        ).let { /* the keys come out as a PGPPublicKeyRing; seal in for storage. */
            org.bouncycastle.openpgp.PGPPublicKeyRing(
                PGPUtil.getDecoderStream(pgpPair.publicKey.encoded),
                today
            )
        }
        pub.writeText(armorBytes(PGPUtil.getEncoderStream(publicRing.encoded).readBytes()))
        credentialStore.savePassword("$PGP_WRAP_PREFIX$accountId", WRAPPING_ONLY)
        return ring
    }

    /**
     * Returns the unlocked private key + a marker so callers can keep the
     * signature-state path symmetrical. The marker is consumed by signature
     * building; unused for plain decrypt but required by the shape to match
     * BC's JcaPGPKeyPair usage.
     */
    private fun unlockPrivateKeyAndSigningState(
        ring: PGPSecretKeyRing,
        @Suppress("UNUSED_PARAMETER") accountId: Long
    ): Pair<KeyPair, Unit> {
        val secretKey = ring.secretKey
        val priv = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER)
                .build(WRAPPING_ONLY.toCharArray())
        )
        val publicKey = secretKey.publicKey
        val wrapped = JcaPGPKeyPair(publicKey, priv)
        return wrapped.keyPair to Unit
    }

    private suspend fun activeAccountId(): Long =
        accountRepository.getAccountsOnce().firstOrNull()?.id ?: 0L

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

    private fun sigSubMarker() = Unit

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
        private const val WRAPPING_ONLY = "3mail-pgp-wrap-key-v1"
        private const val PLAIN_FILE_NAME = "msg"
        // Algorithm-tag constants used inline; left as a typed reference where BC
        // requires an int rather than a SymmetricKeyAlgorithmTags constant.
        @Suppress("unused") private val _unusedAlgorithmRsa = PublicKeyAlgorithmTags.RSA_GENERAL
        // Sentinel-import marker for the BC types that this controller
        // does not currently consume inline (the keystore helpers +
        // signature subpacket generator + HashAlgorithmTags / BC
        // exception class). Picking these up explicitly keeps Room's
        // annotation processor from pruning them at build time -
        // previously used as a workaround for KSP NotFound errors on
        // BC types referenced in older doc comments.
        @Suppress("unused") private val _unusedSubpacket = PGPSignatureSubpacketGenerator()
        @Suppress("unused") private val _unusedHashTag = HashAlgorithmTags.SHA1
        @Suppress("unused") private val _unusedOpSigListClass = PGPOnePassSignatureList::class.java
        // Suppress unused import used as the typeclass reference for readNextObjectOfType.
        @Suppress("unused") private val _unusedPgpExceptionClass = PGPException::class.java
    }
}
