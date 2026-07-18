package com.threemail.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Action performed when a message row is swiped. */
enum class SwipeAction { NONE, ARCHIVE, DELETE, TOGGLE_READ, TOGGLE_STAR }

/** Vertical density of the message list. */
enum class MessageDensity { COMFORTABLE, COMPACT }

data class AppSettings(
    val signature: String = "",
    val syncIntervalMinutes: Long = 15,
    val notificationsEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val emptyTrashOnLaunch: Boolean = false,
    val emptyTrashOnQuit: Boolean = false,
    val pushEnabled: Boolean = true,
    /** Swipe start-to-end (left-to-right) action. */
    val swipeRightAction: SwipeAction = SwipeAction.ARCHIVE,
    /** Swipe end-to-start (right-to-left) action. */
    val swipeLeftAction: SwipeAction = SwipeAction.DELETE,
    val messageDensity: MessageDensity = MessageDensity.COMFORTABLE,
    /** Body-preview lines shown per row (0 hides the preview). */
    val previewLines: Int = 2
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
        val EMPTY_TRASH_ON_LAUNCH = booleanPreferencesKey("empty_trash_on_launch")
        val EMPTY_TRASH_ON_QUIT = booleanPreferencesKey("empty_trash_on_quit")
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        val SWIPE_RIGHT = stringPreferencesKey("swipe_right_action")
        val SWIPE_LEFT = stringPreferencesKey("swipe_left_action")
        val MESSAGE_DENSITY = stringPreferencesKey("message_density")
        val PREVIEW_LINES = intPreferencesKey("preview_lines")
    }

    val settings: Flow<AppSettings> = flow {
        dataStore.data.collect { prefs ->
            emit(
                AppSettings(
                    signature = prefs[Keys.SIGNATURE] ?: "",
                    syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL] ?: 15L,
                    notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
                    themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                    useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                    emptyTrashOnLaunch = prefs[Keys.EMPTY_TRASH_ON_LAUNCH] ?: false,
                    emptyTrashOnQuit = prefs[Keys.EMPTY_TRASH_ON_QUIT] ?: false,
                    pushEnabled = prefs[Keys.PUSH_ENABLED] ?: true,
                    swipeRightAction = prefs[Keys.SWIPE_RIGHT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.ARCHIVE,
                    swipeLeftAction = prefs[Keys.SWIPE_LEFT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.DELETE,
                    messageDensity = prefs[Keys.MESSAGE_DENSITY]?.let { runCatching { MessageDensity.valueOf(it) }.getOrNull() } ?: MessageDensity.COMFORTABLE,
                    previewLines = (prefs[Keys.PREVIEW_LINES] ?: 2).coerceIn(0, 3)
                )
            )
        }
    }

    suspend fun setSignature(value: String) = dataStore.edit { it[Keys.SIGNATURE] = value }
    suspend fun setSyncInterval(minutes: Long) = dataStore.edit { it[Keys.SYNC_INTERVAL] = minutes }
    suspend fun setNotificationsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setEmptyTrashOnLaunch(enabled: Boolean) = dataStore.edit { it[Keys.EMPTY_TRASH_ON_LAUNCH] = enabled }
    suspend fun setEmptyTrashOnQuit(enabled: Boolean) = dataStore.edit { it[Keys.EMPTY_TRASH_ON_QUIT] = enabled }
    suspend fun setPushEnabled(enabled: Boolean) = dataStore.edit { it[Keys.PUSH_ENABLED] = enabled }
    suspend fun setSwipeRightAction(action: SwipeAction) = dataStore.edit { it[Keys.SWIPE_RIGHT] = action.name }
    suspend fun setSwipeLeftAction(action: SwipeAction) = dataStore.edit { it[Keys.SWIPE_LEFT] = action.name }
    suspend fun setMessageDensity(density: MessageDensity) = dataStore.edit { it[Keys.MESSAGE_DENSITY] = density.name }
    suspend fun setPreviewLines(lines: Int) = dataStore.edit { it[Keys.PREVIEW_LINES] = lines.coerceIn(0, 3) }
}
