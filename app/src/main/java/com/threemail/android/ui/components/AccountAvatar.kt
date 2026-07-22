package com.threemail.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.threemail.android.ui.theme.avatarColorFor

/**
 * Reusable avatar for mail accounts that attempts to load the domain's icon
 * (favicon) using Google's favicon service. Falls back to a deterministic
 * colored circle with the account's initial when the icon is unavailable.
 */
@Composable
fun AccountAvatar(
    email: String,
    size: Dp,
    modifier: Modifier = Modifier,
    accountColor: Int? = null,
    contentDescription: String? = null
) {
    val domain = email.substringAfter("@")
    val faviconUrl = "https://www.google.com/s2/favicons?domain=$domain&sz=64"
    val avatarColor = accountColor?.let { Color(it) } ?: avatarColorFor(email)
    val initial = email.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor),
        contentAlignment = Alignment.Center
    ) {
        if (isError || isLoading) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        AsyncImage(
            model = faviconUrl,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            onSuccess = { isLoading = false; isError = false },
            onError = { isLoading = false; isError = true },
            onLoading = { isLoading = true; isError = false }
        )
    }
}
