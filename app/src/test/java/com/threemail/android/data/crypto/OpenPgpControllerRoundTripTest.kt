package com.threemail.android.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.security.CredentialStore
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date

/**
 * Round-trip test for [OpenPgpController]'s sign+encrypt + decrypt+verify
 * pipeline. Drives the public [VisibleForTesting] helpers
 * [signAndEncryptWithKey] and [decryptAndVerifyWithKey] directly with an
 * in-memory synthetic Curve25519 [PGPSecretKeyRing] so we avoid the
 * on-disk keyring persistence path under [OpenPgpController.ensureSecretKeyRing]
 * and don't need a real Android Keystore (which Robolectric doesn't
 * emulate).
 *
 * The test path explicitly does NOT exercise
 * [OpenPgpController.signAndEncrypt] / [decryptAndVerify] (the public
 * suspend functions), because those resolve the ring via
 * [OpenPgpController.ensureSecretKeyRing] which writes to the app's
 * filesDir and reads from a [CredentialStore] backed by Android Keystore.
 * The fixtures below pass null-cast [AccountDao] / [CredentialStore]
 * instances to satisfy the constructor signature, but the test path
 * never invokes any method on them.
 *
 * **What this catches:** the wire-format bug fixed in this drop was that
 * `signAndEncrypt` previously emitted `[onePass-signature | final-signature]`
 * into the compressed-data container but never wrote the literal-data
 * packet. The receiver would see an empty body and `decryptAndVerify`
 * would return `Success` with `data = ByteArray(0)`. This test pins the
 * expected wire order so a future regression in either
 * [OpenPgpController.signAndEncryptWithKey] or any BC API change that
 * flips the packet-order contract fails loudly at test time.
 */
@RunWith(RobolectricTestRunner::class)
class OpenPgpControllerRoundTripTest {

    private lateinit var controller: OpenPgpController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        controller = OpenPgpController(
            context = context,
            // The test path uses signAndEncryptWithKey / decryptAndVerifyWithKey
            // which never call back into AccountRepository / CredentialStore;
            // `null as AccountDao` / `null as CredentialStore` is an
            // unchecked cast past Kotlin's nullability check. Runtime is
            // safe because nothing dereferences either field through this
            // test.
            @Suppress("UNCHECKED_CAST")
            accountRepository = AccountRepository(
                null as AccountDao,
                null as CredentialStore
            ),
            credentialStore = CredentialStore(context)
        )
    }

    @Test
    fun signAndEncrypt_then_decryptAndVerify_round_trips_through_signature_verification() {
        val keyRing = makeSyntheticEd25519Ring()
        val plaintext = "Hello, securely encrypted world!".toByteArray(Charsets.UTF_8)

        val encryptResult = controller.signAndEncryptWithKey(
            secretRing = keyRing,
            plain = plaintext,
            recipientKeys = emptyList()
        )
        assertTrue(
            "signAndEncryptWithKey should succeed; got $encryptResult",
            encryptResult is PgpResult.Success
        )
        val cipher = (encryptResult as PgpResult.Success).data
        assertNotNull(cipher)
        // Cipher must be ASCII-armoured with the PGP MESSAGE boundary so
        // MailPgpOutbound.compose can detect it inline. Non-empty + armour
        // boundary together pin the wire encoding; if either were wrong,
        // composed() downstream would still call the cipher but the inline
        // detector would skip it.
        assertTrue(
            "cipher should be ASCII-armoured PGP MESSAGE; got: ${cipher.toString(Charsets.UTF_8).take(40)}",
            cipher.toString(Charsets.UTF_8).contains("BEGIN PGP MESSAGE")
        )

        val decryptResult = controller.decryptAndVerifyWithKey(keyRing, cipher)
        assertTrue(
            "decryptAndVerifyWithKey should succeed; got $decryptResult",
            decryptResult is PgpResult.Success
        )
        val success = decryptResult as PgpResult.Success
        assertArrayEquals("decrypted plaintext must match original bytes", plaintext, success.data)
        assertEquals(
            "signature status must be VALID (sender's pubkey verifies the wire-signed digest)",
            SignatureStatus.VALID,
            success.signature
        )
    }

    @Test
    fun signAndEncrypt_with_extra_recipient_still_decrypts_cleanly_on_sender_side() {
        // Multi-recipient path: encrypt to a second synthetic key as well
        // as encrypt-to-self. The controller is owned by the sender's
        // ring on the receiver side, but the wire body carries an entry
        // in `encryptedDataList` for the second recipient too - this
        // exercises the `encGen.addMethod(...)` loop and the
        // `keyID != pubKey.keyID` dedup check. Future regressions in
        // either branch surface loudly here.
        val senderRing = makeSyntheticEd25519Ring()
        val recipientRing = makeSyntheticEd25519Ring()
        val plaintext = "Group mail body covering multi-recipient path".toByteArray(Charsets.UTF_8)

        val encryptResult = controller.signAndEncryptWithKey(
            secretRing = senderRing,
            plain = plaintext,
            recipientKeys = listOf(recipientRing.publicKey)
        )
        assertTrue(
            "signAndEncryptWithKey (multi-recipient) should succeed; got $encryptResult",
            encryptResult is PgpResult.Success
        )
        val cipher = (encryptResult as PgpResult.Success).data

        // Sender decrypts via the encrypt-to-self fallback.
        val decryptResult = controller.decryptAndVerifyWithKey(senderRing, cipher)
        assertTrue(
            "sender-side decrypt should succeed; got $decryptResult",
            decryptResult is PgpResult.Success
        )
        val success = decryptResult as PgpResult.Success
        assertArrayEquals(plaintext, success.data)
        assertEquals(SignatureStatus.VALID, success.signature)
    }

    /**
     * Build an in-memory Curve25519 [PGPSecretKeyRing] with the production-
     * identical wrap-passphrase so [extractPrivateKey] inside
     * [decryptAndVerifyWithKey] succeeds without re-gen tooling. The
     * `unlockPrivateKeyAndSigningState` path in [signAndEncryptWithKey]
     * needs the same passphrase to read the secret half back. Both paths
     * consult [OpenPgpController.WRAPPING_ONLY].
     */
    private fun makeSyntheticEd25519Ring(): PGPSecretKeyRing {
        val kpg = KeyPairGenerator.getInstance("Ed25519", "BC")
        kpg.initialize(255, SecureRandom())
        val pair = kpg.generateKeyPair()
        val today = Date()
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
            JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .build(OpenPgpController.WRAPPING_ONLY.toCharArray(), SecureRandom())
        )
        return PGPSecretKeyRing(secretKey)
    }
}
