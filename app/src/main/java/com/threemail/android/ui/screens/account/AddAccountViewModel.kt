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
        val useEncryption: Boolean = true,
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
    fun updateUseEncryption(value: Boolean) { _uiState.value = _uiState.value.copy(useEncryption = value) }
    fun updateAccountType(value: AccountType) { _uiState.value = _uiState.value.copy(accountType = value) }
    fun updateError(message: String?) { _uiState.value = _uiState.value.copy(error = message) }

    fun onRecoverableAuthHandled() {
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
    }

    fun getGoogleSignInIntent() = googleAuthHelper.getSignInIntent()

    fun handleGoogleSignInResult(data: android.content.Intent?) = googleAuthHelper.handleSignInResult(data)

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
                    incomingPort = state.port.toIntOrNull() ?: 993,
                    useEncryption = state.useEncryption,
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
                val account = Account(
                    email = email,
                    displayName = displayName.ifBlank { email.substringBefore("@") },
                    accountType = AccountType.GMAIL,
                    incomingServer = "imap.gmail.com",
                    incomingPort = 993,
                    useEncryption = true,
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
