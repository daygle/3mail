package com.threemail.android.ui.screens.compose

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.threemail.android.R
import com.threemail.android.domain.model.Attachment
import com.threemail.android.util.RichTextFormatter
import com.threemail.android.util.TextEdit
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    viewModel: ComposeViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Auto-close after Send OR after the explicit "Save & close" path. Saving a
    // draft does NOT auto-close so the user can keep editing without re-entering
    // recipients. Both keys are observed in a single effect so we cannot
    // double-navigate within one frame.
    LaunchedEffect(state.isSent, state.shouldClose) {
        when {
            state.isSent -> onNavigateBack()
            state.shouldClose -> {
                viewModel.consumeCloseSignal()
                onNavigateBack()
            }
        }
    }

    val recoverableAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        viewModel.onRecoverableAuthHandled()
        viewModel.retryAfterRecoverableAuth()
    }

    LaunchedEffect(state.recoverableAuthIntent) {
        state.recoverableAuthIntent?.let { intent -> recoverableAuthLauncher.launch(intent) }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            copyToCache(context, uri)?.let { viewModel.addAttachment(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { attachmentPicker.launch("*/*") },
                        enabled = !state.isSending && !state.isSavingDraft
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach))
                    }
                    IconButton(
                        onClick = { viewModel.saveAndClose() },
                        enabled = !state.isSavingDraft && !state.isSent
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = stringResource(R.string.save_and_close)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.saveDraft() },
                        enabled = !state.isSavingDraft && !state.isSent
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = stringResource(R.string.save_draft),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.send() }, enabled = !state.isSending) {
                        if (state.isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.Send, contentDescription = stringResource(R.string.send))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (state.accounts.size > 1) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.accounts.forEach { account ->
                        FilterChip(
                            selected = state.selectedAccount?.id == account.id,
                            onClick = { viewModel.selectAccount(account) },
                            label = { Text(account.email, maxLines = 1) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = state.to,
                onValueChange = viewModel::updateTo,
                label = { Text(stringResource(R.string.to)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { viewModel.toggleCcBcc() }) {
                        Text(if (state.showCcBcc) stringResource(R.string.cc_bcc_hide) else stringResource(R.string.cc_bcc_show))
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                    }
                }
            )
            if (state.showCcBcc) {
                OutlinedTextField(
                    value = state.cc,
                    onValueChange = viewModel::updateCc,
                    label = { Text(stringResource(R.string.cc)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.bcc,
                    onValueChange = viewModel::updateBcc,
                    label = { Text(stringResource(R.string.bcc)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = state.subject,
                onValueChange = viewModel::updateSubject,
                label = { Text(stringResource(R.string.subject)) },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                singleLine = true
            )

            if (state.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.attachments.forEach { attachment ->
                        InputChip(
                            selected = false,
                            onClick = { viewModel.removeAttachment(attachment) },
                            label = { Text(attachment.fileName, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_attachment), Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            // Inline confirmation that the draft was saved.
            if (state.isDraftSaved) {
                Text(
                    text = stringResource(R.string.draft_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (state.isSavingDraft) {
                Text(
                    text = stringResource(R.string.syncing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            var bodyValue by remember { mutableStateOf(TextFieldValue(state.body)) }
            LaunchedEffect(state.body) {
                // Adopt externally-set body (reply/forward prefill) without clobbering edits.
                if (state.body != bodyValue.text) {
                    bodyValue = TextFieldValue(state.body, TextRange(state.body.length))
                }
            }
            var showLinkDialog by remember { mutableStateOf(false) }

            fun apply(op: (TextEdit) -> TextEdit) {
                val edit = TextEdit(bodyValue.text, bodyValue.selection.start, bodyValue.selection.end)
                val result = op(edit)
                bodyValue = TextFieldValue(result.text, TextRange(result.selectionStart, result.selectionEnd))
                viewModel.updateBody(result.text)
            }

            Spacer(Modifier.height(8.dp))
            Row {
                IconButton(onClick = { apply(RichTextFormatter::bold) }) {
                    Icon(Icons.Default.FormatBold, contentDescription = stringResource(R.string.format_bold))
                }
                IconButton(onClick = { apply(RichTextFormatter::italic) }) {
                    Icon(Icons.Default.FormatItalic, contentDescription = stringResource(R.string.format_italic))
                }
                IconButton(onClick = { apply(RichTextFormatter::bulletList) }) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = stringResource(R.string.format_bullet_list))
                }
                IconButton(onClick = { apply(RichTextFormatter::numberedList) }) {
                    Icon(Icons.Default.FormatListNumbered, contentDescription = stringResource(R.string.format_numbered_list))
                }
                IconButton(onClick = { showLinkDialog = true }) {
                    Icon(Icons.Default.Link, contentDescription = stringResource(R.string.link_insert))
                }
            }

            OutlinedTextField(
                value = bodyValue,
                onValueChange = { bodyValue = it; viewModel.updateBody(it.text) },
                label = { Text(stringResource(R.string.message)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default)
            )

            if (showLinkDialog) {
                LinkDialog(
                    onDismiss = { showLinkDialog = false },
                    onConfirm = { url ->
                        showLinkDialog = false
                        apply { RichTextFormatter.link(it, url) }
                    }
                )
            }

            state.error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_insert)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.link_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(url) }) { Text(stringResource(R.string.link_insert_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Copies the picked content Uri into the app cache so JavaMail can attach it by file path. */
private fun copyToCache(context: android.content.Context, uri: Uri): Attachment? {
    return try {
        val resolver = context.contentResolver
        var name = "attachment"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        val outDir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val outFile = File(outDir, name)
        resolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        Attachment(
            fileName = name,
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            size = size,
            localPath = outFile.absolutePath
        )
    } catch (e: Exception) {
        null
    }
}
