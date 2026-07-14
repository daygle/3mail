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
    data object Settings : Screen("settings")

    data object Calendar : Screen("calendar")

    data object CalendarEvent : Screen("calendar_event/{accountId}/{eventId}") {
        /** eventId = -1 means "create a new event"; any other value means "edit existing". */
        fun createRoute(accountId: Long, eventId: Long = -1L): String =
            "calendar_event/$accountId/$eventId"
    }

}
