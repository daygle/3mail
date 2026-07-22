package com.threemail.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.threemail.android.ui.screens.account.AccountFolderRolesScreen
import com.threemail.android.ui.screens.account.AccountIdentitiesScreen
import com.threemail.android.ui.screens.account.AccountPgpScreen
import com.threemail.android.ui.screens.account.AccountPushScreen
import com.threemail.android.ui.screens.account.AccountIncomingServerScreen
import com.threemail.android.ui.screens.account.AccountOutgoingServerScreen
import com.threemail.android.ui.screens.account.AccountScreen
import com.threemail.android.ui.screens.account.AccountSettingsScreen
import com.threemail.android.ui.screens.account.AddAccountScreen
import com.threemail.android.ui.screens.calendar.CalendarEventScreen
import com.threemail.android.ui.screens.calendar.CalendarScreen
import com.threemail.android.ui.screens.calendar.ManageCalendarsScreen
import com.threemail.android.ui.screens.compose.ComposeScreen
import com.threemail.android.ui.screens.folders.FolderManagementScreen
import com.threemail.android.ui.screens.inbox.InboxScreen
import com.threemail.android.ui.screens.message.MessageDetailScreen
import com.threemail.android.ui.screens.search.SearchScreen
import com.threemail.android.ui.screens.settings.SettingsScreen
import com.threemail.android.ui.screens.settings.TopBarCustomisationScreen

/**
 * Hosts the [NavHost]. Individual top-level screens (Inbox, Calendar)
 * mount the [ThreeMailBottomBar] inside their own Scaffolds so that
 * the ModalNavigationDrawer correctly overlaps the entire screen
 * including the bottom menu.
 */
@Composable
fun ThreeMailNavHost(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val bottomBar: @Composable () -> Unit = {
        ThreeMailBottomBar(
            currentRoute = currentRoute,
            onNavigate = { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        )
    }

    NavHost(navController = navController, startDestination = Screen.Inbox.route) {
        composable(Screen.Inbox.route) {
            InboxScreen(
                viewModel = hiltViewModel(),
                onNavigateToCompose = { navController.navigate(Screen.Compose.createRoute()) },
                onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                onNavigateToAccounts = { navController.navigate(Screen.Accounts.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToMessage = { messageId ->
                    navController.navigate(Screen.MessageDetail.createRoute(messageId))
                },
                onNavigateToAddAccount = { navController.navigate(Screen.AddAccount.route) },
                onNavigateToManageFolders = { navController.navigate(Screen.ManageFolders.route) },
                onNavigateToAccountSettings = { accountId ->
                    navController.navigate(Screen.AccountSettings.createRoute(accountId))
                },
                bottomBar = bottomBar
            )
        }
        composable(
            route = Screen.Compose.route,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType; defaultValue = "new" },
                navArgument("refId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("to") { type = NavType.StringType; defaultValue = "" }
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
                onNavigateToNext = { nextId ->
                    navController.popBackStack()
                    navController.navigate(Screen.MessageDetail.createRoute(nextId))
                },
                onReply = { messageId -> navController.navigate(Screen.Compose.createRoute("reply", messageId)) },
                onReplyAll = { messageId -> navController.navigate(Screen.Compose.createRoute("replyAll", messageId)) },
                onForward = { messageId -> navController.navigate(Screen.Compose.createRoute("forward", messageId)) },
                onComposeTo = { address -> navController.navigate(Screen.Compose.createRoute(to = address)) }
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
                onAddAccount = { navController.navigate(Screen.AddAccount.route) },
                onOpenAccountSettings = { accountId ->
                    navController.navigate(Screen.AccountSettings.createRoute(accountId))
                }
            )
        }
        composable(Screen.AddAccount.route) {
            AddAccountScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.AccountSettings.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: -1L
            AccountSettingsScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onOpenIdentities = {
                    navController.navigate(Screen.AccountIdentities.createRoute(accountId))
                },
                onOpenIncomingServer = {
                    navController.navigate(Screen.AccountIncomingServer.createRoute(accountId))
                },
                onOpenOutgoingServer = {
                    navController.navigate(Screen.AccountOutgoingServer.createRoute(accountId))
                },
                onOpenFolderRoles = {
                    navController.navigate(Screen.AccountFolderRoles.createRoute(accountId))
                },
                onOpenPush = {
                    navController.navigate(Screen.AccountPush.createRoute(accountId))
                },
                onOpenPgp = {
                    navController.navigate(Screen.AccountPgp.createRoute(accountId))
                }
            )
        }
        composable(
            route = Screen.AccountIdentities.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) {
            AccountIdentitiesScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.AccountIncomingServer.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) {
            AccountIncomingServerScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.AccountOutgoingServer.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) {
            AccountOutgoingServerScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.AccountFolderRoles.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) {
            AccountFolderRolesScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.AccountPush.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) {
            AccountPushScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.AccountPgp.route,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) {
            AccountPgpScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.ManageFolders.route) {
            FolderManagementScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTopBarSettings = { navController.navigate(Screen.TopBarSettings.route) }
            )
        }
        composable(Screen.TopBarSettings.route) {
            TopBarCustomisationScreen(
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
                onCreateSourceEvent = { sourceId ->
                    navController.navigate(
                        Screen.CalendarEvent.createRoute(0L, -1L, sourceId = sourceId)
                    )
                },
                onEditEvent = { accountId, eventId ->
                    navController.navigate(Screen.CalendarEvent.createRoute(accountId, eventId))
                },
                // The boolean flag tells the receiver to land straight on the
                // "choose calendar type" chooser - used by the empty-state
                // primary action so users who have no calendars at all get a
                // picker one tap away. The top-bar Tune shortcut still passes
                // `false` so existing users land on the manage list as before.
                onNavigateToManageCalendars = { autoAdd ->
                    if (autoAdd) {
                        navController.navigate(Screen.ManageCalendars.createRoute(autoAdd = true))
                    } else {
                        navController.navigate(Screen.ManageCalendars.route)
                    }
                },
                bottomBar = bottomBar
            )
        }
        composable(
            route = Screen.CalendarEvent.route,
            arguments = listOf(
                navArgument("accountId") { type = NavType.LongType },
                navArgument("eventId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("sourceId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) { backStackEntry ->
            CalendarEventScreen(
                viewModel = hiltViewModel(),
                accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L,
                eventId = backStackEntry.arguments?.getLong("eventId") ?: -1L,
                sourceId = backStackEntry.arguments?.getLong("sourceId") ?: -1L,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.ManageCalendars.route,
            arguments = listOf(
                navArgument("autoAdd") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val autoAdd = backStackEntry.arguments?.getBoolean("autoAdd") ?: false
            ManageCalendarsScreen(
                viewModel = hiltViewModel(),
                autoAdd = autoAdd,
                onAddAccount = { navController.navigate(Screen.AddAccount.route) },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
