package com.threemail.android.ui.screens.message

import android.content.Intent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.threemail.android.R
import com.threemail.android.domain.model.Attachment
import com.threemail.android.ui.components.LoadingIndicator
import com.threemail.android.ui.theme.avatarColorFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    viewModel: MessageDetailViewModel,
    onNavigateBack: () -> Unit,
    onReply: (Long) -> Unit,
    onReplyAll: (Long) -> Unit,
    onForward: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val message = state.message
    val context = LocalContext.current

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onNavigateBack()
    }

    LaunchedEffect(state.openFile) {
        state.openFile?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
            viewModel.onFileOpened()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleStar() }) {
                        Icon(
                            imageVector = if (message?.isStarred == true) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Star",
                            tint = if (message?.isStarred == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.toggleRead() }) {
                        Icon(Icons.Default.MarkEmailUnread, contentDescription = stringResource(R.string.mark_as_unread))
                    }
                    IconButton(onClick = { viewModel.archive() }) {
                        Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.archive))
                    }
                    IconButton(onClick = { viewModel.delete() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            if (message != null) {
                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { onReply(message.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.reply))
                        }
                        OutlinedButton(onClick = { onReplyAll(message.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = stringResource(R.string.reply_all), modifier = Modifier.size(18.dp))
                        }
                        OutlinedButton(onClick = { onForward(message.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.forward), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        when {
            state.isLoading -> LoadingIndicator()
            message == null -> Text("Message not found", modifier = Modifier.padding(padding))
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(text = message.subject, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val sender = message.from.firstOrNull()
                        val label = sender?.name?.takeIf { it.isNotBlank() } ?: sender?.address ?: "?"
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(avatarColorFor(sender?.address ?: label), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label.first().uppercaseChar().toString(), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "to ${message.to.joinToString { it.name.ifBlank { it.address } }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    if (message.attachments.isNotEmpty()) {
                        AttachmentRow(
                            attachments = message.attachments,
                            downloading = state.downloadingAttachment,
                            onClick = { viewModel.downloadAttachment(it) }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    when {
                        state.isLoadingBody -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("Loading message…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        !message.bodyHtml.isNullOrBlank() -> {
                            AndroidView(
                                modifier = Modifier.fillMaxWidth(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webViewClient = WebViewClient()
                                        settings.loadsImagesAutomatically = true
                                        settings.blockNetworkImage = true // privacy: block remote trackers by default
                                        loadDataWithBaseURL(null, message.bodyHtml!!, "text/html", "utf-8", null)
                                    }
                                }
                            )
                        }
                        else -> Text(
                            text = message.bodyPlain ?: message.bodyPreview,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachments: List<Attachment>,
    downloading: String?,
    onClick: (Attachment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${attachments.size} attachment${if (attachments.size > 1) "s" else ""}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.take(4).forEach { attachment ->
                AssistChip(
                    onClick = { onClick(attachment) },
                    label = { Text(attachment.fileName, maxLines = 1) },
                    leadingIcon = {
                        if (downloading == attachment.fileName) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AttachFile, contentDescription = null, Modifier.size(AssistChipDefaults.IconSize))
                        }
                    }
                )
            }
        }
    }
}
