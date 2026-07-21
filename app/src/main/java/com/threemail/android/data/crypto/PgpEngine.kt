package com.threemail.android.data.crypto

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.CompressionAlgorithmTags
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.util.Date

/**
 * Pure Bouncy Castle OpenPGP pipeline. No Android, DI, or persistence
 * dependencies - everything stateful (key files, account resolution,
 * `PgpResult` mapping) lives in [OpenPgpController], which delegates here.
 * That split is what lets the round-trip unit test drive the real cipher
 * path as a plain JVM test with an in-memory ring.
 *
 * # Key profile
 *
 * [generateSecretKeyRing] produces the modern two-key ring:
 * an **Ed25519 master key** (`EDDSA_LEGACY`, v4) carrying the user-id
 * self-certification and `Sign+Certify` key flags, plus an **X25519 ECDH
 * subkey** with `Encrypt` flags. The previous single-Ed25519 design was
 * unusable on the encryption side - EdDSA keys sign, they cannot receive
 * `PGPEncryptedDataList` entries - which is why the encrypt stage now
 * selects the ring's encryption-capable subkey via [encryptionKeyOf]
 * instead of the master key.
 *
 * # Wire format
 *
 * [signAndEncrypt] builds, per RFC 4880 §11.3:
 * `armor( encrypt-to-recipients( compress( onePass | literal | signature ) ) )`
 * with AES-256 + MDC integrity packet; the signature is computed over the
 * literal *payload* bytes (not the packet encoding). [decryptAndVerify]
 * walks the same structure in reverse, trying every
 * [PGPPublicKeyEncryptedData] entry against the ring's secret keys so
 * multi-recipient wire bodies decrypt regardless of entry order.
 */
internal object PgpEngine {

    private const val PROVIDER = "BC"
    private const val PLAIN_FILE_NAME = "msg"

