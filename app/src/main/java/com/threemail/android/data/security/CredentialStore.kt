package com.threemail.android.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists per-account secrets (IMAP passwords) encrypted by a symmetric key
 * held in the Android Keystore.
 *
 * Replaces the previously used `androidx.security.crypto.EncryptedSharedPreferences`
 * library, which was deprecated by Google in favour of platform APIs and direct
 * use of the Android Keystore. The synchronous API surface
 * ([savePassword] / [getPassword] / [deletePassword]) is preserved so callers
 * (e.g. login flows) don't need to switch to coroutines.
 *
 * Storage layout (a regular shared-preferences file at [PREFS_FILE]):
 *  * Pref keys are `pwd:<lowercased-email>` in plain text. The email is
 *    already in the Room DB, so this only repeats known information.
 *  * Pref values are AES/GCM ciphertexts. Each encoded value is the raw
 *    concatenation of the 12-byte IV and the ciphertext (which already
 *    contains the 128-bit GCM auth tag), base64-encoded without wrapping.
 *  * The lowercased email is fed as Additional Authenticated Data (AAD) when
 *    encrypting and decrypting, so an attacker with file access cannot swap
 *    a ciphertext between two accounts.
 *
 * The storage file is keyed at [PREFS_FILE]; any passwords saved by previous
 * builds (encrypted with `EncryptedSharedPreferences`) are silently dropped on
 * first launch after upgrade. Users will need to re-enter IMAP passwords once.
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CredentialStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "threemail_master_key"
        private const val PREFS_FILE = "threemail_credentials_v2"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val IV_BYTES = 12
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        // Minimum valid payload size: 12-byte IV + 16-byte GCM auth tag.
        private const val MIN_PAYLOAD_BYTES = IV_BYTES + GCM_TAG_BYTES
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // Loading the AndroidKeyStore provider involves a Provider service lookup
    // and JCA initialisation. Cache the loaded instance so each
    // encrypt/decrypt call doesn't redo that work — this is on the hot path
    // for every IMAP login.
    private val keystore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    /** Returns the existing master key, generating it on first run. */
    private fun masterKey(): SecretKey {
        val existing = keystore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return generateMasterKey()
    }

    private fun generateMasterKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * Pref-key derivation. Always lowercased so it's symmetric with the
     * AAD used by [encrypt]/[decrypt]; otherwise a case-smudge between
     * save() and get() (e.g. `User@foo` → `user@foo`) would slip into the
     * AEAD check, throw `AEADBadTagException`, and wipe the entry.
     */
    private fun key(email: String) = "pwd:${email.lowercase()}"

    private fun encrypt(email: String, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        // AAD must be the same byte string used at decrypt time, hence lowercased.
        cipher.updateAAD(email.lowercase().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ciphertext, 0, it, iv.size, ciphertext.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(email: String, encoded: String): String? {
        val combined = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            // Format we don't recognise — likely leftover data from a previous
            // storage scheme or filesystem corruption. Drop it so we don't
            // keep returning null forever.
            Log.w(TAG, "Stored password for $email is not valid base64; dropping", e)
            dropEntry(email)
            return null
        }
        if (combined.size < MIN_PAYLOAD_BYTES) {
            Log.w(TAG, "Stored password for $email is truncated (${combined.size} bytes); dropping")
            dropEntry(email)
            return null
        }
        val iv = combined.copyOfRange(0, IV_BYTES)
        val payload = combined.copyOfRange(IV_BYTES, combined.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(email.lowercase().toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            // The ciphertext doesn't authenticate under this email's AAD:
            // either an attacker swapped the entry between accounts (real
            // data-integrity failure), or the file was corrupted. Drop it so
            // we don't keep returning null indefinitely; the user can re-enter
            // their password.
            Log.w(TAG, "AAD mismatch for stored password; dropping entry", e)
            dropEntry(email)
            null
        } catch (e: GeneralSecurityException) {
            // Likely transient — keystore key invalidated by an OEM bug,
            // provider unavailable, etc. Don't delete the entry; the caller
            // can retry or surface a re-auth flow.
            Log.w(TAG, "Could not decrypt stored password for $email", e)
            null
        }
    }

    private fun dropEntry(email: String) {
        prefs.edit().remove(key(email)).apply()
    }

    fun savePassword(email: String, password: String?) {
        val prefKey = key(email)
        if (password.isNullOrEmpty()) {
            prefs.edit().remove(prefKey).apply()
        } else {
            prefs.edit().putString(prefKey, encrypt(email, password)).apply()
        }
    }

    fun getPassword(email: String): String? {
        val encoded = prefs.getString(key(email), null) ?: return null
        return decrypt(email, encoded)
    }

    fun deletePassword(email: String) {
        dropEntry(email)
    }
}
