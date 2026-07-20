package com.threemail.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.threemail.android.R
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
 * Top-level destinations presented in the bottom [NavigationBar]. Anything
 * not on this list (Compose, MessageDetail, CalendarEvent, Settings,
 * account screens, ...) renders no bar so it cannot compete with
 * screen-specific chrome.
 *
 * Icons are referenced as ImageVector values at construction time so the
 * NavHost can compare them purely without rounding through stringResource
 * in the bar; both `Mail` and `CalendarMonth` exist in every supported
 * Compose Material3 range (1.1+) we're shipping against.
 */
private enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    Mail(Screen.Inbox.route, R.string.nav_mail, Icons.Default.Email),
    Calendar(Screen.Calendar.route, R.string.nav_calendar, Icons.Default.CalendarMonth)
}

/**
 * Hosts the [NavHost] and overlays a [NavigationBar] at the bottom edge
 * when the current destination is one of [TopLevelDestination].
 *
 * Layout choice: a [Box] with the bar `align(Alignment.BottomCenter)` is
 * preferred over a `Scaffold(bottomBar = ...)` because every child screen
 * (Inbox, Calendar, Compose, ...) already declares its own [androidx.compose.material3.Scaffold]
 * to mount its top app bar + FAB. A pair of nested Scaffolds double-pads
 * the content and would clip the inner FAB under the outer bottom bar -
 * well-documented sharp edge from the Compose nav samples docs. With a
 * Box overlay the inner Scaffold owns all of its layout math and the
 * outer bar sits above its lower edge.
 */
@Composable
fun ThreeMailNavHost(navController: NavHostController) {
    Box(modifier = Modifier.fillMaxSize()) {
        MailNavGraph(navController)
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        if (currentRoute in setOf(Screen.Inbox.route, Screen.Calendar.route)) {
            NavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                TopLevelDestination.values().forEach { destination ->
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == destination.route }
                        ?: false
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (currentRoute != destination.route) {
                                // Standard Nav-Compose tab-switching recipe:
                                // popUpTo(startDestination) keeps each tab's
                                // back stack independent, and launchSingleTop
                                // avoids stacking multiple copies when the
                                // user mashes the icon.
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MailNavGraph(navController: NavHostController) {
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
                onNavigateToManageFolders = { navController.navigate(Screen.ManageFolders.route) }
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
                onNavigateToNext = { nextId ->
                    // Pop the current detail off the back stack, then push
                    // the next. The combination avoids the route-template
                    // ambiguity of popUpTo("message/{messageId}") against
                    // inflated back-stack entries, and exactly matches the
                    // intent: back from the next message lands the user on
                    // whatever was before the detail (typically inbox), not
                    // on the just-deleted message which the room cache
                    // would happily re-show as "not yet deleted".
                    navController.popBackStack()
                    navController.navigate(Screen.MessageDetail.createRoute(nextId))
                },
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
        ) {
            AccountSettingsScreen(
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
                onEditEvent = { accountId, eventId ->
                    navController.navigate(Screen.CalendarEvent.createRoute(accountId, eventId))
                },
                onNavigateToManageCalendars = { navController.navigate(Screen.ManageCalendars.route) }
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
        composable(Screen.ManageCalendars.route) {
            ManageCalendarsScreen(
                viewModel = hiltViewModel(),
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
