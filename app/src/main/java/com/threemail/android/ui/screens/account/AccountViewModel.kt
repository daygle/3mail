package com.threemail.android.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.push.PushController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val pushController: PushController
) : ViewModel() {

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val isLoading: Boolean = false,
        val pendingRemoval: Account? = null,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        viewModelScope.launch {
            runCatching {
            accountRepository.getAccounts().collect { accounts ->
                _uiState.value = _uiState.value.copy(
                    accounts = accounts,
                    isLoading = false,
                    pendingRemoval = _uiState.value.pendingRemoval?.takeIf { pending ->
                        accounts.any { it.id == pending.id }
                    }
                )
            }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load accounts")
            }
        }
    }

    fun requestRemove(account: Account) {
        _uiState.value = _uiState.value.copy(pendingRemoval = account)
    }

    fun cancelRemove() {
        _uiState.value = _uiState.value.copy(pendingRemoval = null)
    }

    fun confirmRemove() {
        val account = _uiState.value.pendingRemoval ?: return
        _uiState.value = _uiState.value.copy(pendingRemoval = null)
        viewModelScope.launch {
            accountRepository.deleteAccount(account)
        }
    }

    /**
     * Persists the per-account IDLE push flag and immediately tells the
     * foreground service to start or stop that account's subscription. The
     * toggle is intentionally fire-and-forget at the UI layer: the service is
     * the authority on what's currently being pushed, and [ImapIdleService]'s
     * next refresh will reconcile anything we miss.
     */
    fun setPushEnabled(accountId: Long, enabled: Boolean) {
        viewModelScope.launch {
            accountRepository.setPushEnabled(accountId, enabled)
            if (enabled) pushController.enablePushFor(accountId)
            else pushController.disablePushFor(accountId)
        }
    }
}
