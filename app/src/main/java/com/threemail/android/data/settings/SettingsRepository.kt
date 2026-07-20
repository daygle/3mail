package com.threemail.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Action performed when a message row is swiped. */
enum class SwipeAction { NONE, ARCHIVE, DELETE, TOGGLE_READ, MARK_SPAM }

/**
 * Where to navigate from the message-detail screen after the user deletes
 * the currently-open message. Modelled after the Outlook / Gmail choice
 * between "leave the screen" and "advance to the next message in the same
 * folder". Defaults to [RETURN_TO_LIST] for users who want a quiet exit
 * path; the more aggressive [NEXT_MESSAGE] mirrors the "swipe delete"
 * rhythm on desktop mail clients.
 */
enum class AfterDeleteNavigation { RETURN_TO_LIST, NEXT_MESSAGE }

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
    val loadImages: Boolean = false,
    /**
     * Set of [TopBarItemId] values the user has explicitly hidden from the
     * top app bar on the supported screens (Inbox, Message Detail, Compose).
     * Each screen reads only the values that apply to it; others are ignored.
     * Default empty: every action shows in the bar, which matches the
     * pre-feature UX so existing users see no behaviour change on first
     * launch. Set membership is stored as a string set in DataStore under
     * [Keys.HIDDEN_TOP_BAR_ITEMS] - the enum names are the on-disk key.
     */
    val hiddenTopBarItems: Set<TopBarItemId> = emptySet(),
    /**
     * Behaviour after the user deletes the message currently open in
     * message-detail. Defaults to [AfterDeleteNavigation.RETURN_TO_LIST] so
     * existing users see the same pop-back behaviour on first launch.
     */
    val afterDeleteNavigation: AfterDeleteNavigation = AfterDeleteNavigation.RETURN_TO_LIST
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
        val HIDDEN_TOP_BAR_ITEMS = stringSetPreferencesKey("hidden_top_bar_items")
        val AFTER_DELETE_NAVIGATION = stringPreferencesKey("after_delete_navigation")
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
                    loadImages = prefs[Keys.LOAD_IMAGES] ?: false,
                    // Stored as enum names so renaming a value drops the
                    // old key silently; entries that fail to resolve are
                    // skipped so an unknown name never crashes the read.
                    hiddenTopBarItems = (prefs[Keys.HIDDEN_TOP_BAR_ITEMS] ?: emptySet())
                        .mapNotNull { runCatching { TopBarItemId.valueOf(it) }.getOrNull() }
                        .toSet(),
                    // Stored as enum.name so renaming a value drops the old
                    // key silently; entries that fail to resolve fall back
                    // to the safe default (RETURN_TO_LIST).
                    afterDeleteNavigation = prefs[Keys.AFTER_DELETE_NAVIGATION]
                        ?.let { runCatching { AfterDeleteNavigation.valueOf(it) }.getOrNull() }
                        ?: AfterDeleteNavigation.RETURN_TO_LIST
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
    suspend fun setAfterDeleteNavigation(value: AfterDeleteNavigation) =
        dataStore.edit { it[Keys.AFTER_DELETE_NAVIGATION] = value.name }

    /**
     * Toggle a single top-bar item's hidden state. Pass the new desired
     * visibility; the previous state doesn't have to be known at the
     * call site. Adding an item to the set removes it from any bar it
     * would otherwise appear in on Inbox / Message Detail / Compose.
     */
    suspend fun setTopBarItemHidden(id: TopBarItemId, hidden: Boolean) =
        dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_TOP_BAR_ITEMS] ?: emptySet()
            val next = if (hidden) current + id.name else current - id.name
            if (next.isEmpty()) prefs.remove(Keys.HIDDEN_TOP_BAR_ITEMS)
            else prefs[Keys.HIDDEN_TOP_BAR_ITEMS] = next
        }

    /**
     * Clear every hidden top-bar item at once. Used by the "Reset to
     * defaults" affordance in [com.threemail.android.ui.screens.settings.TopBarCustomisationScreen].
     */
    suspend fun clearHiddenTopBarItems() =
        dataStore.edit { it.remove(Keys.HIDDEN_TOP_BAR_ITEMS) }
}
