package com.threemail.android.ui.screens.compose

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.EmailAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val imapClientFactory: ImapClientFactory,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val selectedAccount: Account? = null,
        val to: String = "",
        val cc: String = "",
        val bcc: String = "",
        val subject: String = "",
        val body: String = "",
        val isSending: Boolean = false,
        val isSent: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        viewModelScope.launch {
            accountRepository.getAccounts().collect { accounts ->
                _uiState.value = _uiState.value.copy(
                    accounts = accounts,
                    selectedAccount = accounts.firstOrNull()
                )
            }
        }
    }

    fun selectAccount(account: Account) {
        _uiState.value = _uiState.value.copy(selectedAccount = account)
    }

    fun onRecoverableAuthHandled() {
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
    }

    fun updateTo(value: String) { _uiState.value = _uiState.value.copy(to = value) }
    fun updateCc(value: String) { _uiState.value = _uiState.value.copy(cc = value) }
    fun updateBcc(value: String) { _uiState.value = _uiState.value.copy(bcc = value) }
    fun updateSubject(value: String) { _uiState.value = _uiState.value.copy(subject = value) }
    fun updateBody(value: String) { _uiState.value = _uiState.value.copy(body = value) }

    fun send() {
        val account = _uiState.value.selectedAccount ?: return
        _uiState.value = _uiState.value.copy(isSending = true, error = null, recoverableAuthIntent = null)
        viewModelScope.launch {
            try {
                val to = parseAddresses(_uiState.value.to)
                val cc = parseAddresses(_uiState.value.cc)
                val bcc = parseAddresses(_uiState.value.bcc)
                val client = imapClientFactory.create(account)
                val result = client.sendMessage(
                    to = to,
                    cc = cc,
                    bcc = bcc,
                    subject = _uiState.value.subject,
                    body = _uiState.value.body,
                    attachments = emptyList()
                )
                result.onSuccess {
                    _uiState.value = _uiState.value.copy(isSending = false, isSent = true)
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isSending = false, error = it.message)
                }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(isSending = false, recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSending = false, error = e.message)
            }
        }
    }

    fun retryAfterRecoverableAuth() {
        send()
    }

    private fun parseAddresses(text: String): List<EmailAddress> {
        return text.split(",", ";").map { it.trim() }
            .filter { it.isNotBlank() }
            .map { EmailAddress(address = it) }
    }
}
