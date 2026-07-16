package com.threemail.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.ui.theme.avatarColorFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MailListItem(
    message: MailMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleStar: (() -> Unit)? = null
) {
    val sender = message.from.firstOrNull()
    val senderLabel = sender?.name?.takeIf { it.isNotBlank() } ?: sender?.address ?: "(unknown)"
    val avatarColor = avatarColorFor(sender?.address ?: senderLabel)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (message.isRead) MaterialTheme.colorScheme.surface 
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Sender avatar with deterministic color + initial.
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(if (message.isRead) avatarColor.copy(alpha = 0.8f) else avatarColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = senderLabel.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = senderLabel,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (message.isRead) FontWeight.Medium else FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (message.attachments.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = formatDate(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = message.subject,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.bodyPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onToggleStar != null) {
                    IconButton(onClick = onToggleStar, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (message.isStarred) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star",
                            modifier = Modifier.size(18.dp),
                            tint = if (message.isStarred) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 24 * 60 * 60 * 1000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        diff < 7 * 24 * 60 * 60 * 1000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
    }
}
