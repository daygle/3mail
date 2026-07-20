package com.threemail.android.ui.screens.inbox

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.repository.UndoController
import com.threemail.android.data.settings.MessageDensity
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.data.settings.SwipeAction
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val mailRemoteFactory: MailRemoteFactory,
    private val settingsRepository: SettingsRepository,
    private val undoController: UndoController
) : ViewModel() {

    /** Pending undoable action (drives the inbox undo snackbar). */
    val undoPending = undoController.pending

    fun undo() = undoController.undo()

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val selectedAccount: Account? = null,
        val folders: List<MailFolder> = emptyList(),
        val selectedFolder: MailFolder? = null,
        val messages: List<MailMessage> = emptyList(),
        val isSyncing: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null,
        /** True when the cross-account unified inbox is shown instead of a single folder. */
        val unifiedInbox: Boolean = false,
        /** Ids of messages currently selected for a batch action. Empty => not in selection mode. */
        val selectedIds: Set<Long> = emptySet(),
        // Display preferences (mirrored from SettingsRepository so the row layout
        // and swipe gestures react to changes without re-navigating).
        val swipeRightAction: SwipeAction = SwipeAction.ARCHIVE,
        val swipeLeftAction: SwipeAction = SwipeAction.DELETE,
        val messageDensity: MessageDensity = MessageDensity.COMFORTABLE,
        val previewLines: Int = 2
    ) {
        val selectionMode: Boolean get() = selectedIds.isNotEmpty()
    }

    private data class Transient(
        val isSyncing: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null
    )

    private val _selectedAccount = MutableStateFlow<Account?>(null)
    private val _selectedFolder = MutableStateFlow<MailFolder?>(null)
    private val _unifiedMode = MutableStateFlow(false)
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _transient = MutableStateFlow(Transient())

    private val accountsFlow = accountRepository.getAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val foldersFlow = _selectedAccount
        .filterNotNull()
        .flatMapLatest { account -> mailRepository.getFolders(account.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Reactive message feed: switches between the cross-account unified inbox
    // and a single folder. Room-backed, so sync results and read/star/delete
    // mutations reflect live without a manual re-query.
    private val messagesFlow = combine(_unifiedMode, _selectedFolder) { unified, folder ->
        unified to folder
    }.flatMapLatest { (unified, folder) ->
        when {
            unified -> mailRepository.observeUnifiedInbox(DEFAULT_PAGE_SIZE)
            folder != null -> mailRepository.observeFolder(folder.id, DEFAULT_PAGE_SIZE)
            else -> flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val baseState: StateFlow<UiState> = combine(
        accountsFlow,
        foldersFlow,
        messagesFlow,
        _selectedAccount,
        _selectedFolder
    ) { accounts, folders, messages, selectedAccount, selectedFolder ->
        val account = selectedAccount ?: accounts.firstOrNull()
        val folder = selectedFolder
            ?: folders.firstOrNull { it.type == FolderType.Inbox }
            ?: folders.firstOrNull()
        UiState(
            accounts = accounts,
            selectedAccount = account,
            folders = folders,
            selectedFolder = folder,
            messages = messages
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    private val settingsFlow = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.threemail.android.data.settings.AppSettings())

    val uiState: StateFlow<UiState> = combine(
        baseState,
        _transient,
        _selectedIds,
        _unifiedMode,
        settingsFlow
    ) { base, transient, selectedIds, unified, settings ->
        base.copy(
            isSyncing = transient.isSyncing,
            error = transient.error,
            recoverableAuthIntent = transient.recoverableAuthIntent,
            unifiedInbox = unified,
            selectedFolder = if (unified) null else base.selectedFolder,
            // Drop ids that scrolled out of the current feed so the selection
            // count never counts phantom rows.
            selectedIds = selectedIds intersect base.messages.mapTo(HashSet()) { it.id },
            swipeRightAction = settings.swipeRightAction,
            swipeLeftAction = settings.swipeLeftAction,
            messageDensity = settings.messageDensity,
            previewLines = settings.previewLines
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    init {
        viewModelScope.launch {
            runCatching {
            accountsFlow.collect { accounts ->
                if (_selectedAccount.value == null && accounts.isNotEmpty()) {
                    _selectedAccount.value = accounts.first()
                }
            }
            }.onFailure { e ->
                _transient.value = _transient.value.copy(error = e.message ?: "Failed to load inbox")
            }
        }
        viewModelScope.launch {
            runCatching {
            foldersFlow.collect { folders ->
                if (_selectedFolder.value == null && folders.isNotEmpty()) {
                    _selectedFolder.value = folders.firstOrNull { it.type == FolderType.Inbox } ?: folders.first()
                }
            }
            }.onFailure { e ->
                _transient.value = _transient.value.copy(error = e.message ?: "Failed to load inbox")
            }
        }
        // Auto-sync whenever the active view changes - either the selected
        // folder (single-folder view) or the unified-inbox toggle.
        //
        // Why this is needed: `selectFolder()` / `selectAccount()` /
        // `selectUnifiedInbox()` only update the reactive selectors; the
        // `messagesFlow` then re-subscribes to the matching Room observer,
        // and a Room observer does NOT trigger a remote fetch - it just
        // re-emits whatever is already in the local cache. Remote fetches
        // happen via `MailSyncWorker` (limited to INBOX/SENT/DRAFTS via
        // `SYNCED_FOLDERS`) or via this VM's `sync()`. Without this
        // collector, tapping any folder the worker hasn't fetched yet -
        // custom folders, sub-folders, the unified cross-account inbox,
        // or a freshly-added account's INBOX - shows an empty list until
        // the user hits Refresh by hand.
        //
        // The (unified, folder) pair reactively mirrors the UI:
        //   1. Initial bootstrap - sets the inbox and we sync it immediately.
        //   2. `selectFolder(X)` - pair changes from (false, inbox) to
        //      (false, X) and we fetch X.
        //   3. `selectUnifiedInbox()` - pair changes to (true, inbox) and
        //      we fetch every account's INBOX (the unified view's data).
        //   4. `selectAccount(other)` - re-nulls the selector, the
        //      bootstrap picks the new account's INBOX, and we sync it
        //      as part of the same transition.
        //
        // `distinctUntilChanged` dedupes re-selects of the same pair; tapping
        // the same row twice does not spam the server. `filterNotNull` skips
        // the transient null state `selectAccount` leaves behind before the
        // bootstrap re-picks. `collectLatest` cancels the previous sync
        // when a new target is selected before it finishes, so rapid
        // A→B→C taps do not fan out into three parallel network calls -
        // they mirror the `flatMapLatest` idiom already used by
        // `messagesFlow` and `foldersFlow` above.
        //
        // `MutableStateFlow` already conflates same-value writes
        // (re-selecting the same folder is a no-op on the wire), and
        // `distinctUntilChanged` is the safety net above that for the
        // synthesized (unified, folder) pair emissions.
        //
        // No `runCatching { }.onFailure { /* surface */ }` wrapper here,
        // unlike the two sibling bootstrap collectors above: `sync()` itself
        // routes its remote failures through `_transient.error` and
        // `recoverableAuthIntent`, and the only thing that could throw in
        // this collector is the `combine` plumbing itself, which should
        // crash (a broken contract is a bug, not a recoverable user error).
        //
        // `combine` only emits once BOTH legs have produced a value. On a
        // brand-new install that flips `_unifiedMode` to true before the
        // bootstrap picks an inbox (so `_selectedFolder.filterNotNull()` has
        // never emitted), the auto-sync intentionally sits idle until the
        // bootstrap delivers the first non-null folder - that is the right
        // behaviour, not a stuck state.
        viewModelScope.launch {
            combine(
                _unifiedMode,
                _selectedFolder.filterNotNull().distinctUntilChanged()
            ) { unified, folder -> unified to folder }
                .distinctUntilChanged()
                .collectLatest { sync() }
        }
    }

    fun selectAccount(account: Account) {
        _unifiedMode.value = false
        _selectedIds.value = emptySet()
        _selectedAccount.value = account
        _selectedFolder.value = null
    }

    fun selectFolder(folder: MailFolder) {
        _unifiedMode.value = false
        _selectedIds.value = emptySet()
        _selectedFolder.value = folder
    }

    /** Switch to the cross-account unified inbox view. */
    fun selectUnifiedInbox() {
        _selectedIds.value = emptySet()
        _unifiedMode.value = true
    }

    fun onRecoverableAuthHandled() {
        _transient.value = _transient.value.copy(recoverableAuthIntent = null)
    }

    fun dismissError() {
        _transient.value = _transient.value.copy(error = null)
    }

    fun sync() {
        _transient.value = _transient.value.copy(isSyncing = true, error = null, recoverableAuthIntent = null)
        viewModelScope.launch {
            try {
                if (_unifiedMode.value) {
                    syncAllInboxes()
                } else {
                    syncSelectedFolder()
                }
                _transient.value = _transient.value.copy(isSyncing = false)
            } catch (e: RecoverableAuthException) {
                _transient.value = _transient.value.copy(isSyncing = false, recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _transient.value = _transient.value.copy(isSyncing = false, error = e.message)
            }
        }
    }

    private suspend fun syncSelectedFolder() {
        val account = _selectedAccount.value ?: return
        val folder = _selectedFolder.value ?: return
        val remote = mailRemoteFactory.create(account)
        val fetch = remote.fetchMessages(folder, folder.syncVersion, limit = 100).getOrThrow()
        if (fetch.messages.isNotEmpty()) {
            mailRepository.saveMessages(fetch.messages.map { it.copy(folderId = folder.id) })
        }
        mailRepository.updateFolderCursor(folder.id, fetch.nextCursor)
    }

    /**
     * Sync the INBOX of every account for the unified view. One account's
     * failure (offline, auth) is isolated so the rest still refresh; a
     * recoverable-auth error is rethrown so the UI can launch the consent flow.
     */
    private suspend fun syncAllInboxes() {
        val accounts = accountsFlow.value
        for (account in accounts) {
            val folders = mailRepository.getFoldersOnce(account.id)
            val inbox = folders.firstOrNull { it.type == FolderType.Inbox } ?: continue
            try {
                val remote = mailRemoteFactory.create(account)
                val fetch = remote.fetchMessages(inbox, inbox.syncVersion, limit = 100).getOrThrow()
                if (fetch.messages.isNotEmpty()) {
                    mailRepository.saveMessages(fetch.messages.map { it.copy(folderId = inbox.id) })
                }
                mailRepository.updateFolderCursor(inbox.id, fetch.nextCursor)
            } catch (e: RecoverableAuthException) {
                throw e
            } catch (_: Exception) {
                // isolate this account's failure; other inboxes still sync
            }
        }
    }

    fun retryAfterRecoverableAuth() {
        sync()
    }

    // ── Selection (multi-select triage) ──

    fun toggleSelection(message: MailMessage) {
        val current = _selectedIds.value
        _selectedIds.value = if (message.id in current) current - message.id else current + message.id
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        _selectedIds.value = messagesFlow.value.mapTo(HashSet()) { it.id }
    }

    private fun selectedMessages(): List<MailMessage> {
        val ids = _selectedIds.value
        return messagesFlow.value.filter { it.id in ids }
    }

    fun archiveSelected() {
        val batch = selectedMessages()
        _selectedIds.value = emptySet()
        viewModelScope.launch { mailActions.archiveBatch(batch) }
    }

    fun deleteSelected() {
        val batch = selectedMessages()
        _selectedIds.value = emptySet()
        viewModelScope.launch { mailActions.deleteBatch(batch) }
    }

    fun markSelectedRead(isRead: Boolean) {
        val batch = selectedMessages()
        _selectedIds.value = emptySet()
        viewModelScope.launch { mailActions.setReadBatch(batch, isRead) }
    }

    /**
     * Bulk-mark every selected message as spam. Routes through
     * [MailActions.markSpamBatch] which silently skips messages whose
     * account has no Spam/Junk folder (mirroring how archiveBatch /
     * deleteBatch tolerate partial failures).
     */
    fun markSpamSelected() {
        val batch = selectedMessages()
        _selectedIds.value = emptySet()
        viewModelScope.launch { mailActions.markSpamBatch(batch) }
    }

    /** Mark every message in the current feed as read (server + local). */
    fun markAllRead() {
        val batch = messagesFlow.value.filterNot { it.isRead }
        viewModelScope.launch { mailActions.setReadBatch(batch, true) }
    }

    fun markAsRead(message: MailMessage, isRead: Boolean) {
        viewModelScope.launch { mailActions.setRead(message, isRead) }
    }

    fun delete(message: MailMessage) {
        viewModelScope.launch { mailActions.deleteWithUndo(message) }
    }

    fun archive(message: MailMessage) {
        viewModelScope.launch { mailActions.archiveWithUndo(message) }
    }

    fun markSpam(message: MailMessage) {
        viewModelScope.launch { mailActions.markSpamWithUndo(message) }
    }

    /** Event emitted after an Empty Trash attempt. Collected by the
     * composable to show a snackbar with the result.
     */
    sealed interface EmptyTrashEvent {
        data class Success(val expungedCount: Int) : EmptyTrashEvent
        /** Failure carries no user-facing text — the Screen uses the
         * localized `empty_trash_failure` resource for all failures. */
        data object Failure : EmptyTrashEvent
    }

    private val _emptyTrashEvents = MutableSharedFlow<EmptyTrashEvent>(extraBufferCapacity = 1)
    val emptyTrashEvents: SharedFlow<EmptyTrashEvent> = _emptyTrashEvents

    /**
     * Permanently delete every message in the currently-selected account's
     * Trash folder. Server-first: marks each message DELETED on the remote
     * then expunges the folder; only on server success do we drop the local
     * cache for that folder. A confirmation dialog guards the call site.
     *
     * Emits [EmptyTrashEvent.Success] or [EmptyTrashEvent.Failure] on the
     * [emptyTrashEvents] flow so the UI can surface a snackbar.
     */
    fun emptyTrash() {
        viewModelScope.launch {
            val account = _selectedAccount.value ?: run {
                _emptyTrashEvents.tryEmit(EmptyTrashEvent.Failure)
                return@launch
            }
            val folders = mailRepository.getFoldersOnce(account.id)
            val trash = folders.firstOrNull { it.type == FolderType.TRASH } ?: run {
                _emptyTrashEvents.tryEmit(EmptyTrashEvent.Failure)
                return@launch
            }
            val result = mailActions.emptyTrash(account, trash)
            result
                .onSuccess { count -> _emptyTrashEvents.tryEmit(EmptyTrashEvent.Success(count)) }
                .onFailure { _emptyTrashEvents.tryEmit(EmptyTrashEvent.Failure) }
        }
    }

    /**
     * Toggle the favorite flag for a folder. Writes through the repository so
     * the joined `folder + folder_favorites` flow re-emits and the drawer's
     * favorites section + star icons update without a server round-trip.
     */
    fun toggleFavorite(folder: MailFolder) {
        viewModelScope.launch {
            mailRepository.setFolderFavorite(
                accountId = folder.accountId,
                serverId = folder.serverId,
                isFavorite = !folder.isFavorite
            )
        }
    }

    /**
     * Persist a drag-reorder of the drawer's pinned favorites. Called once
     * per drag release with the new serverId ordering. The DAO wraps the
     * per-row position UPDATEs in a single Room Transaction so a partial
     * reorder (mid-loop cancellation, crash) rolls back instead of leaving
     * the user's pinned list half-renumbered.
     */
    fun reorderFavorites(accountId: Long, serverIds: List<String>) {
        viewModelScope.launch {
            mailRepository.reorderFavorites(accountId, serverIds)
        }
    }

    companion object {
        /**
         * Page size for the folder / unified feed. Bounds the reactive query so
         * the inbox doesn't stream every message in a giant folder into Compose.
         */
        private const val DEFAULT_PAGE_SIZE = 50
    }
}
