package com.threemail.android.ui.screens.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.crypto.OpenPgpController
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.Identity
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.push.PushController
import com.threemail.android.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backs the per-account settings screen. Loads the target account once into
 * local state (rather than observing the accounts flow) so editing a text
 * field can't fight a re-emission that would reset the cursor. Each edit is
 * written straight through to the account row via targeted DAO updaters, and
 * any change that affects the mail-check cadence re-schedules that account's
 * dedicated periodic sync.
 */
@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val pushController: PushController,
    private val openPgpController: OpenPgpController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val account: Account? = null,
        /** Global default cadence, shown as the meaning of the "Default" chip. */
        val defaultIntervalMinutes: Long = 15L,
        val isLoading: Boolean = true,
        val notFound: Boolean = false,
        /** Cached once at load so the folder-role picker doesn't churn on sync. */
        val folders: List<MailFolder> = emptyList(),
        /** Formatted fingerprint of this account's own OpenPGP master key. */
        val ownKeyFingerprint: String? = null,
        /** Cached peer keys: email -> formatted fingerprint (null = unparseable). */
        val peerKeys: Map<String, String?> = emptyMap(),
        /** Transient outcome of the last import/remove action, for inline display. */
        val keyActionMessage: KeyActionMessage? = null,
        /** Staged WKD export payload; the screen writes it via SAF then clears. */
        val wkdExport: OpenPgpController.WkdExport? = null
    )

    /** Import/remove outcome routed to a string resource by the screen. */
    sealed interface KeyActionMessage {
        data class Imported(val fingerprint: String) : KeyActionMessage
        data class Failed(val reason: String) : KeyActionMessage
    }

    private val accountId: Long = savedStateHandle.get<Long>("accountId") ?: -1L

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        viewModelScope.launch {
            val account = accountRepository.getAccountById(accountId)
            val default = settingsRepository.settings.first().syncIntervalMinutes
            val folders = if (account != null) {
                mailRepository.getFoldersOnce(accountId)
            } else emptyList()
            _uiState.value = UiState(
                account = account,
                defaultIntervalMinutes = default,
                isLoading = false,
                notFound = account == null,
                folders = folders
            )
            if (account != null) refreshPgpState(account)
        }
    }

    /**
     * (Re)load the OpenPGP slice of the state: the account's own key
     * fingerprint (generating the keyring on first visit - deliberate, so
     * the fingerprint the user shares matches the key mail will use) and
     * the cached peer keys. Runs on IO because the own-key path reads or
     * creates the on-disk keyring.
     */
    private suspend fun refreshPgpState(account: Account) {
        val fingerprint = withContext(Dispatchers.IO) {
            openPgpController.ownKeyFingerprint(account.id, account.email)
        }
        val peers = withContext(Dispatchers.IO) {
            openPgpController.peerKeyFingerprints(account.id)
        }
        _uiState.value = _uiState.value.copy(ownKeyFingerprint = fingerprint, peerKeys = peers)
    }

    /** Validate + store a manually imported peer key, then refresh the list. */
    fun importPeerKey(email: String, keyData: String) {
        val account = _uiState.value.account ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                openPgpController.importPeerKey(account.id, email, keyData)
            }
            _uiState.value = _uiState.value.copy(
                keyActionMessage = result.fold(
                    onSuccess = { KeyActionMessage.Imported(it) },
                    onFailure = { KeyActionMessage.Failed(it.message ?: "Invalid key data") }
                )
            )
            refreshPgpState(account)
        }
    }

    /** Drop a cached peer key and refresh the list. */
    fun removePeerKey(email: String) {
        val account = _uiState.value.account ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { openPgpController.removePeerKey(account.id, email) }
            refreshPgpState(account)
        }
    }

    fun clearKeyActionMessage() {
        _uiState.value = _uiState.value.copy(keyActionMessage = null)
    }

    /** Stage the WKD export payload; the screen reacts by launching SAF. */
    fun prepareWkdExport() {
        val account = _uiState.value.account ?: return
        viewModelScope.launch {
            val export = withContext(Dispatchers.IO) {
                openPgpController.wkdExport(account.id, account.email)
            }
            _uiState.value = _uiState.value.copy(wkdExport = export)
        }
    }

    fun clearWkdExport() {
        _uiState.value = _uiState.value.copy(wkdExport = null)
    }

    private fun updateAccount(transform: (Account) -> Account) {
        val current = _uiState.value.account ?: return
        _uiState.value = _uiState.value.copy(account = transform(current))
    }

    fun setDisplayName(value: String) {
        updateAccount { it.copy(displayName = value) }
        viewModelScope.launch { accountRepository.setDisplayName(accountId, value) }
    }

    fun setSignature(value: String) {
        updateAccount { it.copy(signature = value) }
        viewModelScope.launch { accountRepository.setSignature(accountId, value) }
    }

    fun setSyncIntervalMinutes(minutes: Long) {
        updateAccount { it.copy(syncIntervalMinutes = minutes) }
        viewModelScope.launch {
            accountRepository.setSyncIntervalMinutes(accountId, minutes)
            rescheduleSync()
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        updateAccount { it.copy(syncEnabled = enabled) }
        viewModelScope.launch {
            accountRepository.setSyncEnabled(accountId, enabled)
            rescheduleSync()
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        updateAccount { it.copy(notificationsEnabled = enabled) }
        viewModelScope.launch { accountRepository.setNotificationsEnabled(accountId, enabled) }
    }

    fun setCalendarSyncEnabled(enabled: Boolean) {
        updateAccount { it.copy(calendarSyncEnabled = enabled) }
        viewModelScope.launch { accountRepository.setCalendarSyncEnabled(accountId, enabled) }
    }

    fun addIdentity(identity: Identity) {
        val current = _uiState.value.account ?: return
        val updated = current.identities + identity
        updateAccount { it.copy(identities = updated) }
        viewModelScope.launch { accountRepository.setIdentities(accountId, updated) }
    }

    fun removeIdentity(index: Int) {
        val current = _uiState.value.account ?: return
        if (index !in current.identities.indices) return
        val updated = current.identities.toMutableList().apply { removeAt(index) }
        updateAccount { it.copy(identities = updated) }
        viewModelScope.launch { accountRepository.setIdentities(accountId, updated) }
    }

    /**
     * Updates (or clears) the per-account folder-role override for [role].
     * Passing `serverId = null` removes the override and reverts to the
     * name-matching heuristic in ImapClient for that role.
     *
     * Persists the change via [AccountRepository.setFolderRoles] (which writes
     * the JSON column); the local folder table stays untouched, so the next
     * sync will surface the change. We also re-emit the local UiState so the
     * role row's subtitle updates immediately.
     */
    fun setFolderRole(role: FolderType, serverId: String?) {
        val current = _uiState.value.account ?: return
        val next = if (serverId.isNullOrBlank()) current.folderRoles - role
                   else current.folderRoles + (role to serverId)
        updateAccount { it.copy(folderRoles = next) }
        viewModelScope.launch { accountRepository.setFolderRoles(accountId, next) }
    }

    fun setPushEnabled(enabled: Boolean) {
        updateAccount { it.copy(pushEnabled = enabled) }
        viewModelScope.launch {
            accountRepository.setPushEnabled(accountId, enabled)
            if (enabled) pushController.enablePushFor(accountId)
            else pushController.disablePushFor(accountId)
        }
    }

    /**
     * Toggle an extra push folder (by IMAP `serverId`) on or off. INBOX is
     * always watched and is never part of this set. After persisting, refresh
     * this account's push so the IDLE service opens/closes the matching
     * connection without waiting for the next app foreground.
     */
    fun togglePushFolder(serverId: String, enabled: Boolean) {
        val current = _uiState.value.account ?: return
        val next = if (enabled) {
            (current.pushFolders + serverId).distinct()
        } else {
            current.pushFolders - serverId
        }
        updateAccount { it.copy(pushFolders = next) }
        viewModelScope.launch {
            accountRepository.setPushFolders(accountId, next)
            pushController.enablePushFor(accountId)
        }
    }

    /**
     * Re-derives this account's periodic sync from its current (enabled,
     * interval) state and the global default. Cancels the worker when sync is
     * paused so a disabled account stops waking WorkManager.
     */
    private suspend fun rescheduleSync() {
        val account = _uiState.value.account ?: return
        if (!account.syncEnabled) {
            syncScheduler.cancelPeriodicSyncForAccount(accountId)
            return
        }
        val effective = account.syncIntervalMinutes.takeIf { it > 0 }
            ?: _uiState.value.defaultIntervalMinutes
        syncScheduler.schedulePeriodicSyncForAccount(accountId, effective)
    }
}
