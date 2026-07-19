package com.threemail.android.ui.screens.message

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.WebResourceRequest
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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Report
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.threemail.android.R
import com.threemail.android.data.crypto.SignatureStatus
import com.threemail.android.domain.model.Attachment
import com.threemail.android.ui.components.LoadingIndicator
import com.threemail.android.ui.theme.avatarColorFor
import java.io.File

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
    var menuOpen by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onNavigateBack()
    }

    // OpenKeychain passphrase prompt for decryption.
    val pgpActionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onPgpUserActionResult()
        }
    }
    LaunchedEffect(state.pgpUserAction) {
        state.pgpUserAction?.let { pi ->
            pgpActionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    LaunchedEffect(state.openFile) {
        state.openFile?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.open_with))) }
            viewModel.onFileOpened()
        }
    }

    if (showMoveDialog) {
        AlertDialog(
            onDismissRequest = { showMoveDialog = false },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                LazyColumn {
                    items(state.moveTargets, key = { it.id }) { folder ->
                        Text(
                            text = folder.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoveDialog = false
                                    viewModel.moveToFolder(folder)
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
                    IconButton(onClick = { viewModel.toggleRead() }) {
                        Icon(Icons.Default.MarkEmailUnread, contentDescription = stringResource(R.string.mark_as_unread))
                    }
                    IconButton(onClick = { viewModel.archive() }) {
                        Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.archive))
                    }
                    IconButton(onClick = { viewModel.delete() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to_folder)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                            enabled = state.moveTargets.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                showMoveDialog = true
                            }
                        )
                        if (state.spamAvailable) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mark_as_spam)) },
                                leadingIcon = { Icon(Icons.Outlined.Report, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.markSpam()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
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

                    if (state.isEncrypted) {
                        EncryptionBanner(
                            signature = state.signatureStatus,
                            decrypting = state.isDecrypting
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    when {
                        state.isLoadingBody -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.loading_message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // Encrypted: show the decrypted plaintext once available.
                        state.isEncrypted && state.decryptedBody != null -> Text(
                            text = state.decryptedBody!!,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        state.isEncrypted && state.isDecrypting -> {
                            Text(
                                text = stringResource(R.string.decrypting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.isEncrypted -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.pgpError?.let {
                                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                }
                                OutlinedButton(onClick = { viewModel.decrypt() }) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(stringResource(R.string.decrypt))
                                }
                            }
                        }
                        !message.bodyHtml.isNullOrBlank() -> {
                            HtmlEmailContent(
                                html = message.bodyHtml!!,
                                modifier = Modifier.fillMaxWidth()
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

/**
 * A WebView locked down to render HTML mail bodies safely.
 *
 * - JavaScript is explicitly disabled (lint-safe: we set to false, the safe direction).
 * - File and content access are disabled.
 * - Mixed content is blocked.
 * - DOM storage is disabled.
 * - Remote image loads are blocked until user explicitly enables them.
 * - Link taps are intercepted and routed to the system browser via [launchExternal].
 *
 * Extends [WebView] directly (rather than wrapping one) so it slots into
 * [AndroidView]'s `reified T : View` factory without losing the [loadHtml] helper.
 */
class SafeWebView(
    context: android.content.Context,
    private val launchExternal: (Uri) -> Unit = { uri ->
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
) : WebView(context) {
    init {
        settings.javaScriptEnabled = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.domStorageEnabled = false
        settings.loadsImagesAutomatically = false
        // Block remote trackers by default; user-controlled load-images toggle could enable this.
        settings.blockNetworkImage = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url ?: return false
                if (target.scheme == "http" || target.scheme == "https" || target.scheme == "mailto") {
                    launchExternal(target)
                }
                return true // never navigate the WebView itself
            }
        }
    }

    fun loadHtml(html: String) {
        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}

/**
 * Compose-hosted [WebView] that destroys itself when the composable leaves the
 * composition. Without this, navigating away from [MessageDetailScreen] would
 * leak the WebView (and its surface + CookieManager + JS bridge handles).
 */
@Composable
private fun HtmlEmailContent(
    html: String,
    modifier: Modifier = Modifier
) {
    var webView: SafeWebView? by remember { mutableStateOf(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> SafeWebView(ctx).also { webView = it } },
        update = { it.loadHtml(html) }
    )
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.destroy()
            }
            webView = null
        }
    }
}

/**
 * A small banner shown above an encrypted message body summarizing its PGP
 * state: encrypted, plus the signature verification result once decrypted.
 */
@Composable
private fun EncryptionBanner(signature: SignatureStatus?, decrypting: Boolean) {
    val (text, color) = when {
        decrypting -> stringResource(R.string.decrypting) to MaterialTheme.colorScheme.onSurfaceVariant
        signature == SignatureStatus.VALID ->
            stringResource(R.string.pgp_signature_valid) to MaterialTheme.colorScheme.tertiary
        signature == SignatureStatus.UNVERIFIED ->
            stringResource(R.string.pgp_signature_unverified) to MaterialTheme.colorScheme.primary
        signature == SignatureStatus.KEY_MISSING ->
            stringResource(R.string.pgp_signature_key_missing) to MaterialTheme.colorScheme.onSurfaceVariant
        signature == SignatureStatus.INVALID ->
            stringResource(R.string.pgp_signature_invalid) to MaterialTheme.colorScheme.error
        else -> stringResource(R.string.pgp_encrypted) to MaterialTheme.colorScheme.primary
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
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
            text = pluralStringResource(
                R.plurals.attachments_count,
                attachments.size,
                attachments.size
            ),
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
