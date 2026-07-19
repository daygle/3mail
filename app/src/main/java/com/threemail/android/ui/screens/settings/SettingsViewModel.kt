package com.threemail.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.settings.AppSettings
import com.threemail.android.data.settings.MessageDensity
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.data.settings.SwipeAction
import com.threemail.android.data.settings.ThemeMode
import com.threemail.android.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setSyncInterval(minutes: Long) {
        viewModelScope.launch {
            settingsRepository.setSyncInterval(minutes)
            // The global interval is the default cadence; reschedule every
            // account so those without a per-account override pick up the new
            // value (accounts with an override keep it).
            syncScheduler.reconcileAccountSyncs(accountRepository.getAccountsOnce(), minutes)
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

    fun setEmptyTrashOnLaunch(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEmptyTrashOnLaunch(enabled) }
    }

    fun setEmptyTrashOnQuit(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEmptyTrashOnQuit(enabled) }
    }

    fun setSwipeRightAction(action: SwipeAction) {
        viewModelScope.launch { settingsRepository.setSwipeRightAction(action) }
    }

    fun setSwipeLeftAction(action: SwipeAction) {
        viewModelScope.launch { settingsRepository.setSwipeLeftAction(action) }
    }

    fun setMessageDensity(density: MessageDensity) {
        viewModelScope.launch { settingsRepository.setMessageDensity(density) }
    }

    fun setPreviewLines(lines: Int) {
        viewModelScope.launch { settingsRepository.setPreviewLines(lines) }
    }
}
