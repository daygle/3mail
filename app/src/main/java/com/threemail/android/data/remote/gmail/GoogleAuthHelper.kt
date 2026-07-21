package com.threemail.android.data.remote.gmail

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.threemail.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthHelper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    companion object {
        const val GMAIL_SCOPE = "https://mail.google.com/"
    }

    private val credentialManager = CredentialManager.create(context)

    suspend fun signInWithGoogle(activityContext: Context): Result<GoogleUserInfo> = withContext(Dispatchers.Main) {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(context.getString(R.string.default_web_client_id))
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        try {
            val result = credentialManager.getCredential(activityContext, request)
            Result.success(handleCredentialResponse(result))
        } catch (e: NoCredentialException) {
            // No Google account available / user dismissed the picker.
            Result.failure(e)
        } catch (e: GetCredentialException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun handleCredentialResponse(response: GetCredentialResponse): GoogleUserInfo {
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            // Parse via the official factory rather than casting the credential
            // directly - the raw response carries the token in credential.data
            // (see lint's CredentialManagerSignInWithGoogle).
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            return GoogleUserInfo(
                email = googleIdTokenCredential.id,
                displayName = googleIdTokenCredential.displayName,
                idToken = googleIdTokenCredential.idToken
            )
        } else {
            throw Exception("Unexpected credential type: ${credential.type}")
        }
    }

    /**
     * Fetches an OAuth2 access token for Gmail IMAP/SMTP.
     * Call this on a background coroutine right before connecting to the mail server.
     *
     * @throws RecoverableAuthException when the user must grant additional consent.
     *         The caller should launch the provided intent and then retry.
     */
    @Throws(RecoverableAuthException::class)
    suspend fun getAccessToken(email: String): String = withContext(Dispatchers.IO) {
        val account = android.accounts.Account(email, "com.google")
        try {
            GoogleAuthUtil.getToken(context, account, "oauth2:$GMAIL_SCOPE")
        } catch (e: UserRecoverableAuthException) {
            val intent = e.intent ?: throw IllegalStateException("Google consent required but no intent was provided", e)
            throw RecoverableAuthException(intent, "Google consent required for mail access")
        }
    }
}

data class GoogleUserInfo(
    val email: String,
    val displayName: String? = null,
    val idToken: String? = null
)
