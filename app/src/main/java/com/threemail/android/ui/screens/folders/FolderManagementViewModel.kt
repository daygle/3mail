package com.threemail.android.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.data.repository.FolderPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the folder-visibility management screen: lists every folder of the
 * selected account with a show/hide toggle. Hiding a folder keeps it in the
 * database (and syncing) but removes it from the navigation drawer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FolderManagementViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val mailRemoteFactory: MailRemoteFactory
) : ViewModel() {

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val selectedAccount: Account? = null,
        val folders: List<MailFolder> = emptyList()
    )

    private val _selectedAccount = MutableStateFlow<Account?>(null)

    private val accountsFlow = accountRepository.getAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val foldersFlow = _selectedAccount
        .filterNotNull()
        .flatMapLatest { account -> mailRepository.getFolders(account.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<UiState> = combine(
        accountsFlow,
        foldersFlow,
        _selectedAccount
    ) { accounts, folders, selected ->
        UiState(
            accounts = accounts,
            selectedAccount = selected ?: accounts.firstOrNull(),
            folders = folders
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
    }

    fun selectAccount(account: Account) {
        _selectedAccount.value = account
    }

    fun setHidden(folder: MailFolder, hidden: Boolean) {
        viewModelScope.launch {
            // Local visibility drives the drawer immediately.
            mailRepository.setFolderHidden(folder.id, hidden)
            // Propagate to the server as an IMAP (un)subscribe so other clients
            // honor it. No-op success for Gmail/POP3, and a server failure must
            // not undo the local hide, so it's best-effort.
            val account = _selectedAccount.value ?: accountRepository.getAccountById(folder.accountId)
            if (account != null) {
                runCatching { mailRemoteFactory.create(account).setSubscribed(folder, !hidden) }
            }
        }
    }

    /**
     * One-shot messages for the screen's snackbar. The screen maps each event
     * to a localized string; keeping the event provider-agnostic (and free of
     * `Context`) means the view-model needs no Android string handles.
     */
    sealed interface FolderEvent {
        /** Folder was successfully renamed/moved/deleted; carries the display name. */
        data class Renamed(val name: String) : FolderEvent
        data class Moved(val name: String) : FolderEvent
        data class Deleted(val name: String) : FolderEvent
        /** Server (or transport) rejected the operation. */
        data class Failed(val name: String) : FolderEvent
        /** Client-side validation stopped the operation before any server call. */
        object InvalidName : FolderEvent
        object DuplicateName : FolderEvent
    }

    private val _events = MutableSharedFlow<FolderEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FolderEvent> = _events

    /**
     * Rename a custom folder's leaf (its parent is unchanged). Validates the
     * new name, renames on the server, and only then rewrites the local cache
     * (folder + descendants) so the tree never drifts ahead of the server.
     */
    fun renameFolder(folder: MailFolder, newLeafName: String) {
        viewModelScope.launch {
            val folders = uiState.value.folders
            val account = accountFor(folder) ?: return@launch
            val remote = mailRemoteFactory.create(account)
            val separator = separatorFor(remote, folders)
            val trimmed = newLeafName.trim()
            if (trimmed.isEmpty() || trimmed.indexOf(separator) >= 0) {
                _events.emit(FolderEvent.InvalidName)
                return@launch
            }
            if (trimmed == folder.name) return@launch // no-op rename
            val newServerId = FolderPaths.renamed(folder.serverId, trimmed, separator)
            // Case-insensitive: most IMAP servers treat folder paths that differ
            // only in case as the same mailbox, so flag the clash locally rather
            // than let the server reject it with a generic error.
            if (folders.any { it.serverId.equals(newServerId, ignoreCase = true) }) {
                _events.emit(FolderEvent.DuplicateName)
                return@launch
            }
            relocate(account, remote, folder, newServerId, trimmed, folders, separator, FolderEvent.Renamed(trimmed))
        }
    }

    /**
     * Move a custom folder under [newParent] (or to the top level when null),
     * keeping its own name. Rejects moving a folder into itself or one of its
     * descendants, and rejects a destination already occupied by another folder.
     */
    fun moveFolder(folder: MailFolder, newParent: MailFolder?) {
        viewModelScope.launch {
            val folders = uiState.value.folders
            val account = accountFor(folder) ?: return@launch
            val remote = mailRemoteFactory.create(account)
            val separator = separatorFor(remote, folders)
            if (newParent != null &&
                FolderPaths.isSelfOrDescendant(folder.serverId, newParent.serverId, separator)
            ) {
                _events.emit(FolderEvent.Failed(folder.name))
                return@launch
            }
            val newServerId = FolderPaths.reparented(folder.serverId, newParent?.serverId, separator)
            if (newServerId == folder.serverId) return@launch // already in place
            if (folders.any { it.serverId.equals(newServerId, ignoreCase = true) }) {
                _events.emit(FolderEvent.DuplicateName)
                return@launch
            }
            relocate(account, remote, folder, newServerId, folder.name, folders, separator, FolderEvent.Moved(folder.name))
        }
    }

    /** Delete a custom folder and its subfolders on the server, then locally. */
    fun deleteFolder(folder: MailFolder) {
        viewModelScope.launch {
            val folders = uiState.value.folders
            val account = accountFor(folder) ?: return@launch
            val remote = mailRemoteFactory.create(account)
            val separator = separatorFor(remote, folders)
            val serverIds = listOf(folder.serverId) +
                FolderPaths.descendantsOf(folder.serverId, folders, separator).map { it.serverId }
            val result = runCatching { remote.deleteFolder(folder.serverId) }.getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                mailRepository.applyFolderDeletion(account.id, serverIds)
                _events.emit(FolderEvent.Deleted(folder.name))
            } else {
                _events.emit(FolderEvent.Failed(folder.name))
            }
        }
    }

    private suspend fun relocate(
        account: Account,
        remote: MailRemote,
        folder: MailFolder,
        newServerId: String,
        newName: String,
        folders: List<MailFolder>,
        separator: Char,
        success: FolderEvent
    ) {
        val rewrites = FolderPaths.descendantsOf(folder.serverId, folders, separator)
            .map { it.serverId to FolderPaths.rewriteDescendant(it.serverId, folder.serverId, newServerId) }
        val result = runCatching { remote.renameFolder(folder.serverId, newServerId) }.getOrElse { Result.failure(it) }
        if (result.isSuccess) {
            mailRepository.applyFolderRelocation(account.id, folder.serverId, newServerId, newName, rewrites)
            _events.emit(success)
        } else {
            _events.emit(FolderEvent.Failed(folder.name))
        }
    }

    /**
     * The server's authoritative folder separator, falling back to inferring it
     * from the current folder list when the transport can't report one (Gmail/
     * POP3) or the lookup fails. Used to build rename/move target paths - a
     * guessed separator could otherwise mis-split a path whose leaf happens to
     * contain the inferred character.
     */
    private suspend fun separatorFor(remote: MailRemote, folders: List<MailFolder>): Char =
        remote.folderSeparator().getOrElse { FolderPaths.separatorOf(folders) }

    private suspend fun accountFor(folder: MailFolder): Account? =
        _selectedAccount.value?.takeIf { it.id == folder.accountId }
            ?: accountRepository.getAccountById(folder.accountId)
}
