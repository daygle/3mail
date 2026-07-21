package com.threemail.android.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Round-trip coverage for the Bouncy Castle OpenPGP pipeline in [PgpEngine].
 * Plain JVM tests - the engine has no Android dependencies, which is the
 * point of the engine/controller split: the cipher path that ships is the
 * cipher path under test, with in-memory rings and no Robolectric.
 *
 * What this pins:
 *  - key generation produces the Ed25519 (sign) + X25519 (encrypt) profile
 *    with an encryption-capable subkey ([PgpEngine.encryptionKeyOf] must
 *    not select the signing master - EdDSA keys cannot receive
 *    encrypted-data entries, the defect that sank the previous drop);
 *  - sign+encrypt emits ASCII-armoured `PGP MESSAGE` wire bytes;
 *  - decrypt+verify recovers the exact plaintext and reports
 *    [SignatureStatus.VALID] for the self-signed round trip;
 *  - multi-recipient wire bodies decrypt on BOTH sender and recipient
 *    rings (the recipient sees [SignatureStatus.KEY_MISSING] because it
 *    doesn't hold the sender's public key - the correct verdict, not a
 *    failure);
 *  - [PgpEngine.parsePublicKeyRing] accepts both cached keydata shapes:
 *    ASCII armour (WKD write-back) and raw base64 (Autocrypt `keydata=`).
 */
class PgpEngineRoundTripTest {

    private val passphrase = "test-wrap-passphrase".toCharArray()

    @Test
    fun generated_ring_has_distinct_encryption_subkey() {
        val ring = PgpEngine.generateSecretKeyRing("alice@example.org <alice@example.org>", passphrase)
        val encryptionKey = PgpEngine.encryptionKeyOf(ring)
        assertNotNull("ring must expose an encryption-capable key", encryptionKey)
        assertTrue("encryption key must be a subkey, not the EdDSA master", !encryptionKey!!.isMasterKey)
        assertTrue(
            "master and encryption keys must differ",
            ring.publicKey.keyID != encryptionKey.keyID
        )
    }

    @Test
    fun sign_encrypt_decrypt_verify_round_trips_with_valid_signature() {
        val ring = PgpEngine.generateSecretKeyRing("alice@example.org <alice@example.org>", passphrase)
        val plaintext = "Hello, securely encrypted world!".toByteArray(Charsets.UTF_8)

        val cipher = PgpEngine.signAndEncrypt(ring, passphrase, plaintext, emptyList())
        val cipherText = cipher.toString(Charsets.UTF_8)
        assertTrue(
            "cipher should be ASCII-armoured PGP MESSAGE; got: ${cipherText.take(40)}",
            cipherText.contains("BEGIN PGP MESSAGE")
        )

        val outcome = PgpEngine.decryptAndVerify(ring, passphrase, cipher)
        assertArrayEquals("decrypted plaintext must match original bytes", plaintext, outcome.plain)
        assertEquals(
            "self round trip must verify VALID",
            SignatureStatus.VALID,
            outcome.signature
        )
        assertEquals("alice@example.org <alice@example.org>", outcome.signerUserId)
    }

    @Test
    fun multi_recipient_cipher_decrypts_on_both_sides() {
        val senderRing = PgpEngine.generateSecretKeyRing("alice@example.org", passphrase)
        val recipientRing = PgpEngine.generateSecretKeyRing("bob@example.net", passphrase)
        val recipientKey = PgpEngine.encryptionKeyOf(recipientRing)!!
        val plaintext = "Group mail body covering the multi-recipient path".toByteArray(Charsets.UTF_8)

        val cipher = PgpEngine.signAndEncrypt(senderRing, passphrase, plaintext, listOf(recipientKey))

        // Sender side: encrypt-to-self entry, own signature verifies.
        val senderOutcome = PgpEngine.decryptAndVerify(senderRing, passphrase, cipher)
        assertArrayEquals(plaintext, senderOutcome.plain)
        assertEquals(SignatureStatus.VALID, senderOutcome.signature)

        // Recipient side without the sender's key: decrypts via its own
        // entry; the signer is unknown, so the verdict is KEY_MISSING
        // (shown as "signed, key unavailable" - not a decryption failure).
        val recipientOutcome = PgpEngine.decryptAndVerify(recipientRing, passphrase, cipher)
        assertArrayEquals(plaintext, recipientOutcome.plain)
        assertEquals(SignatureStatus.KEY_MISSING, recipientOutcome.signature)
    }

