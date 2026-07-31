package com.threemail.android.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.MailMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mailRepository: MailRepository,
    private val accountRepository: AccountRepository,
    private val mailRemoteFactory: MailRemoteFactory
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<MailMessage> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val _query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            runCatching {
            _query
                .debounce(300)
                .flatMapLatest { query ->
                    if (query.length < 2) {
                        flow { emit(emptyList<MailMessage>()) }
                    } else {
                        flow {
                            emit(searchEverywhere(query))
                        }
                    }
                }
                .collect { results ->
                    _uiState.value = _uiState.value.copy(results = results, isLoading = false)
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load search")
            }
        }
    }

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query, isLoading = query.length >= 2, error = null)
        _query.value = query
    }

    /**
     * Local FTS remains the instant path, while each provider is queried for
     * older mail that has never been cached. Remote results are persisted when
     * they have a real folder id, making them openable and available to FTS on
     * the next search.
     */
    private suspend fun searchEverywhere(query: String): List<MailMessage> {
        val local = mailRepository.searchMessages(query).first()
        val remoteResults = mutableListOf<MailMessage>()
        val accounts = accountRepository.getAccountsOnce()
        for (account in accounts) {
            val folders = mailRepository.getFoldersOnce(account.id)
            val remote = mailRemoteFactory.create(account)
            remote.search(query, folders, limit = 100)
                .onSuccess { messages ->
                    val usable = messages.filter { it.folderId > 0L }
                    if (usable.isNotEmpty()) {
                        mailRepository.saveMessages(usable)
                        remoteResults += usable.mapNotNull { saved ->
                            // Remote providers do not know Room's auto-generated
                            // primary key, so every fresh result arrives with
                            // id == 0. Resolve by the stable provider identity
                            // after the upsert instead of asking Room for row 0.
                            mailRepository.getMessageByIdentity(
                                accountId = saved.accountId,
                                messageId = saved.messageId,
                                folderId = saved.folderId
                            )
                        }
                    }
                }
                .onFailure { error ->
                    // Local results remain useful when an account is offline;
                    // surface an error only if nothing else can be shown.
                    if (local.isEmpty() && remoteResults.isEmpty()) {
                        _uiState.value = _uiState.value.copy(error = error.message)
                    }
                }
        }
        return (local + remoteResults)
            .distinctBy { "${it.accountId}:${it.messageId}:${it.folderId}" }
            .sortedByDescending { it.date }
    }
}
