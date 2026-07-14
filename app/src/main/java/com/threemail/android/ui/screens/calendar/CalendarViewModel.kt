package com.threemail.android.ui.screens.calendar

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.calendar.CalendarApiClient
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.CalendarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val calendarApiClient: CalendarApiClient
) : ViewModel() {

    data class UiState(
        val accountEmail: String? = null,
        val events: List<CalendarEvent> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    init {
        load()
    }

    fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null, recoverableAuthIntent = null)
        viewModelScope.launch {
            try {
                val account = accountRepository.getAccounts().first()
                    .firstOrNull { it.accountType == AccountType.GMAIL }
                if (account == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, accountEmail = null)
                    return@launch
                }
                val now = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val start = now.timeInMillis
                val end = start + 60L * 24 * 60 * 60 * 1000 // next 60 days
                calendarApiClient.listEvents(account.email, start, end)
                    .onSuccess { events ->
                        _uiState.value = _uiState.value.copy(
                            accountEmail = account.email,
                            events = events.sortedBy { it.start },
                            isLoading = false
                        )
                    }
                    .onFailure { _uiState.value = _uiState.value.copy(isLoading = false, error = it.message) }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(isLoading = false, recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun createEvent(title: String, startMs: Long, endMs: Long, allDay: Boolean) {
        val email = _uiState.value.accountEmail ?: return
        viewModelScope.launch {
            try {
                val event = CalendarEvent(id = "", title = title, start = startMs, end = endMs, allDay = allDay)
                calendarApiClient.createEvent(email, event)
                    .onSuccess { load() }
                    .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun deleteEvent(eventId: String) {
        val email = _uiState.value.accountEmail ?: return
        viewModelScope.launch {
            calendarApiClient.deleteEvent(email, eventId).onSuccess { load() }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message) }
        }
    }

    fun onRecoverableAuthHandled() {
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
    }

    fun retryAfterRecoverableAuth() = load()
}
