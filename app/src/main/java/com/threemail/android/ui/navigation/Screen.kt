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

    data object ManageFolders : Screen("manage_folders")

    data object Settings : Screen("settings")

    /**
     * Top-bar visibility controls. Reachable from [Settings] via an inline
     * settings row; users can show or hide individual top-bar actions on
     * Inbox, Message Detail, and Compose screens.
     */
    data object TopBarSettings : Screen("top_bar_settings")

    data object Calendar : Screen("calendar")

    data object CalendarEvent : Screen("calendar_event/{accountId}/{eventId}") {
        /** eventId = -1 means "create a new event"; any other value means "edit existing". */
        fun createRoute(accountId: Long, eventId: Long = -1L): String =
            "calendar_event/$accountId/$eventId"
    }
}