    /**
     * Replace any platform "BC" with the full Bouncy Castle provider. On
     * Android the pre-installed "BC" is a stripped fork without the
     * Ed25519/X25519 KeyPairGenerators or the OpenPGP JCE shims, so keying
     * off the provider *name* is not enough - the class must match too.
     * Idempotent; call before any JCE lookup.
     */
    fun ensureProvider() {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing is BouncyCastleProvider) return
        if (existing != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    /** Outcome of [decryptAndVerify]: plaintext + signature verdict. */
    data class Decrypted(
        val plain: ByteArray,
        val signature: SignatureStatus,
        val signerUserId: String?
    )

    /**
     * Generate the Ed25519 (sign) + X25519 (encrypt) secret keyring for
     * [userId], with both halves wrapped under [passphrase] (AES-256 S2K).
     */
    fun generateSecretKeyRing(userId: String, passphrase: CharArray): PGPSecretKeyRing {
        ensureProvider()
        val now = Date()
        val signingPair = JcaPGPKeyPair(
            PublicKeyAlgorithmTags.EDDSA_LEGACY,
            KeyPairGenerator.getInstance("Ed25519", PROVIDER).generateKeyPair(),
            now
        )
        val encryptionPair = JcaPGPKeyPair(
            PublicKeyAlgorithmTags.ECDH,
            KeyPairGenerator.getInstance("X25519", PROVIDER).generateKeyPair(),
            now
        )
        val sha1Calc = JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(PROVIDER)
            .build()
            .get(HashAlgorithmTags.SHA1)
        val masterSubpackets = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
            setPreferredSymmetricAlgorithms(
                false,
                intArrayOf(SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_128)
            )
            setPreferredHashAlgorithms(
                false,
                intArrayOf(HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA256)
            )
        }.generate()
        val subKeySubpackets = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        }.generate()
        val ringGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            signingPair,
            userId,
            sha1Calc,
            masterSubpackets,
            null,
            JcaPGPContentSignerBuilder(signingPair.publicKey.algorithm, HashAlgorithmTags.SHA512)
                .setProvider(PROVIDER),
            JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setProvider(PROVIDER)
                .build(passphrase)
        )
        ringGen.addSubKey(encryptionPair, subKeySubpackets, null)
        return ringGen.generateSecretKeyRing()
    }

    /** The public half of [secretRing] as a transferable public keyring. */
    fun publicKeyRingOf(secretRing: PGPSecretKeyRing): PGPPublicKeyRing {
        val out = ByteArrayOutputStream()
        secretRing.secretKeys.forEach { out.write(it.publicKey.encoded) }
        return PGPPublicKeyRing(out.toByteArray(), JcaKeyFingerprintCalculator())
    }

    /**
     * The encryption-capable key of a ring: prefer a subkey with encryption
     * key flags, fall back to an encryption-capable master (RSA rings).
     */
    fun encryptionKeyOf(secretRing: PGPSecretKeyRing): PGPPublicKey? {
        val publicKeys = secretRing.secretKeys.asSequence().map { it.publicKey }.toList()
        return publicKeys.firstOrNull { !it.isMasterKey && it.isEncryptionKey }
            ?: publicKeys.firstOrNull { it.isEncryptionKey }
    }

    /** Encryption-key selection for a recipient's public keyring. */
    fun encryptionKeyOf(ring: PGPPublicKeyRing): PGPPublicKey? {
        val publicKeys = ring.publicKeys.asSequence().toList()
        return publicKeys.firstOrNull { !it.isMasterKey && it.isEncryptionKey }
            ?: publicKeys.firstOrNull { it.isEncryptionKey }
    }

    /**
     * Parse recipient keydata into a [PGPPublicKeyRing]. Accepts both wire
     * shapes we cache: ASCII-armoured blocks (WKD write-back, manual
     * import) and the raw-base64 form Autocrypt `keydata=` attributes
     * carry (RFC 8180 strips the armour). Returns null when neither form
     * decodes - the caller maps that to "unparseable" for strict-mode.
     */
    fun parsePublicKeyRing(keyData: String): PGPPublicKeyRing? {
        val trimmed = keyData.trim()
        if (trimmed.isEmpty()) return null
        ensureProvider()
        val armored = runCatching {
            PGPPublicKeyRing(
                PGPUtil.getDecoderStream(ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8))),
                JcaKeyFingerprintCalculator()
            )
        }.getOrNull()
        if (armored != null && trimmed.contains("BEGIN PGP")) return armored
        // Autocrypt keydata: base64 of the binary transferable public key,
        // possibly containing folding whitespace.
        val binary = runCatching {
            val bytes = java.util.Base64.getMimeDecoder().decode(trimmed)
            PGPPublicKeyRing(bytes, JcaKeyFingerprintCalculator())
        }.getOrNull()
        return binary ?: armored
    }

    /** ASCII-armour any OpenPGP-encoded byte sequence. */
    fun armor(encoded: ByteArray): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { it.write(encoded) }
        // Not ByteArrayOutputStream.toString(Charset): that overload is
        // API 33+ on Android (minSdk is 31).
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Sign [plain] with [secretRing]'s master key and encrypt the signed
     * stream to the ring's own encryption subkey plus every key in
     * [recipientKeys] (deduplicated by key id). Returns the ASCII-armoured
     * `PGP MESSAGE`. Throws on any pipeline failure - callers map to
     * [PgpResult.Error].
     */
    fun signAndEncrypt(
        secretRing: PGPSecretKeyRing,
        passphrase: CharArray,
        plain: ByteArray,
        recipientKeys: List<PGPPublicKey>
    ): ByteArray {
        ensureProvider()
        val signingSecret = secretRing.secretKey
        val signingPrivate = signingSecret.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER).build(passphrase)
        )

        // Inner stream: compress( onePass | literal | signature ). The
        // signature hashes the literal PAYLOAD bytes; hashing the packet
        // encoding instead is the classic mistake that makes every
        // verification fail.
        val inner = ByteArrayOutputStream()
        PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP).open(inner).use { compressedOut ->
            val sigGen = PGPSignatureGenerator(
                JcaPGPContentSignerBuilder(signingSecret.publicKey.algorithm, HashAlgorithmTags.SHA512)
                    .setProvider(PROVIDER)
            )
            sigGen.init(PGPSignature.BINARY_DOCUMENT, signingPrivate)
            sigGen.generateOnePassVersion(false).encode(compressedOut)
            PGPLiteralDataGenerator()
                .open(compressedOut, PGPLiteralData.BINARY, PLAIN_FILE_NAME, plain.size.toLong(), Date())
                .use { it.write(plain) }
            sigGen.update(plain)
            sigGen.generate().encode(compressedOut)
        }
        val innerBytes = inner.toByteArray()

        val selfKey = encryptionKeyOf(secretRing)
            ?: throw IllegalStateException("Keyring has no encryption-capable key")
        val encGen = PGPEncryptedDataGenerator(
            JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
                .setProvider(PROVIDER)
        )
        // Sender's own subkey first so Sent-folder copies stay readable;
        // recipients follow in resolution order, deduplicated by key id.
        encGen.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(selfKey).setProvider(PROVIDER))
        val seenKeyIds = mutableSetOf(selfKey.keyID)
        recipientKeys.forEach { recipient ->
            if (seenKeyIds.add(recipient.keyID)) {
                encGen.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(recipient).setProvider(PROVIDER))
            }
        }

        val armoredBytes = ByteArrayOutputStream()
        ArmoredOutputStream(armoredBytes).use { armoredOut ->
            encGen.open(armoredOut, innerBytes.size.toLong()).use { it.write(innerBytes) }
        }
        return armoredBytes.toByteArray()
    }

    /**
     * Decrypt [cipher] (armoured or binary) with whichever of
     * [secretRing]'s keys matches an entry in the wire body's
     * [PGPEncryptedDataList], then verify the embedded signature.
     *
     * Signature verdicts: [SignatureStatus.VALID]/[SignatureStatus.INVALID]
     * when the signer is our own master key (self-signed round trips),
     * [SignatureStatus.KEY_MISSING] when the one-pass packet names a key we
     * don't hold (a peer's signature - verifying it needs their public key,
     * which the decrypt path doesn't carry today), [SignatureStatus.NONE]
     * for unsigned ciphers. Throws on structural or integrity failures.
     */
    fun decryptAndVerify(
        secretRing: PGPSecretKeyRing,
        passphrase: CharArray,
        cipher: ByteArray
    ): Decrypted {
        ensureProvider()
        val objectFactory = PGPObjectFactory(
            PGPUtil.getDecoderStream(ByteArrayInputStream(cipher)),
            JcaKeyFingerprintCalculator()
        )
        val encryptedDataList = firstOfType(objectFactory, PGPEncryptedDataList::class.java)
            ?: throw IllegalArgumentException("Cipher does not contain an encrypted-data list")

        var matched: PGPPublicKeyEncryptedData? = null
        var clearStream: InputStream? = null
        for (entry in encryptedDataList) {
            val publicKeyEntry = entry as? PGPPublicKeyEncryptedData ?: continue
            val secretKey = secretRing.getSecretKey(publicKeyEntry.keyID) ?: continue
            val privateKey = secretKey.extractPrivateKey(
                JcePBESecretKeyDecryptorBuilder().setProvider(PROVIDER).build(passphrase)
            )
            clearStream = publicKeyEntry.getDataStream(
                JcePublicKeyDataDecryptorFactoryBuilder()
                    .setProvider(PROVIDER)
                    .setContentProvider(PROVIDER)
                    .build(privateKey)
            )
            matched = publicKeyEntry
            break
        }
        val resolvedStream = clearStream
            ?: throw IllegalArgumentException("No matching recipient key in cipher")

        var factory = PGPObjectFactory(resolvedStream, JcaKeyFingerprintCalculator())
        val compressed = firstOfType(factory, PGPCompressedData::class.java)
        if (compressed != null) {
            factory = PGPObjectFactory(compressed.dataStream, JcaKeyFingerprintCalculator())
        }
        val onePassList = firstOfType(factory, PGPOnePassSignatureList::class.java)
        val literalData = firstOfType(factory, PGPLiteralData::class.java)
            ?: throw IllegalArgumentException("Decrypted payload has no literal-data packet")
        val plainBytes = ByteArrayOutputStream().use { collector ->
            literalData.inputStream.use { it.copyTo(collector) }
            collector.toByteArray()
        }
        val signatures = mutableListOf<PGPSignature>()
        while (true) {
            val packet = factory.nextObject() ?: break
            if (packet is PGPSignature) signatures.add(packet)
            if (packet is org.bouncycastle.openpgp.PGPSignatureList) {
                for (i in 0 until packet.size()) signatures.add(packet[i])
            }
        }
        // MDC check only after the stream is fully consumed.
        if (matched != null && matched.isIntegrityProtected && !matched.verify()) {
            throw IllegalStateException("Message failed integrity (MDC) check")
        }

        var signerUserId: String? = null
        val signatureStatus = if (onePassList != null && onePassList.size() > 0 && signatures.isNotEmpty()) {
            val onePass = onePassList[0]
            val ownMaster = secretRing.publicKey
            if (onePass.keyID == ownMaster.keyID) {
                signerUserId = ownMaster.userIDs.asSequence().firstOrNull()
                onePass.init(JcaPGPContentVerifierBuilderProvider().setProvider(PROVIDER), ownMaster)
                onePass.update(plainBytes)
                if (onePass.verify(signatures.first())) SignatureStatus.VALID else SignatureStatus.INVALID
            } else {
                SignatureStatus.KEY_MISSING
            }
        } else {
            SignatureStatus.NONE
        }
        return Decrypted(plainBytes, signatureStatus, signerUserId)
    }

    /** Read forward through [factory] until a packet of [type]; null on miss. */
    private fun <T> firstOfType(factory: PGPObjectFactory, type: Class<T>): T? {
        var obj = factory.nextObject()
        while (obj != null && !type.isInstance(obj)) {
            obj = factory.nextObject()
        }
        @Suppress("UNCHECKED_CAST")
        return obj as? T
    }
}
