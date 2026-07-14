package com.threemail.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.settings.AppSettings
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.data.settings.ThemeMode
import com.threemail.android.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val mailActions: MailActions
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    fun clearStatus() { _statusMessage.value = null }

    fun setSignature(value: String) {
        viewModelScope.launch { settingsRepository.setSignature(value) }
    }

    fun setSyncInterval(minutes: Long) {
        viewModelScope.launch {
            settingsRepository.setSyncInterval(minutes)
            syncScheduler.schedulePeriodicSync(minutes, replace = true)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun setEmptyTrashOnExit(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEmptyTrashOnExit(enabled) }
    }

    fun emptyTrashNow() {
        viewModelScope.launch {
            _statusMessage.value = "Emptying trash…"
            mailActions.emptyTrash()
                .onSuccess { _statusMessage.value = "Trash emptied" }
                .onFailure { _statusMessage.value = "Couldn't empty trash: ${it.message}" }
        }
    }
}
