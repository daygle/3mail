package com.threemail.android.ui.screens.message

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.MailMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    private val mailRepository: MailRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val message: MailMessage? = null,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        val messageId = savedStateHandle.get<Long>("messageId") ?: 0L
        loadMessage(messageId)
    }

    private fun loadMessage(messageId: Long) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val message = mailRepository.getMessageById(messageId)
                _uiState.value = UiState(message = message, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = UiState(error = e.message, isLoading = false)
            }
        }
    }

    fun toggleStar() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailRepository.updateStarred(message.id, !message.isStarred)
            _uiState.value = _uiState.value.copy(message = message.copy(isStarred = !message.isStarred))
        }
    }
}
