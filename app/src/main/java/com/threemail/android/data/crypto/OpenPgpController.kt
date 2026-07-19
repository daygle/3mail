package com.threemail.android.data.crypto

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.openintents.openpgp.IOpenPgpService2
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.OpenPgpSignatureResult
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Package name of the OpenKeychain app we delegate all OpenPGP crypto to. */
const val KEYCHAIN_PACKAGE = "org.sufficientlysecure.keychain"

/** Verification outcome of a decrypted/verified message's signature. */
enum class SignatureStatus { NONE, VALID, UNVERIFIED, KEY_MISSING, INVALID }

/**
 * Result of an OpenPGP operation. All real cryptography happens inside
 * OpenKeychain; this app only brokers the request. [NeedUserInteraction] carries
 * a [PendingIntent] the UI must launch (passphrase entry, key selection, key
 * confirmation) before retrying the same call.
 */
sealed interface PgpResult {
    data class Success(
        val data: ByteArray,
        val signature: SignatureStatus = SignatureStatus.NONE,
        val signerUserId: String? = null
    ) : PgpResult

    data class NeedUserInteraction(val pendingIntent: PendingIntent) : PgpResult
    data class Error(val message: String) : PgpResult

    /** OpenKeychain isn't installed / couldn't be bound. */
    data object Unavailable : PgpResult
}

/**
 * Thin coroutine wrapper over the OpenKeychain OpenPGP API. Binds to the
 * OpenKeychain service on demand and runs the (blocking) `executeApi` calls off
 * the main thread. The whole feature is opt-in and degrades gracefully: with no
 * OpenKeychain installed every call returns [PgpResult.Unavailable] and mail
 * continues to work unencrypted.
 *
 * Note: this integration delegates to an external app and its passphrase /
 * key-selection flow needs on-device testing; it is intentionally isolated so
 * the rest of the app never depends on it.
 */
@Singleton
class OpenPgpController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @Volatile
    private var connection: OpenPgpServiceConnection? = null

    fun isKeychainInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(KEYCHAIN_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    private suspend fun ensureApi(): OpenPgpApi? {
        if (!isKeychainInstalled()) return null
        connection?.let { existing ->
            if (existing.service != null) return OpenPgpApi(context, existing.service)
        }
        val bound = suspendCancellableCoroutine<OpenPgpServiceConnection?> { cont ->
            lateinit var conn: OpenPgpServiceConnection
            conn = OpenPgpServiceConnection(
                context.applicationContext,
                KEYCHAIN_PACKAGE,
                object : OpenPgpServiceConnection.OnBound {
                    override fun onBound(service: IOpenPgpService2) {
                        if (cont.isActive) cont.resume(conn)
                    }

                    override fun onError(e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
            connection = conn
            conn.bindToService()
        }
        val service = bound?.service ?: return null
        return OpenPgpApi(context, service)
    }

    /**
     * Sign and encrypt [plain] to [recipients] (email user-ids). No explicit
     * signing key is passed, so OpenKeychain prompts for one on first use and
     * remembers it. Output is ASCII-armored (inline PGP).
     */
    suspend fun signAndEncrypt(plain: ByteArray, recipients: List<String>): PgpResult {
        val intent = Intent(OpenPgpApi.ACTION_SIGN_AND_ENCRYPT).apply {
            putExtra(OpenPgpApi.EXTRA_USER_IDS, recipients.toTypedArray())
            putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)
        }
        return execute(intent, plain)
    }

    /** Decrypt and verify an ASCII-armored PGP message. */
    suspend fun decryptAndVerify(cipher: ByteArray): PgpResult =
        execute(Intent(OpenPgpApi.ACTION_DECRYPT_VERIFY), cipher)

    @Suppress("DEPRECATION")
    private suspend fun execute(intent: Intent, input: ByteArray): PgpResult = withContext(Dispatchers.IO) {
        val api = ensureApi() ?: return@withContext PgpResult.Unavailable
        try {
            val out = ByteArrayOutputStream()
            val result = api.executeApi(intent, ByteArrayInputStream(input), out)
            when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                OpenPgpApi.RESULT_CODE_SUCCESS -> {
                    val sig = result.getParcelableExtra<OpenPgpSignatureResult>(OpenPgpApi.RESULT_SIGNATURE)
                    PgpResult.Success(out.toByteArray(), sig.toStatus(), sig?.primaryUserId)
                }
                OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                    val pi = result.getParcelableExtra<PendingIntent>(OpenPgpApi.RESULT_INTENT)
                    if (pi != null) PgpResult.NeedUserInteraction(pi)
                    else PgpResult.Error("OpenKeychain requires user interaction")
                }
                else -> {
                    val err = result.getParcelableExtra<OpenPgpError>(OpenPgpApi.RESULT_ERROR)
                    PgpResult.Error(err?.message ?: "OpenPGP operation failed")
                }
            }
        } catch (e: Exception) {
            PgpResult.Error(e.message ?: "OpenPGP operation failed")
        }
    }

    private fun OpenPgpSignatureResult?.toStatus(): SignatureStatus = when (this?.result) {
        null, OpenPgpSignatureResult.RESULT_NO_SIGNATURE -> SignatureStatus.NONE
        OpenPgpSignatureResult.RESULT_VALID_KEY_CONFIRMED -> SignatureStatus.VALID
        OpenPgpSignatureResult.RESULT_VALID_KEY_UNCONFIRMED -> SignatureStatus.UNVERIFIED
        OpenPgpSignatureResult.RESULT_KEY_MISSING -> SignatureStatus.KEY_MISSING
        else -> SignatureStatus.INVALID
    }
}
