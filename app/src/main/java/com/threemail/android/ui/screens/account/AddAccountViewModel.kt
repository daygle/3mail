package com.threemail.android.ui.screens.account

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.gmail.GoogleAuthHelper
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Security
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val googleAuthHelper: GoogleAuthHelper,
    private val mailRemoteFactory: MailRemoteFactory
) : ViewModel() {

    data class UiState(
        val email: String = "",
        val displayName: String = "",
        val password: String = "",
        val server: String = "",
        val port: String = "993",
        val outgoingServer: String = "",
        val outgoingPort: String = "587",
        /**
         * Connection security applied to IMAP and SMTP. Defaults to SSL_TLS
         * to match the implicit-SSL default the legacy `useEncryption=true`
         * row used to produce, so existing users see no behaviour change on
         * upgrade.
         */
        val security: Security = Security.SSL_TLS,
        val accountType: AccountType = AccountType.IMAP,
        val isSaving: Boolean = false,
        val isSaved: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null,
        val pendingGmailEmail: String = "",
        val pendingGmailDisplayName: String = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    fun updateEmail(value: String) { _uiState.value = _uiState.value.copy(email = value) }
    fun updateDisplayName(value: String) { _uiState.value = _uiState.value.copy(displayName = value) }
    fun updatePassword(value: String) { _uiState.value = _uiState.value.copy(password = value) }
    fun updateServer(value: String) { _uiState.value = _uiState.value.copy(server = value) }
    fun updatePort(value: String) { _uiState.value = _uiState.value.copy(port = value) }
    fun updateOutgoingServer(value: String) { _uiState.value = _uiState.value.copy(outgoingServer = value) }
    fun updateOutgoingPort(value: String) { _uiState.value = _uiState.value.copy(outgoingPort = value) }
    fun updateSecurity(value: Security) {
        // Pair the security mode with its typical ports so a user picking
        // STARTTLS doesn't leave the field at the SSL_TLS default of 993 -
        // which would connect to nothing on most plain-IMAP-143 servers.
        // We only auto-reset port fields when the current value still matches
        // a default for the previous mode; a hand-edited port (e.g. 1430 on
        // a stub server) is preserved across security changes.
        val state = _uiState.value
        val currentPort = state.port.toIntOrNull()
        val port = if (currentPort == null || currentPort == defaultIncomingPort(state.security)) {
            defaultIncomingPort(value).toString()
        } else {
            state.port
        }
        val currentOutgoingPort = state.outgoingPort.toIntOrNull()
        val outgoingPort = if (currentOutgoingPort == null || currentOutgoingPort == defaultOutgoingPort(state.security)) {
            defaultOutgoingPort(value).toString()
        } else {
            state.outgoingPort
        }
        _uiState.value = state.copy(
            security = value,
            port = port,
            outgoingPort = outgoingPort
        )
    }
    fun updateAccountType(value: AccountType) { _uiState.value = _uiState.value.copy(accountType = value) }

    /**
     * Standard IMAP port for the chosen security mode. Used as the fallback
     * in [save] when the user hasn't typed one, and as the auto-reset target
     * in [updateSecurity] when the user is still on the previous default.
     */
    private fun defaultIncomingPort(security: Security): Int = when (security) {
        Security.SSL_TLS -> 993
        Security.STARTTLS, Security.NONE -> 143
    }

    /**
     * Standard SMTP submission port for the chosen security mode. Mirrors
     * [defaultIncomingPort]; SSL_TLS SMTP typically lives on 465 (SMTPS),
     * STARTTLS on 587 (submission), NONE on 25 (legacy relay).
     */
    private fun defaultOutgoingPort(security: Security): Int = when (security) {
        Security.SSL_TLS -> 465
        Security.STARTTLS -> 587
        Security.NONE -> 25
    }
    fun updateError(message: String?) { _uiState.value = _uiState.value.copy(error = message) }

    fun onRecoverableAuthHandled() {
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
    }

    fun signInWithGoogle(context: android.content.Context) {
        viewModelScope.launch {
            googleAuthHelper.signInWithGoogle(context).onSuccess { userInfo ->
                saveGmailAccount(userInfo.email, userInfo.displayName.orEmpty())
            }.onFailure { error ->
                updateError("Google Sign-In failed: ${error.message}")
            }
        }
    }

    fun save() {
        val state = _uiState.value
        _uiState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                val account = Account(
                    email = state.email,
                    displayName = state.displayName.ifBlank { state.email.substringBefore("@") },
                    accountType = state.accountType,
                    incomingServer = state.server.ifBlank { null },
                    incomingPort = state.port.toIntOrNull() ?: defaultIncomingPort(state.security),
                    outgoingServer = state.outgoingServer.ifBlank { null },
                    outgoingPort = state.outgoingPort.toIntOrNull() ?: defaultOutgoingPort(state.security),
                    security = state.security,
                    password = state.password.ifBlank { null }
                )
                val test = mailRemoteFactory.create(account).testConnection()
                test.onSuccess {
                    accountRepository.addAccount(account)
                    _uiState.value = _uiState.value.copy(isSaving = false, isSaved = true)
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = it.message)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun saveGmailAccount(email: String, displayName: String) {
        _uiState.value = _uiState.value.copy(
            isSaving = true,
            error = null,
            recoverableAuthIntent = null,
            pendingGmailEmail = email,
            pendingGmailDisplayName = displayName
        )
        viewModelScope.launch {
            try {
                // Gmail uses OAuth XOAUTH2 for both IMAP and SMTP, so the
                // connection-security option is irrelevant on this path. We
                // pin it to SSL_TLS (the historical default) anyway so a
                // potential future fallback to plain IMAP inherits the safest
                // setting.
                val account = Account(
                    email = email,
                    displayName = displayName.ifBlank { email.substringBefore("@") },
                    accountType = AccountType.GMAIL,
                    incomingServer = "imap.gmail.com",
                    incomingPort = 993,
                    security = Security.SSL_TLS,
                    password = null,
                    syncEnabled = true
                )
                val test = mailRemoteFactory.create(account).testConnection()
                test.onSuccess {
                    accountRepository.addAccount(account)
                    _uiState.value = _uiState.value.copy(isSaving = false, isSaved = true)
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isSaving = false, error = it.message)
                }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    recoverableAuthIntent = e.intent
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = e.message)
            }
        }
    }

    fun retryAfterRecoverableAuth() {
        val state = _uiState.value
        saveGmailAccount(state.pendingGmailEmail, state.pendingGmailDisplayName)
    }
}
