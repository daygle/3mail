package com.threemail.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threemail.android.data.settings.MessageDensity
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.ui.theme.avatarColorFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MailListItem(
    message: MailMessage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    density: MessageDensity = MessageDensity.COMFORTABLE,
    previewLines: Int = 2
) {
    val sender = message.from.firstOrNull()
    val senderLabel = sender?.name?.takeIf { it.isNotBlank() } ?: sender?.address ?: "(unknown)"
    val avatarColor = avatarColorFor(sender?.address ?: senderLabel)

    val extraCompact = density == MessageDensity.EXTRA_COMPACT
    val compact = density == MessageDensity.COMPACT || extraCompact
    val rowPaddingV = when {
        extraCompact -> 4.dp
        compact -> 8.dp
        else -> 12.dp
    }
    // The 32 dp minimum keeps the sender-initial letterform readable when
    // Accessibility text-scaling is on (the system setting users can't toggle
    // from inside the app); smaller circles push titleMedium ascenders into
    // the rounded outline.
    val avatarSize = when {
        extraCompact -> 32.dp
        compact -> 36.dp
        else -> 44.dp
    }

    // Unread/selected tints intentionally avoid `primaryContainer` because it
    // is taken from the user's Material You accent palette. A red wallpaper
    // would otherwise turn every unread row red (the accent can be highly
    // saturated, so even 0.1f alpha reads as the accent colour). The neutral
    // `surfaceVariant` comes from the accent-free tonal palette and stays
    // neutral regardless of the wallpaper. Selected rows deliberately keep
    // `primaryContainer` here because the user just chose them and the highlight
    // should follow the theme.
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        message.isRead -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = rowPaddingV),
        verticalAlignment = Alignment.Top
    ) {
        // Sender avatar with deterministic color + initial. In selection mode a
        // selected row swaps the avatar for a check disc.
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(avatarSize)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else if (message.isRead) avatarColor.copy(alpha = 0.8f) else avatarColor
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White
                )
            } else {
                Text(
                    text = senderLabel.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
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
                // "Sent encrypted" badge. The flag is sourced from the
                // message_flags side-table in MailRepository (so it
                // survives REPLACE-style server syncs); an icon here is
                // a touch lighter than a chip row so the row still
                // reads as "subject + meta" at a glance.
                if (message.isEncrypted) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(
                            com.threemail.android.R.string.sent_encrypted_content_description
                        ),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                // Date color avoids `primary` for the unread state; under
                // dynamic Material You color, `primary` tracks the user's
                // accent palette and would render red on a red-tinted
                // wallpaper, just like the row background used to.
                // `onSurface` (read contrast text on the row surface) gives
                // the unread date a slight emphasis over the read date's
                // `onSurfaceVariant`, without inheriting the accent colour.
                Text(
                    text = formatDate(message.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (message.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
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
                if (previewLines > 0) {
                    Text(
                        text = message.bodyPreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = previewLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
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
