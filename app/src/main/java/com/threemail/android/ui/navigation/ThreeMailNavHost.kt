package com.threemail.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.threemail.android.ui.screens.account.AccountScreen
import com.threemail.android.ui.screens.account.AddAccountScreen
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
                onNavigateToMessage = { messageId ->
                    navController.navigate(Screen.MessageDetail.createRoute(messageId))
                }
            )
        }
        composable(Screen.Compose.route) {
            ComposeScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.MessageDetail.route) {
            MessageDetailScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onReply = { messageId ->
                    navController.navigate(Screen.Compose.createRoute(messageId))
                }
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
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
