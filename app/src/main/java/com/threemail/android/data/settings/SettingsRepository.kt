package com.threemail.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val signature: String = "",
    val syncIntervalMinutes: Long = 15,
    val notificationsEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private object Keys {
        val SIGNATURE = stringPreferencesKey("signature")
        val SYNC_INTERVAL = longPreferencesKey("sync_interval_minutes")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            signature = prefs[Keys.SIGNATURE] ?: "",
            syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL] ?: 15L,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true
        )
    }

    suspend fun setSignature(value: String) = dataStore.edit { it[Keys.SIGNATURE] = value }

    suspend fun setSyncInterval(minutes: Long) = dataStore.edit { it[Keys.SYNC_INTERVAL] = minutes }

    suspend fun setNotificationsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[Keys.THEME] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
}
