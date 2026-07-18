package com.threemail.android.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.MailFolder
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

/**
 * Backs the folder-visibility management screen: lists every folder of the
 * selected account with a show/hide toggle. Hiding a folder keeps it in the
 * database (and syncing) but removes it from the navigation drawer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FolderManagementViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository
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
        viewModelScope.launch { mailRepository.setFolderHidden(folder.id, hidden) }
    }
}
