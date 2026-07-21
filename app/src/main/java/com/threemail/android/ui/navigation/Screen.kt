package com.threemail.android.ui.navigation

sealed class Screen(val route: String) {
    data object Inbox : Screen("inbox")

    data object Compose : Screen("compose?mode={mode}&refId={refId}") {
        /**
         * @param mode one of "new", "reply", "replyAll", "forward"
         * @param refId id of the message being replied to / forwarded, if any
         */
        fun createRoute(mode: String = "new", refId: Long? = null): String =
            "compose?mode=$mode&refId=${refId ?: -1L}"
    }

    data object MessageDetail : Screen("message/{messageId}") {
        fun createRoute(messageId: Long): String = "message/$messageId"
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

    data object AccountServer : Screen("account_settings/{accountId}/server") {
        fun createRoute(accountId: Long): String = "account_settings/$accountId/server"
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
     */
    data object ManageCalendars : Screen("manage_calendars")
}
