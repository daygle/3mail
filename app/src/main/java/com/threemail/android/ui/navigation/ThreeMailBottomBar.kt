package com.threemail.android.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.threemail.android.R
import com.threemail.android.ui.navigation.Screen
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Email

internal enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    Mail(Screen.Inbox.route, R.string.nav_mail, Icons.Default.Email),
    Calendar(Screen.Calendar.route, R.string.nav_calendar, Icons.Default.CalendarMonth)
}

@Composable
fun ThreeMailBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    if (currentRoute !in setOf(Screen.Inbox.route, Screen.Calendar.route)) return

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        TopLevelDestination.values().forEach { destination ->
            val selected = currentRoute == destination.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (currentRoute != destination.route) {
                        onNavigate(destination.route)
                    }
                },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}
