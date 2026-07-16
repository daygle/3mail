package com.threemail.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.threemail.android.ui.screens.account.AccountScreen
import com.threemail.android.ui.screens.account.AddAccountScreen
import com.threemail.android.ui.screens.calendar.CalendarEventScreen
import com.threemail.android.ui.screens.calendar.CalendarScreen
import com.threemail.android.ui.screens.compose.ComposeScreen
import com.threemail.android.ui.screens.inbox.InboxScreen
import com.threemail.android.ui.screens.message.MessageDetailScreen
import com.threemail.android.ui.screens.search.SearchScreen
import com.threemail.android.ui.screens.settings.SettingsScreen

@Composable
fun ThreeMailNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Inbox.route) {
        composable(Screen.Inbox.route) {
            InboxScreen(
                viewModel = hiltViewModel(),
                onNavigateToCompose = { navController.navigate(Screen.Compose.createRoute()) },
                onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                onNavigateToAccounts = { navController.navigate(Screen.Accounts.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToCalendar = { navController.navigate(Screen.Calendar.route) },
                onNavigateToMessage = { messageId ->
                    navController.navigate(Screen.MessageDetail.createRoute(messageId))
                }
            )
        }
        composable(
            route = Screen.Compose.route,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType; defaultValue = "new" },
                navArgument("refId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) {
            ComposeScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.MessageDetail.route,
            arguments = listOf(navArgument("messageId") { type = NavType.LongType })
        ) {
            MessageDetailScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onReply = { messageId -> navController.navigate(Screen.Compose.createRoute("reply", messageId)) },
                onReplyAll = { messageId -> navController.navigate(Screen.Compose.createRoute("replyAll", messageId)) },
                onForward = { messageId -> navController.navigate(Screen.Compose.createRoute("forward", messageId)) }
            )
        }
        composable(Screen.Search.route) {
            SearchScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onMessageClick = { messageId ->
                    navController.navigate(Screen.MessageDetail.createRoute(messageId))
                }
            )
        }
        composable(Screen.Accounts.route) {
            AccountScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onAddAccount = { navController.navigate(Screen.AddAccount.route) }
            )
        }
        composable(Screen.AddAccount.route) {
            AddAccountScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Calendar.route) {
            CalendarScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onCreateEvent = { accountId ->
                    navController.navigate(Screen.CalendarEvent.createRoute(accountId, -1L))
                },
                onEditEvent = { accountId, eventId ->
                    navController.navigate(Screen.CalendarEvent.createRoute(accountId, eventId))
                }
            )
        }
        composable(
            route = Screen.CalendarEvent.route,
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType },
                navArgument("eventId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            CalendarEventScreen(
                viewModel = hiltViewModel(),
                accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L,
                eventId = backStackEntry.arguments?.getLong("eventId") ?: -1L,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
