package com.threemail.android.data.remote.gmail

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val GMAIL_SCOPE = "https://mail.google.com/"
        const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar"
    }

    private val signInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(GMAIL_SCOPE))
            .requestScopes(Scope(CALENDAR_SCOPE))
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    fun getSignInIntent() = signInClient.signInIntent

    fun getSignedInAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    fun isSignedIn(): Boolean {
        return getSignedInAccount() != null
    }

    fun getSignedInEmail(): String? {
        return getSignedInAccount()?.email
    }

    fun hasCalendarConsent(): Boolean {
        return GoogleSignIn.hasPermissions(
            getSignedInAccount() ?: return false,
            Scope(CALENDAR_SCOPE)
        )
    }

    /**
     * Fetches an OAuth2 access token for Gmail IMAP/SMTP.
     * Call this on a background coroutine right before connecting to the mail server.
     *
     * @throws RecoverableAuthException when the user must grant additional consent.
     *         The caller should launch the provided intent and then retry.
     */
    @Throws(RecoverableAuthException::class)
    suspend fun getAccessToken(): String = withContext(Dispatchers.IO) {
        val account = getSignedInAccount()?.account ?: throw IllegalStateException("No Google account is signed in")
        try {
            GoogleAuthUtil.getToken(context, account, "oauth2:$GMAIL_SCOPE")
        } catch (e: UserRecoverableAuthException) {
            val intent = e.intent ?: throw IllegalStateException("Google consent required but no intent was provided", e)
            throw RecoverableAuthException(intent, "Google consent required for mail access")
        }
    }

    /** Returns an Intent the caller can launch to ask the user for the Calendar scope. */
    fun requestCalendarConsentIntent() =
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestScopes(Scope(CALENDAR_SCOPE))
                .build()
        ).signInIntent

    /**
     * Parses the result of the Google Sign-In intent.
     * Returns the account on success, or throws [ApiException] on failure.
     */
    fun handleSignInResult(data: android.content.Intent?): Result<GoogleSignInAccount> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            Result.success(task.getResult(ApiException::class.java))
        } catch (e: ApiException) {
            Result.failure(e)
        }
    }

    fun signOut() {
        signInClient.signOut()
    }
}
