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
 * their screen with no path back.
 *
 * Membership changes over time as we add features. Adding a new value
 * is safe: existing stored "hidden" sets keep working, and the new value
 * defaults to visible because it's missing. Renaming a value is unsafe:
 * the stored set would treat it as a fresh item and show it again - if
 * a rename is unavoidable, ship a one-shot migration that drops the old
 * key from AppSettings.hiddenTopBarItems on first launch.
 */
enum class TopBarItemId {
    // Inbox top bar (InboxTopBar composable in InboxScreen.kt)
    INBOX_SEARCH,
    INBOX_SYNC,
    /** Empty-trash button is only rendered while viewing the Trash folder,
     *  so configuring it to hidden has no effect outside that folder. */
    INBOX_EMPTY_TRASH,
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
