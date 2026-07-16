package com.threemail.android.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores sensitive per-account secrets (IMAP passwords) in an
 * [EncryptedSharedPreferences] file backed by a Keystore master key, instead of
 * keeping them as plaintext columns in the Room database.
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "threemail_credentials",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun key(email: String) = "pwd:${email.lowercase()}"

    fun savePassword(email: String, password: String?) {
        if (password.isNullOrEmpty()) {
            prefs.edit().remove(key(email)).apply()
        } else {
            prefs.edit().putString(key(email), password).apply()
        }
    }

    fun getPassword(email: String): String? = prefs.getString(key(email), null)

    fun deletePassword(email: String) {
        prefs.edit().remove(key(email)).apply()
    }
}