    @Test
    fun peer_signature_verifies_against_cached_sender_key() {
        val senderRing = PgpEngine.generateSecretKeyRing("alice@example.org", passphrase)
        val recipientRing = PgpEngine.generateSecretKeyRing("bob@example.net", passphrase)
        val recipientKey = PgpEngine.encryptionKeyOf(recipientRing)!!
        val plaintext = "Signed by a peer, verified via the Autocrypt cache".toByteArray(Charsets.UTF_8)

        val cipher = PgpEngine.signAndEncrypt(senderRing, passphrase, plaintext, listOf(recipientKey))

        // Recipient holds the sender's PUBLIC ring (as the Autocrypt
        // learner would have cached it) - the signature now verifies
        // instead of reporting KEY_MISSING.
        val senderPublicRing = PgpEngine.publicKeyRingOf(senderRing)
        val outcome = PgpEngine.decryptAndVerify(
            recipientRing,
            passphrase,
            cipher,
            peerKeys = listOf(senderPublicRing)
        )
        assertArrayEquals(plaintext, outcome.plain)
        assertEquals(SignatureStatus.VALID, outcome.signature)
        assertEquals("alice@example.org", outcome.signerUserId)

        // A WRONG peer key must not verify: an unrelated ring in the cache
        // still leaves the signer unknown.
        val strangerRing = PgpEngine.generateSecretKeyRing("mallory@example.com", passphrase)
        val wrongPeer = PgpEngine.decryptAndVerify(
            recipientRing,
            passphrase,
            cipher,
            peerKeys = listOf(PgpEngine.publicKeyRingOf(strangerRing))
        )
        assertEquals(SignatureStatus.KEY_MISSING, wrongPeer.signature)
    }

    @Test
    fun fingerprint_is_stable_across_secret_and_public_forms() {
        val ring = PgpEngine.generateSecretKeyRing("erin@example.org", passphrase)
        val fromSecret = PgpEngine.fingerprintOf(ring)
        val fromPublic = PgpEngine.fingerprintOf(PgpEngine.publicKeyRingOf(ring))
        assertEquals(fromSecret, fromPublic)
        // v4 fingerprint = 20 bytes = 40 hex chars in 10 groups of 4.
        assertEquals(10, fromSecret.split(" ").size)
        assertTrue(fromSecret.matches(Regex("([0-9A-F]{4} ){9}[0-9A-F]{4}")))
    }

    @Test
    fun recipient_keys_are_deduplicated_by_key_id() {
        val ring = PgpEngine.generateSecretKeyRing("alice@example.org", passphrase)
        val selfKey = PgpEngine.encryptionKeyOf(ring)!!
        val plaintext = "dedup".toByteArray(Charsets.UTF_8)

        // Passing our own subkey as an explicit recipient must not produce
        // a second identical PKESK entry - and must still round-trip.
        val cipher = PgpEngine.signAndEncrypt(ring, passphrase, plaintext, listOf(selfKey, selfKey))
        val outcome = PgpEngine.decryptAndVerify(ring, passphrase, cipher)
        assertArrayEquals(plaintext, outcome.plain)
    }

    @Test
    fun parse_public_key_ring_accepts_armoured_and_autocrypt_base64() {
        val ring = PgpEngine.generateSecretKeyRing("carol@example.com", passphrase)
        val publicRing = PgpEngine.publicKeyRingOf(ring)
        val expectedKeyId = PgpEngine.encryptionKeyOf(ring)!!.keyID

        val armored = PgpEngine.armor(publicRing.encoded)
        val fromArmored = PgpEngine.parsePublicKeyRing(armored)
        assertNotNull("armoured keydata must parse", fromArmored)
        assertEquals(expectedKeyId, PgpEngine.encryptionKeyOf(fromArmored!!)!!.keyID)

        // Autocrypt keydata= shape: raw base64 of the binary key, with the
        // folding whitespace mail transports introduce.
        val base64 = java.util.Base64.getEncoder().encodeToString(publicRing.encoded)
        val folded = base64.chunked(64).joinToString("\r\n ")
        val fromBase64 = PgpEngine.parsePublicKeyRing(folded)
        assertNotNull("raw-base64 Autocrypt keydata must parse", fromBase64)
        assertEquals(expectedKeyId, PgpEngine.encryptionKeyOf(fromBase64!!)!!.keyID)
    }

    @Test
    fun autocrypt_header_round_trips_through_parser_and_key_decode() {
        val ring = PgpEngine.generateSecretKeyRing("dave@example.org", passphrase)
        val keyData = java.util.Base64.getEncoder()
            .encodeToString(PgpEngine.publicKeyRingOf(ring).encoded)
        val headerValue = "addr=dave@example.org; prefer-encrypt=mutual; keydata=$keyData"

        val parsed = AutocryptHeader.parse(headerValue)
        assertNotNull("well-formed Autocrypt header must parse", parsed)
        assertEquals("dave@example.org", parsed!!.email)
        assertEquals("mutual", parsed.preferEncrypt)

        val decoded = PgpEngine.parsePublicKeyRing(parsed.keyDataBase64)
        assertNotNull("parsed keydata must decode into a keyring", decoded)
        assertNotNull(PgpEngine.encryptionKeyOf(decoded!!))
    }

    @Test
    fun zbase32_matches_wkd_reference_vector() {
        // Reference vector from the WKD spec (draft-koch-openpgp-webkey-service):
        // the lowercased local-part "joe.doe" hashes to this zbase32 string.
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("joe.doe".toByteArray(Charsets.UTF_8))
        assertEquals("iy9q119eutrkn8s1mk4r39qejnbu3n5q", ZBase32.encode(digest))
    }
}
