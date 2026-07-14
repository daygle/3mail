package com.threemail.android.ui.screens.inbox

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val imapClientFactory: ImapClientFactory
) : ViewModel() {

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val selectedAccount: Account? = null,
        val folders: List<MailFolder> = emptyList(),
        val selectedFolder: MailFolder? = null,
        val messages: List<MailMessage> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null
    )

    private val _selectedAccount = MutableStateFlow<Account?>(null)
    private val _selectedFolder = MutableStateFlow<MailFolder?>(null)

    private val accountsFlow = accountRepository.getAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val foldersFlow = _selectedAccount
        .filterNotNull()
        .flatMapLatest { account -> mailRepository.getFolders(account.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val messagesFlow = _selectedFolder
        .filterNotNull()
        .flatMapLatest { folder -> mailRepository.getMessages(folder.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<UiState> = combine(
        accountsFlow,
        foldersFlow,
        messagesFlow,
        _selectedAccount,
        _selectedFolder
    ) { accounts, folders, messages, selectedAccount, selectedFolder ->
        val account = selectedAccount ?: accounts.firstOrNull()
        val folder = selectedFolder ?: folders.firstOrNull { it.type == com.threemail.android.domain.model.FolderType.INBOX } ?: folders.firstOrNull()
        UiState(
            accounts = accounts,
            selectedAccount = account,
            folders = folders,
            selectedFolder = folder,
            messages = messages,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    init {
        viewModelScope.launch {
            accountsFlow.collect { accounts ->
                if (_selectedAccount.value == null && accounts.isNotEmpty()) {
                    _selectedAccount.value = accounts.first()
                }
            }
        }
        viewModelScope.launch {
            foldersFlow.collect { folders ->
                if (_selectedFolder.value == null && folders.isNotEmpty()) {
                    _selectedFolder.value = folders.firstOrNull { it.type == com.threemail.android.domain.model.FolderType.INBOX } ?: folders.first()
                }
            }
        }
    }

    fun selectAccount(account: Account) {
        _selectedAccount.value = account
        _selectedFolder.value = null
    }

    fun selectFolder(folder: MailFolder) {
        _selectedFolder.value = folder
    }

    fun onRecoverableAuthHandled() {
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
    }

    fun sync() {
        val account = _selectedAccount.value ?: return
        val folder = _selectedFolder.value ?: return
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
        viewModelScope.launch {
            try {
                if (account.accountType == AccountType.GMAIL || account.accountType == AccountType.IMAP) {
                    val client = imapClientFactory.create(account)
                    val result = client.fetchMessages(folder.serverId, limit = 50)
                    result.getOrNull()?.let { messages ->
                        mailRepository.saveMessages(messages.map { it.copy(folderId = folder.id) })
                    }
                }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun retryAfterRecoverableAuth() {
        sync()
    }

    fun markAsRead(messageId: Long, isRead: Boolean) {
        viewModelScope.launch {
            mailRepository.updateReadStatus(messageId, isRead)
        }
    }
}
