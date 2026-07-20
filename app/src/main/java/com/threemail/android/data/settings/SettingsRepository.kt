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
enum class SwipeAction { NONE, ARCHIVE, DELETE, TOGGLE_READ, MARK_SPAM }

/**
 * Vertical density of the message list. Three tiers so users with
 * shallow inboxes can pick a row size that fits their device:
 *
 *  - [COMFORTABLE]  legacy default. 12 dp vertical padding, 44 dp
 *     avatar. Designed for finger-tap accessibility over information
 *     density.
 *  - [COMPACT]      one notch tighter - 8 dp padding, 36 dp avatar.
 *     Picked-up historically because it shaves a row or two on
 *     mid-sized phones.
 *  - [EXTRA_COMPACT] the densest tier - 4 dp padding, 28 dp avatar.
 *     Shown as a third chip in Settings for users whose inboxes run
 *     deep or whose phones run small. Density and preview-line count
 *     stay orthogonal here: a user who wants "rows but no body
 *     preview" can still set `previewLines = 0` independently.
 */
enum class MessageDensity { COMFORTABLE, COMPACT, EXTRA_COMPACT }

data class AppSettings(
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
    val previewLines: Int = 2,
    /**
     * Whether to load remote images (and the pixel trackers behind them) in
     * every HTML email. Default false: the WebView is set up to block remote
     * images by default, and the Message Detail screen surfaces a per-message
     * "Show images" affordance so users can opt in on a single locked-down
     * message without flipping the global setting permanently.
     */
    val loadImages: Boolean = false
)

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private object Keys {
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
        val LOAD_IMAGES = booleanPreferencesKey("load_images")
    }

    val settings: Flow<AppSettings> = flow {
        dataStore.data.collect { prefs ->
            emit(
            AppSettings(
                syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL] ?: 15L,
                    notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
                    themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                    useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                    emptyTrashOnLaunch = prefs[Keys.EMPTY_TRASH_ON_LAUNCH] ?: false,
                    emptyTrashOnQuit = prefs[Keys.EMPTY_TRASH_ON_QUIT] ?: false,
                    pushEnabled = prefs[Keys.PUSH_ENABLED] ?: true,
                    // Legacy prefs holding the now-removed "TOGGLE_STAR"
                    // string coerce to ARCHIVE (right) / DELETE (left) -
                    // the safe defaults. Intentional one-way migration;
                    // do not surface this as a visible error.
                    swipeRightAction = prefs[Keys.SWIPE_RIGHT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.ARCHIVE,
                    swipeLeftAction = prefs[Keys.SWIPE_LEFT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.DELETE,
                    messageDensity = prefs[Keys.MESSAGE_DENSITY]?.let { runCatching { MessageDensity.valueOf(it) }.getOrNull() } ?: MessageDensity.COMFORTABLE,
                    previewLines = (prefs[Keys.PREVIEW_LINES] ?: 2).coerceIn(0, 3),
                    loadImages = prefs[Keys.LOAD_IMAGES] ?: false
                )
            )
        }
    }

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
    suspend fun setLoadImages(enabled: Boolean) = dataStore.edit { it[Keys.LOAD_IMAGES] = enabled }
}
