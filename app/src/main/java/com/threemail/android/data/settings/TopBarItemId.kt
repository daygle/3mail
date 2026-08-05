package com.threemail.android.data.settings

/**
 * Stable identifiers for every top-bar action that the user can show or
 * hide in Settings. Each value maps to one IconButton in a specific
 * Scaffold's TopAppBar.actions block; hiding the value moves the action
 * into that bar's MoreVert overflow DropdownMenu so it stays reachable,
 * just one tap further away.
 *
 * Required-by-OS actions (navigation IconButton / back arrow / selection
 * close) are intentionally NOT modelled here - they're structurally
 * required and un-hideable, since hiding them would strand the user on
 * their screen with no path back. The overflow button itself also remains
 * available so a user can always leave selection mode or restore actions.
 *
 * Membership changes over time as we add features. Adding a new value
 * is safe: existing stored "hidden" sets keep working, and the new value
 * defaults to visible because it's missing. Renaming a value is unsafe:
 * the stored set would treat it as a fresh item and show it again - if
 * a rename is unavoidable, ship a one-shot migration that drops the old
 * key from AppSettings.hiddenTopBarItems on first launch. Selection-mode
 * actions that already live in the More menu are hidden from that menu when
 * disabled; inline selection actions move into More when disabled.
 */
enum class TopBarItemId {
    // Inbox top bar (InboxTopBar composable in InboxScreen.kt)
    INBOX_SEARCH,
    INBOX_SYNC,
    /** Empty-trash button is only rendered while viewing the Trash folder,
     *  so configuring it to hidden has no effect outside that folder. */
    INBOX_EMPTY_TRASH,
    // Actions shown after long-pressing a message (selection mode).
    // Clear selection is intentionally required and cannot be hidden.
    SELECTION_ARCHIVE,
    SELECTION_MOVE,
    SELECTION_MARK_READ,
    SELECTION_MARK_UNREAD,
    SELECTION_MARK_SPAM,
    SELECTION_DELETE,
    SELECTION_SELECT_ALL,
    // Message Detail top bar (TopAppBar in MessageDetailScreen.kt)
    DETAIL_MARK_UNREAD,
    DETAIL_ARCHIVE,
    DETAIL_DELETE,
    // Message Detail bottom action bar (bottomBar in MessageDetailScreen.kt).
    // Hiding one drops its button from the bottom bar and surfaces it in the
    // top bar's overflow menu so the action stays reachable.
    DETAIL_REPLY,
    DETAIL_REPLY_ALL,
    DETAIL_FORWARD,
    // Compose top bar (TopAppBar in ComposeScreen.kt)
    COMPOSE_INSERT_IMAGE,
    COMPOSE_ATTACH,
    COMPOSE_SAVE_DRAFT,
    // Note: COMPOSE_SEND is intentionally NOT a member - sending is the
    // primary action of the compose flow and must remain in the bar.
}
