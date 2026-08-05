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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Action performed when a message row is swiped. */
enum class SwipeAction { NONE, ARCHIVE, DELETE, TOGGLE_READ, MARK_SPAM, MOVE }

/**
 * Where to navigate from the message-detail screen after the user deletes
 * the currently-open message. Modelled after the Outlook / Gmail choice
 * between "leave the screen" and "open an adjacent email in the same
 * folder". Defaults to [RETURN_TO_LIST] for users who want a quiet exit
 * path; the adjacent-email choices mirror the "swipe delete" rhythm on
 * desktop mail clients.
 */
enum class AfterDeleteNavigation { RETURN_TO_LIST, PREVIOUS_EMAIL, NEXT_EMAIL }

/** Vertical density of the message list. */
enum class MessageDensity { COMFORTABLE, COMPACT, EXTRA_COMPACT }

/** Sort order for the message list. */
enum class MessageSort { DATE_DESC, DATE_ASC, SENDER_ASC, SUBJECT_ASC, UNREAD_FIRST }

data class AppSettings(
    val syncIntervalMinutes: Long = 15,
    val notificationsEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val pushEnabled: Boolean = true,
    /** Require biometric/device authentication before showing mail. */
    val biometricLockEnabled: Boolean = false,
    /** Swipe start-to-end (left-to-right) action. Defaults to Move. */
    val swipeRightAction: SwipeAction = SwipeAction.MOVE,
    /** Swipe end-to-start (right-to-left) short-swipe action. */
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
     * Whether to scale wide HTML email bodies down so they fit the screen
     * width (WebView `useWideViewPort` + `loadWithOverviewMode`) instead of
     * letting fixed-width newsletter layouts overflow horizontally. Default
     * true: shrink-to-fit is what most users expect and matches other mail
     * clients; turning it off renders at the email's native width with
     * sideways scroll.
     */
    val shrinkEmailToFit: Boolean = true,
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
    val afterDeleteNavigation: AfterDeleteNavigation = AfterDeleteNavigation.RETURN_TO_LIST,
    /**
     * Number of messages to load in the inbox and folder views.
     * 0 means "Unlimited" (load all cached messages).
     * Default: 250 for a balance between history and performance.
     */
    val inboxLimit: Int = 250,
    /** Sort order for the inbox and folder views. Defaults to newest first. */
    val inboxSort: MessageSort = MessageSort.DATE_DESC,
    /**
     * Email addresses and domains whose images are always loaded,
     * even when the global "load remote images" preference is off.
     * Populated by the "Always allow from this sender" button in the
     * per-message images-blocked banner. Managed in Settings > Privacy.
     */
    val imageAllowlist: Set<String> = emptySet()
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
        // Retained only long enough for the one-time move into account rows.
        val LEGACY_EMPTY_TRASH_ON_LAUNCH = booleanPreferencesKey("empty_trash_on_launch")
        val LEGACY_EMPTY_TRASH_ON_QUIT = booleanPreferencesKey("empty_trash_on_quit")
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock_enabled")
        val SWIPE_RIGHT = stringPreferencesKey("swipe_right_action")
        val SWIPE_LEFT = stringPreferencesKey("swipe_left_action")
        val MESSAGE_DENSITY = stringPreferencesKey("message_density")
        val PREVIEW_LINES = intPreferencesKey("preview_lines")
        val LOAD_IMAGES = booleanPreferencesKey("load_images")
        val SHRINK_EMAIL_TO_FIT = booleanPreferencesKey("shrink_email_to_fit")
        val HIDDEN_TOP_BAR_ITEMS = stringSetPreferencesKey("hidden_top_bar_items")
        val AFTER_DELETE_NAVIGATION = stringPreferencesKey("after_delete_navigation")
        val INBOX_LIMIT = intPreferencesKey("inbox_limit")
        val INBOX_SORT = stringPreferencesKey("inbox_sort")
        val IMAGE_ALLOWLIST = stringSetPreferencesKey("image_allowlist")
    }

    val settings: Flow<AppSettings> = flow {
        dataStore.data.collect { prefs ->
            emit(
            AppSettings(
                syncIntervalMinutes = prefs[Keys.SYNC_INTERVAL] ?: 15L,
                    notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
                    themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                    useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
                    pushEnabled = prefs[Keys.PUSH_ENABLED] ?: true,
                    biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false,
                    // Legacy prefs holding the now-removed "TOGGLE_STAR"
                    // string coerce to ARCHIVE (right) / DELETE (left) -
                    // the safe defaults. Intentional one-way migration;
                    // do not surface this as a visible error.
                    swipeRightAction = prefs[Keys.SWIPE_RIGHT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.MOVE,
                    swipeLeftAction = prefs[Keys.SWIPE_LEFT]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: SwipeAction.DELETE,
                    messageDensity = prefs[Keys.MESSAGE_DENSITY]?.let { runCatching { MessageDensity.valueOf(it) }.getOrNull() } ?: MessageDensity.COMFORTABLE,
                    previewLines = (prefs[Keys.PREVIEW_LINES] ?: 2).coerceIn(0, 3),
                    loadImages = prefs[Keys.LOAD_IMAGES] ?: false,
                    shrinkEmailToFit = prefs[Keys.SHRINK_EMAIL_TO_FIT] ?: true,
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
                        ?.let { stored ->
                            // Keep existing users on the renamed option: the
                            // old enum name was persisted as NEXT_MESSAGE.
                            val migrated = if (stored == "NEXT_MESSAGE") "NEXT_EMAIL" else stored
                            runCatching { AfterDeleteNavigation.valueOf(migrated) }.getOrNull()
                        }
                        ?: AfterDeleteNavigation.RETURN_TO_LIST,
                    inboxLimit = prefs[Keys.INBOX_LIMIT] ?: 250,
                    inboxSort = prefs[Keys.INBOX_SORT]?.let { runCatching { MessageSort.valueOf(it) }.getOrNull() } ?: MessageSort.DATE_DESC,
                    imageAllowlist = prefs[Keys.IMAGE_ALLOWLIST] ?: emptySet()
                )
            )
        }
    }

    suspend fun setSyncInterval(minutes: Long) = dataStore.edit { it[Keys.SYNC_INTERVAL] = minutes }
    suspend fun setNotificationsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }

    /**
     * Reads the pre-v28 global Trash switches without changing them. The
     * application copies these values to existing mailboxes before clearing
     * them, so users who enabled the old global setting do not lose it during
     * the scope change.
     */
    suspend fun readLegacyTrashSettings(): Pair<Boolean, Boolean>? {
        val prefs = dataStore.data.first()
        val hasLaunch = prefs[Keys.LEGACY_EMPTY_TRASH_ON_LAUNCH] != null
        val hasQuit = prefs[Keys.LEGACY_EMPTY_TRASH_ON_QUIT] != null
        if (!hasLaunch && !hasQuit) return null
        return (
            prefs[Keys.LEGACY_EMPTY_TRASH_ON_LAUNCH] ?: false
        ) to (
            prefs[Keys.LEGACY_EMPTY_TRASH_ON_QUIT] ?: false
        )
    }

    /** Remove the old global switches after account rows have been updated. */
    suspend fun clearLegacyTrashSettings() = dataStore.edit {
        it.remove(Keys.LEGACY_EMPTY_TRASH_ON_LAUNCH)
        it.remove(Keys.LEGACY_EMPTY_TRASH_ON_QUIT)
    }

    suspend fun setPushEnabled(enabled: Boolean) = dataStore.edit { it[Keys.PUSH_ENABLED] = enabled }
    suspend fun setBiometricLockEnabled(enabled: Boolean) = dataStore.edit { it[Keys.BIOMETRIC_LOCK] = enabled }
    suspend fun setSwipeRightAction(action: SwipeAction) = dataStore.edit { it[Keys.SWIPE_RIGHT] = action.name }
    suspend fun setSwipeLeftAction(action: SwipeAction) = dataStore.edit { it[Keys.SWIPE_LEFT] = action.name }
    suspend fun setMessageDensity(density: MessageDensity) = dataStore.edit { it[Keys.MESSAGE_DENSITY] = density.name }
    suspend fun setPreviewLines(lines: Int) = dataStore.edit { it[Keys.PREVIEW_LINES] = lines.coerceIn(0, 3) }
    suspend fun setLoadImages(enabled: Boolean) = dataStore.edit { it[Keys.LOAD_IMAGES] = enabled }
    suspend fun setShrinkEmailToFit(enabled: Boolean) = dataStore.edit { it[Keys.SHRINK_EMAIL_TO_FIT] = enabled }
    suspend fun setAfterDeleteNavigation(value: AfterDeleteNavigation) =
        dataStore.edit { it[Keys.AFTER_DELETE_NAVIGATION] = value.name }

    suspend fun setInboxLimit(limit: Int) = dataStore.edit { it[Keys.INBOX_LIMIT] = limit }

    suspend fun setInboxSort(sort: MessageSort) = dataStore.edit { it[Keys.INBOX_SORT] = sort.name }

    /** Add a sender email address or domain to the image allowlist. Duplicates (case-insensitive) are ignored. */
    suspend fun addToImageAllowlist(sender: String) = dataStore.edit { prefs ->
        val normalized = sender.trim().lowercase().takeIf { it.isNotBlank() } ?: return@edit
        val current = prefs[Keys.IMAGE_ALLOWLIST] ?: emptySet()
        prefs[Keys.IMAGE_ALLOWLIST] = current + normalized
    }

    /** Remove a single entry from the image allowlist. */
    suspend fun removeFromImageAllowlist(item: String) = dataStore.edit { prefs ->
        val current = prefs[Keys.IMAGE_ALLOWLIST] ?: emptySet()
        val next = current - item.lowercase()
        if (next.isEmpty()) prefs.remove(Keys.IMAGE_ALLOWLIST)
        else prefs[Keys.IMAGE_ALLOWLIST] = next
    }

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
