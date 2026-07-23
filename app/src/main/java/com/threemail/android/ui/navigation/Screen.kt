package com.threemail.android.ui.navigation

sealed class Screen(val route: String) {
    data object Inbox : Screen("inbox")

    data object Compose : Screen("compose?mode={mode}&refId={refId}&to={to}") {
        /**
         * @param mode one of "new", "reply", "replyAll", "forward"
         * @param refId id of the message being replied to / forwarded, if any
         * @param to pre-filled To address for a fresh compose (URL-encoded so
         *   the "@" and other reserved characters survive the nav route)
         */
        fun createRoute(mode: String = "new", refId: Long? = null, to: String = ""): String =
            "compose?mode=$mode&refId=${refId ?: -1L}&to=${android.net.Uri.encode(to)}"
    }

    /**
     * Single-message detail surface that, when [folderId] >= 0 (or [unified]
     * is true), ALSO hosts a horizontal [androidx.compose.foundation.pager.HorizontalPager]
     * over that folder's entire message list - so swiping left/right steps
     * through the next-older / next-newer message without leaving the screen.
     *
     * Both query-style args are optional and default to "no pager":
     *   - `folderId = -1` (default) AND `unified = false` (default) ->
     *     legacy single-message mode used by deep links from Search and
     *     notifications, where the caller doesn't know which folder the
     *     message came from (and a cross-account context is meaningless).
     *   - Otherwise the screen reads the matching reactive observer from the
     *     repository and reveals adjacent messages as swipe targets.
     *
     * Back-button semantics: a single detail-screen entry stays on the back
     * stack regardless of how many swipes the user performs inside it, so
     * "back" always returns to the screen that opened detail (inbox or
     * search). The post-delete "advance to next message" preference still
     * works; in pager mode it animates within the pager instead of popping
     * + pushing a new entry, keeping the back stack clean.
     */
    data object MessageDetail : Screen("message/{messageId}?folderId={folderId}&unified={unified}") {
        fun createRoute(messageId: Long, folderId: Long = -1L, unified: Boolean = false): String =
            "message/$messageId?folderId=$folderId&unified=$unified"
    }

    data object Search : Screen("search")
    data object Accounts : Screen("accounts")
    data object AddAccount : Screen("add_account")

    data object AccountSettings : Screen("account_settings/{accountId}") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId"
    }

    /**
     * Drilled-out sub-pages of [AccountSettings]. Each carries the same
     * accountId and hosts one focused section (identities, server settings,
     * folder roles, IDLE push, OpenPGP keys) so the main settings screen stays
     * a short scroll of drill-in rows.
     */
    data object AccountIdentities : Screen("account_settings/{accountId}/identities") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId/identities"
    }

    data object AccountIncomingServer : Screen("account_settings/{accountId}/server/incoming") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId/server/incoming"
    }

    data object AccountOutgoingServer : Screen("account_settings/{accountId}/server/outgoing") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId/server/outgoing"
    }

    data object AccountFolderRoles : Screen("account_settings/{accountId}/folder_roles") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId/folder_roles"
    }

    data object AccountPush : Screen("account_settings/{accountId}/push") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId/push"
    }

    data object AccountPgp : Screen("account_settings/{accountId}/pgp") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId/pgp"
    }

    data object ManageFolders : Screen("manage_folders")

    data object Settings : Screen("settings")

    /**
     * Top-bar visibility controls. Reachable from [Settings] via an inline
     * settings row; users can show or hide individual top-bar actions on
     * Inbox, Message Detail, and Compose screens.
     */
    data object TopBarSettings : Screen("top_bar_settings")

    data object Calendar : Screen("calendar")

    data object CalendarEvent : Screen("calendar_event/{accountId}/{eventId}?sourceId={sourceId}") {
        /**
         * eventId = -1 means "create a new event"; any other value means
         * "edit existing". A positive [sourceId] targets a CalDAV
         * subscription instead of a Google account (create mode only —
         * edits carry their source on the loaded event).
         */
        fun createRoute(accountId: Long, eventId: Long = -1L, sourceId: Long = -1L): String =
            "calendar_event/$accountId/$eventId?sourceId=$sourceId"
    }

    /**
     * Manage-calendars surface. Lists every Google account's subscribed
     * calendars with a per-row visibility toggle and surfaces two CTAs
     * (subscribe by URL / create new) so users can expand what the
     * calendar app shows. Deep-linked from CalendarScreen's top bar.
     *
     * [autoAdd] is an optional navigation argument: when `true`,
     * [com.threemail.android.ui.screens.calendar.ManageCalendarsScreen]
     * opens its "choose calendar type" chooser dialog on first
     * composition (used by the empty-state primary action on the main
     * Calendar page so the user lands one tap away from the type picker
     * rather than having to tap a second FAB). Defaults to `false` so
     * plain deep-links from the top bar continue to land on the manage
     * list itself.
     */
    data object ManageCalendars : Screen("manage_calendars?autoAdd={autoAdd}") {
        fun createRoute(autoAdd: Boolean = false): String =
            "manage_calendars?autoAdd=$autoAdd"
    }
}
