package com.threemail.android.ui.screens.inbox

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.Account
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
import com.threemail.android.domain.model.FolderType
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val mailActions: MailActions,
    private val mailRemoteFactory: MailRemoteFactory
) : ViewModel() {

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val selectedAccount: Account? = null,
        val folders: List<MailFolder> = emptyList(),
        val selectedFolder: MailFolder? = null,
        val messages: List<MailMessage> = emptyList(),
        val isSyncing: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null
    )

    private data class Transient(
        val isSyncing: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null
    )

    private val _selectedAccount = MutableStateFlow<Account?>(null)
    private val _selectedFolder = MutableStateFlow<MailFolder?>(null)
    private val _transient = MutableStateFlow(Transient())

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

    private val baseState: StateFlow<UiState> = combine(
        accountsFlow,
        foldersFlow,
        messagesFlow,
        _selectedAccount,
        _selectedFolder
    ) { accounts, folders, messages, selectedAccount, selectedFolder ->
        val account = selectedAccount ?: accounts.firstOrNull()
        val folder = selectedFolder
            ?: folders.firstOrNull { it.type == FolderType.INBOX }
            ?: folders.firstOrNull()
        UiState(
            accounts = accounts,
            selectedAccount = account,
            folders = folders,
            selectedFolder = folder,
            messages = messages
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    val uiState: StateFlow<UiState> = combine(baseState, _transient) { base, transient ->
        base.copy(
            isSyncing = transient.isSyncing,
            error = transient.error,
            recoverableAuthIntent = transient.recoverableAuthIntent
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
                    _selectedFolder.value = folders.firstOrNull { it.type == FolderType.INBOX } ?: folders.first()
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
        _transient.value = _transient.value.copy(recoverableAuthIntent = null)
    }

    fun dismissError() {
        _transient.value = _transient.value.copy(error = null)
    }

    fun sync() {
        val account = _selectedAccount.value ?: return
        val folder = _selectedFolder.value ?: return
        _transient.value = _transient.value.copy(isSyncing = true, error = null, recoverableAuthIntent = null)
        viewModelScope.launch {
            try {
                val remote = mailRemoteFactory.create(account)
                val result = remote.fetchMessages(folder, folder.syncVersion, limit = 100)
                result.onSuccess { fetch ->
                    if (fetch.messages.isNotEmpty()) {
                        mailRepository.saveMessages(fetch.messages.map { it.copy(folderId = folder.id) })
                    }
                    mailRepository.updateFolderCursor(folder.id, fetch.nextCursor)
                }.onFailure { throw it }
                _transient.value = _transient.value.copy(isSyncing = false)
            } catch (e: RecoverableAuthException) {
                _transient.value = _transient.value.copy(isSyncing = false, recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _transient.value = _transient.value.copy(isSyncing = false, error = e.message)
            }
        }
    }

    fun retryAfterRecoverableAuth() {
        sync()
    }

    fun markAsRead(message: MailMessage, isRead: Boolean) {
        viewModelScope.launch { mailActions.setRead(message, isRead) }
    }

    fun toggleStar(message: MailMessage) {
        viewModelScope.launch { mailActions.setStarred(message, !message.isStarred) }
    }

    fun delete(message: MailMessage) {
        viewModelScope.launch { mailActions.delete(message) }
    }

    fun archive(message: MailMessage) {
        viewModelScope.launch { mailActions.archive(message) }
    }
}
