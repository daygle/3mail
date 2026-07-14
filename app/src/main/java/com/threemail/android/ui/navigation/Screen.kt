package com.threemail.android.ui.navigation

sealed class Screen(val route: String) {
    data object Inbox : Screen("inbox")
    data object Compose : Screen("compose?replyTo={replyTo}") {
        fun createRoute(replyTo: Long? = null): String {
            return if (replyTo != null) "compose?replyTo=$replyTo" else "compose"
        }
    }
    data object MessageDetail : Screen("message/{messageId}") {
        fun createRoute(messageId: Long): String = "message/$messageId"
    }
    data object Search : Screen("search")
    data object Accounts : Screen("accounts")
    data object AddAccount : Screen("add_account")
    data object Settings : Screen("settings")
}
