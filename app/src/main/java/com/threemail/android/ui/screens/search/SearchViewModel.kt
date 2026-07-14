package com.threemail.android.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.MailMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val mailRepository: MailRepository
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<MailMessage> = emptyList(),
        val isLoading: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val _query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _query
                .debounce(300)
                .flatMapLatest { query ->
                    if (query.length < 2) {
                        flow { emit(emptyList<MailMessage>()) }
                    } else {
                        mailRepository.searchMessages(query)
                    }
                }
                .collect { results ->
                    _uiState.value = _uiState.value.copy(results = results, isLoading = false)
                }
        }
    }

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query, isLoading = query.length >= 2)
        _query.value = query
    }
}
