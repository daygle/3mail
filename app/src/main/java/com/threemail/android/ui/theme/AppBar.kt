package com.threemail.android.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Google-style grey top app bar.
 *
 * Light mode uses a soft neutral grey (Google's classic `#F1F3F4`) so the bar
 * reads as a distinct surface above white content instead of blending into it;
 * dark mode uses a muted grey that sits just above the app's dark surfaces.
 */
val AppBarGreyLight = Color(0xFFF1F3F4)
val AppBarGreyDark = Color(0xFF2B2D31)

/**
 * Shared [TopAppBarColors] for the app's standard top bars. Kept flat — the
 * scrolled colour matches the resting colour so the bar stays an even grey
 * rather than tinting as content scrolls under it. Darkness is inferred from
 * the active scheme so it tracks the System / Light / Dark theme and dynamic
 * colour uniformly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTopBarColors(): TopAppBarColors {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val barColor = if (dark) AppBarGreyDark else AppBarGreyLight
    return TopAppBarDefaults.topAppBarColors(
        containerColor = barColor,
        scrolledContainerColor = barColor
    )
}
