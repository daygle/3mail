package com.threemail.android.ui.screens.compose

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.data.settings.TopBarItemId
import com.threemail.android.ui.screens.settings.SettingsViewModel
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.Contact
import com.threemail.android.util.RichTextFormatter
import com.threemail.android.util.TextEdit
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    viewModel: ComposeViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // Top-bar customisation. Each hidden entry suppresses its IconButton in
    // the bar and surfaces the same affordance in the MoreVert overflow so
    // power users that hide a button don't lose access to the underlying
    // feature. SettingsViewModel is Hilt-scoped here and shares the singleton
    // DataStore with the Settings screen.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val appSettings by settingsViewModel.settings.collectAsState()
    val hidden = appSettings.hiddenTopBarItems
    val isHidden: (TopBarItemId) -> Boolean = { id -> id in hidden }
    var overflowOpen by remember { mutableStateOf(false) }

    // Send is the only side-effect that auto-navigates away; the user can
    // also stay on the compose screen after Save draft, or hit the back
    // arrow and let ComposeViewModel persist the draft on its own.
    LaunchedEffect(state.isSent) {
        if (state.isSent) onNavigateBack()
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

    // OpenKeychain passphrase / key-selection prompt for encryption.
    val pgpActionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onPgpUserActionResult()
        }
    }
    LaunchedEffect(state.pgpUserAction) {
        state.pgpUserAction?.let { pi ->
            pgpActionLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            copyToCache(context, uri)?.let { viewModel.addAttachment(it) }
        }
    }

    // Photo picker (no permission needed on Android 13+; back-portable to 26 by
    // androidx.activity). The selected image is copied into the app cache so
    // JavaMail can attach it by file path, then surfaced in the body via
    // ![]() (cid:) and registered as an inline Attachment.
    val inlineImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        copyImageForInline(context, uri)?.let { saved ->
            val contentId = UUID.randomUUID().toString().replace("-", "").take(24)
            viewModel.addInlineImage(contentId, saved.fileName, saved.mimeType, saved.size, saved.localPath)
        }
    }

    // READ_CONTACTS request is triggered from the VM only on the user's first
    // interaction with To/Cc/Bcc, not eagerly on mount.
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onContactsPermissionResult(granted) }

    LaunchedEffect(state.requestContactsPermission) {
        if (state.requestContactsPermission) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compose)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (!isHidden(TopBarItemId.COMPOSE_INSERT_IMAGE)) {
                        IconButton(
                            onClick = {
                                inlineImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !state.isSending && !state.isSavingDraft
                        ) {
                            Icon(Icons.Default.Image, contentDescription = stringResource(R.string.insert_image))
                        }
                    }
                    if (!isHidden(TopBarItemId.COMPOSE_ATTACH)) {
                        IconButton(
                            onClick = { attachmentPicker.launch("*/*") },
                            enabled = !state.isSending && !state.isSavingDraft
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = stringResource(R.string.attach))
                        }
                    }
                    if (!isHidden(TopBarItemId.COMPOSE_SAVE_DRAFT)) {
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
                    }
                    // Send remains unconditional; it's the primary action of
                    // this screen and must always be tappable.
                    IconButton(onClick = { viewModel.send() }, enabled = !state.isSending) {
                        if (state.isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                        }
                    }
                    // Overflow only renders when at least one user-customisable
                    // action has been hidden - otherwise the MoreVert button
                    // would just be visual weight with nothing inside.
                    if (isHidden(TopBarItemId.COMPOSE_INSERT_IMAGE) ||
                        isHidden(TopBarItemId.COMPOSE_ATTACH) ||
                        isHidden(TopBarItemId.COMPOSE_SAVE_DRAFT)
                    ) {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options)
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false }
                        ) {
                            if (isHidden(TopBarItemId.COMPOSE_INSERT_IMAGE)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.insert_image)) },
                                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        inlineImagePicker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    }
                                )
                            }
                            if (isHidden(TopBarItemId.COMPOSE_ATTACH)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.attach)) },
                                    leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        attachmentPicker.launch("*/*")
                                    }
                                )
                            }
                            if (isHidden(TopBarItemId.COMPOSE_SAVE_DRAFT)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.save_draft)) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Save,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        overflowOpen = false
                                        viewModel.saveDraft()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = appTopBarColors(),
                scrollBehavior = scrollBehavior
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

            // From (send-as identity) picker: shown only when the account has
            // aliases configured beyond its primary address.
            if (state.identities.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.from_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.identities.forEach { identity ->
                            val label = if (identity.displayName.isBlank()) identity.email
                            else "${identity.displayName} <${identity.email}>"
                            FilterChip(
                                selected = state.selectedIdentity?.email == identity.email &&
                                    state.selectedIdentity?.displayName == identity.displayName,
                                onClick = { viewModel.selectIdentity(identity) },
                                label = { Text(label, maxLines = 1) }
                            )
                        }
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
            ContactSuggestionDropdown(
                visible = state.activeRecipientField == RecipientField.TO && state.contactSuggestions.isNotEmpty(),
                suggestions = state.contactSuggestions,
                onPick = { contact -> viewModel.pickContact(contact, 0) }
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
                ContactSuggestionDropdown(
                    visible = state.activeRecipientField == RecipientField.CC && state.contactSuggestions.isNotEmpty(),
                    suggestions = state.contactSuggestions,
                    onPick = { contact -> viewModel.pickContact(contact, 0) }
                )
                OutlinedTextField(
                    value = state.bcc,
                    onValueChange = viewModel::updateBcc,
                    label = { Text(stringResource(R.string.bcc)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
                    singleLine = true
                )
                ContactSuggestionDropdown(
                    visible = state.activeRecipientField == RecipientField.BCC && state.contactSuggestions.isNotEmpty(),
                    suggestions = state.contactSuggestions,
                    onPick = { contact -> viewModel.pickContact(contact, 0) }
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

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.requestReadReceipt,
                    onClick = { viewModel.toggleReadReceipt() },
                    leadingIcon = {
                        Icon(
                            Icons.Default.MarkEmailRead,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text(stringResource(R.string.request_read_receipt)) }
                )
                if (state.pgpAvailable) {
                    FilterChip(
                        selected = state.encrypt,
                        onClick = { viewModel.toggleEncrypt() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = { Text(stringResource(R.string.encrypt)) }
                    )
                }
            }

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
                            label = {
                                Text(
                                    if (attachment.isInline)
                                        "${attachment.fileName} ${stringResource(R.string.inline)}"
                                    else attachment.fileName,
                                    maxLines = 1
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.remove_attachment),
                                    Modifier.size(16.dp)
                                )
                            }
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
                // Adopt externally-set body (reply/forward prefill or inserted inline image) without clobbering the live edit.
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

@Composable
private fun ContactSuggestionDropdown(
    visible: Boolean,
    suggestions: List<Contact>,
    onPick: (Contact) -> Unit
) {
    if (!visible) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            suggestions.take(5).forEach { contact ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(contact) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        val title = contact.displayName.ifBlank { contact.emails.firstOrNull().orEmpty() }
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                        if (contact.displayName.isNotBlank() && contact.emails.isNotEmpty()) {
                            Text(
                                contact.emails.first(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class CopiedFile(
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val localPath: String
)

/**
 * Streams the picked content Uri into the named cache subdir and returns
 * the file metadata. Used both for regular attachments (subdir "outgoing")
 * and inline images (subdir "inline_images").
 */
// We DO sanitize the ContentProvider display name below (strip path segments
// and reject traversal), but lint's taint analysis can't follow the value
// through those string ops and keeps flagging it - suppress the false positive.
@Suppress("UnsanitizedFilenameFromContentProvider")
private fun copyBytes(context: Context, uri: Uri, subdir: String): CopiedFile? {
    return try {
        val resolver = context.contentResolver
        var name = "file"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        val outDir = File(context.cacheDir, subdir).apply { mkdirs() }
        // Sanitize the ContentProvider-supplied display name: strip any path
        // components and reject traversal segments ("." / "..") so a malicious
        // provider can't escape the cache subdir. Pure string ops (no File(name))
        // so we never build a File from the raw, unsanitized value.
        val safeName = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .let { if (it.isBlank() || it == "." || it == "..") "file" else it }
        val outFile = File(outDir, safeName)
        resolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        CopiedFile(
            fileName = name,
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            size = size,
            localPath = outFile.absolutePath
        )
    } catch (_: Exception) {
        null
    }
}

/** Copies the picked content Uri to a cache file so JavaMail can attach it by path. */
private fun copyToCache(context: Context, uri: Uri): Attachment? =
    copyBytes(context, uri, "outgoing")?.let {
        Attachment(fileName = it.fileName, mimeType = it.mimeType, size = it.size, localPath = it.localPath)
    }

/** Copies a picked image to a separate cache subdir reserved for inline references. */
private fun copyImageForInline(context: Context, uri: Uri): CopiedFile? =
    copyBytes(context, uri, "inline_images")
