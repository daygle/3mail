package com.threemail.android.ui.screens.message

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.threemail.android.ui.theme.appTopBarColors
import com.threemail.android.R
import com.threemail.android.data.crypto.SignatureStatus
import com.threemail.android.data.settings.AfterDeleteNavigation
import com.threemail.android.data.settings.TopBarItemId
import com.threemail.android.ui.screens.settings.SettingsViewModel
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.ui.components.LoadingIndicator
import com.threemail.android.ui.theme.avatarColorFor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.io.File

/**
 * Synthetic origin used when loading HTML email bodies in the privacy-locked
 * WebView. Provides a real base URL so remote image fetches carry an origin
 * once the user has opted in to loading images, instead of coming back as
 * bare-null-origin requests that some servers reject.
 */
private const val IMAGE_BASE_URL = "https://email.invalid/"

/**
 * Prefetch radius: how many pages on either side of the current page the
 * VM warms via `ensureLoaded(id)` so the body of the next swipe target is
 * already in the per-page VM cache by the time the user lands on it.
 * The prefetch covers the user's most likely next step; we don't pass
 * `beyondBoundsPageCount` to the [HorizontalPager] itself because that
 * overload isn't on the Compose BOM pinned by this module, and the ViewModel
 * prefetch is the cheaper mechanism anyway (no extra WebView kept alive on
 * top of the current one).
 */
private const val PAGER_PREFETCH_RADIUS = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    viewModel: MessageDetailViewModel,
    onNavigateBack: () -> Unit,
    /**
     * Invoked after a successful delete in **single-message** mode (deep
     * links from Search / notifications, which don't carry folder context)
     * when the user's "After delete" setting is [AfterDeleteNavigation.NEXT_MESSAGE]
     * AND the VM resolved a next-older message in the same folder. The host
     * implementation pops the current detail entry off the back stack before
     * navigating so back from the next screen returns to whatever was before
     * the original (typically search) rather than the just-deleted message.
     *
     * **Pager mode overrides this path**: in pager mode the delete advances
     * by an in-screen `animateScrollToPage` so the user can keep swiping
     * through their inbox without growing the back stack on every delete.
     */
    onNavigateToNext: (Long) -> Unit = {},
    onReply: (Long) -> Unit,
    onReplyAll: (Long) -> Unit,
    onForward: (Long) -> Unit,
    /** Start a fresh compose addressed to the given email address. */
    onComposeTo: (String) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val message = state.message
    val ids by viewModel.adjacentIds.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val context = LocalContext.current
    // Resolved here because stringResource is @Composable and can't be called
    // from inside the file-open LaunchedEffect coroutine below.
    val openWithLabel = stringResource(R.string.open_with)
    val composeToSenderLabel = stringResource(R.string.compose_to_sender)
    // Top-bar customisation: read the hidden set once per recomposition and
    // pass it into the inline actions block. The Hilt-scoped SettingsViewModel
    // shares the singleton DataStore with the one in Settings, so adding a
    // top-bar change in Settings reaches this screen on the next recomposition.
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val appSettings by settingsViewModel.settings.collectAsState()
    val hidden = appSettings.hiddenTopBarItems
    val isHidden: (TopBarItemId) -> Boolean = { id -> id in hidden }
    // Privacy posture: remote images only load when either the user's global
    // preference allows them or the user has tapped "Show images" for this
    // specific message. Either side is enough to flip the WebView off its
    // privacy lockdown, but only the global opt-in is persistent.
    val showImages = state.loadImagesSetting || state.imagesShownForThisMessage
    var menuOpen by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showHeaders by remember { mutableStateOf(false) }

    // Pager wiring (only meaningful when the nav route passed folder context
    // -- ids stays empty in single-message deep-link mode, and the body
    // renders without a HorizontalPager below).
    val pagerMode = ids.isNotEmpty()
    val initialPage = remember(ids) {
        ids.indexOf(selectedId).let { if (it >= 0) it else 0 }
    }
    // Only mount the pager when we have at least one adjacent id beyond the
    // current selection; a 1-page "swipe pager" would be a worse UX than
    // the static Column (no swipe affordance, but the binding is predictable).
    val pagerState = if (pagerMode && ids.size > 1) {
        rememberPagerState(initialPage = initialPage) { ids.size }
    } else {
        null
    }

    // Forward user-driven pager scrolls into the ViewModel so the body
    // (folder list, mark-read, body fetch, decryption, move targets) re-
    // hydrates for the new page. We only fire when the page index actually
    // changes AND the matching id differs from the VM's currently selected
    // one - that last check prevents the loop where the VM swap reacts by
    // emitting an updated ids list, which would re-derive the same currentPage
    // and re-call selectMessage with the same id.
    if (pagerState != null) {
        LaunchedEffect(pagerState, ids) {
            snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
                .filter { (_, scrolling) -> !scrolling }
                .distinctUntilChanged()
                .collect { (page, _) ->
                    val targetId = ids.getOrNull(page) ?: return@collect
                    if (targetId != selectedId) viewModel.selectMessage(targetId)
                }
        }
        // Prefetch the bodies of the two pages flanking the user's likely
        // next swipe so `beyondBoundsPageCount = 1` doesn't have to wait
        // for a 200ms remote fetch when the user lands on the new page.
        LaunchedEffect(pagerState, ids) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    ids.getOrNull(page + 1)?.let(viewModel::ensureLoaded)
                    ids.getOrNull(page - 1)?.let(viewModel::ensureLoaded)
                }
        }
    }

    // Post-delete navigation honours the user's "After delete" preference. In
    // pager mode we drive the advance via `pagerState.animateScrollToPage` so
    // the back stack stays at one entry; in single-message mode we fall
    // through to the host's pop+navigate codepath as before.
    LaunchedEffect(state.isDeleted, appSettings.afterDeleteNavigation) {
        if (!state.isDeleted) return@LaunchedEffect
        val advanceInline = pagerState != null &&
            appSettings.afterDeleteNavigation == AfterDeleteNavigation.NEXT_MESSAGE &&
            state.nextMessageId != null
        if (advanceInline) {
            val nextIdx = ids.indexOf(state.nextMessageId)
            if (nextIdx >= 0 && pagerState != null) {
                pagerState.animateScrollToPage(nextIdx)
            } else {
                // The next id isn't in our scope (e.g. it just got filtered
                // out for some reason) - graceful fallback to back-out.
                onNavigateBack()
            }
            // The `snapshotFlow(pagerState.currentPage)` collector below
            // picks up the new page and calls selectMessage for us, so no
            // further work is needed in this branch.
        } else {
            val goToNext = appSettings.afterDeleteNavigation == AfterDeleteNavigation.NEXT_MESSAGE &&
                state.nextMessageId != null
            if (goToNext) state.nextMessageId?.let(onNavigateToNext) else onNavigateBack()
        }
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
            runCatching { context.startActivity(Intent.createChooser(intent, openWithLabel)) }
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

    if (showHeaders && message != null) {
        // Parsed-field fallback shown while the raw headers load, or if the
        // server fetch fails / returns nothing. Field names are the literal
        // RFC 5322 names, so they are intentionally not localized.
        val parsedFallback = buildList {
            add("From" to message.from.joinToString(", ") { it.toString() })
            if (message.to.isNotEmpty()) add("To" to message.to.joinToString(", ") { it.toString() })
            if (message.cc.isNotEmpty()) add("Cc" to message.cc.joinToString(", ") { it.toString() })
            if (message.bcc.isNotEmpty()) add("Bcc" to message.bcc.joinToString(", ") { it.toString() })
            add("Date" to formatHeaderDate(message.date))
            add("Subject" to message.subject)
            if (message.messageId.isNotBlank()) add("Message-ID" to message.messageId)
        }
        AlertDialog(
            onDismissRequest = { showHeaders = false },
            title = { Text(stringResource(R.string.headers_title)) },
            text = {
                when {
                    state.isLoadingHeaders -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.headers_loading))
                        }
                    }
                    !state.rawHeaders.isNullOrBlank() -> {
                        Text(
                            text = state.rawHeaders!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    else -> {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            parsedFallback.forEach { (label, value) ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHeaders = false }) {
                    Text(stringResource(R.string.headers_close))
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
                    if (!isHidden(TopBarItemId.DETAIL_MARK_UNREAD)) {
                        IconButton(onClick = { viewModel.toggleRead() }) {
                            Icon(Icons.Default.MarkEmailUnread, contentDescription = stringResource(R.string.mark_as_unread))
                        }
                    }
                    if (!isHidden(TopBarItemId.DETAIL_ARCHIVE)) {
                        IconButton(onClick = { viewModel.archive() }) {
                            Icon(Icons.Default.Archive, contentDescription = stringResource(R.string.archive))
                        }
                    }
                    if (!isHidden(TopBarItemId.DETAIL_DELETE)) {
                        IconButton(onClick = { viewModel.delete() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // Always-present overflow items: Move (gated by having
                        // at least one target folder) and Mark Spam (only on
                        // accounts that surface a Spam folder).
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.headers_show)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                            enabled = message != null,
                            onClick = {
                                menuOpen = false
                                viewModel.loadHeaders()
                                showHeaders = true
                            }
                        )
                        val anyHidden =
                            isHidden(TopBarItemId.DETAIL_MARK_UNREAD) ||
                                isHidden(TopBarItemId.DETAIL_ARCHIVE) ||
                                isHidden(TopBarItemId.DETAIL_DELETE)
                        if (anyHidden) {
                            HorizontalDivider()
                        }
                        if (isHidden(TopBarItemId.DETAIL_MARK_UNREAD)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mark_as_unread)) },
                                leadingIcon = { Icon(Icons.Default.MarkEmailUnread, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.toggleRead()
                                }
                            )
                        }
                        if (isHidden(TopBarItemId.DETAIL_ARCHIVE)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.archive)) },
                                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.archive()
                                }
                            )
                        }
                        if (isHidden(TopBarItemId.DETAIL_DELETE)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.delete()
                                }
                            )
                        }
                        val anyBottomHidden = message != null && (
                            isHidden(TopBarItemId.DETAIL_REPLY) ||
                                isHidden(TopBarItemId.DETAIL_REPLY_ALL) ||
                                isHidden(TopBarItemId.DETAIL_FORWARD)
                            )
                        if (anyBottomHidden) {
                            HorizontalDivider()
                        }
                        if (message != null && isHidden(TopBarItemId.DETAIL_REPLY)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reply)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onReply(message.id)
                                }
                            )
                        }
                        if (message != null && isHidden(TopBarItemId.DETAIL_REPLY_ALL)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reply_all)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onReplyAll(message.id)
                                }
                            )
                        }
                        if (message != null && isHidden(TopBarItemId.DETAIL_FORWARD)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.forward)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onForward(message.id)
                                }
                            )
                        }
                    }
                },
                colors = appTopBarColors()
            )
        },
        bottomBar = {
            // Each button is individually show/hideable from Settings -> Top
            // Bar. A hidden button drops off the bottom bar and appears in the
            // top bar's overflow menu instead. When all three are hidden the
            // bar collapses entirely.
            val showReply = !isHidden(TopBarItemId.DETAIL_REPLY)
            val showReplyAll = !isHidden(TopBarItemId.DETAIL_REPLY_ALL)
            val showForward = !isHidden(TopBarItemId.DETAIL_FORWARD)
            if (message != null && (showReply || showReplyAll || showForward)) {
                Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (showReply) {
                            Button(onClick = { onReply(message.id) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.reply))
                            }
                        }
                        if (showReplyAll) {
                            OutlinedButton(onClick = { onReplyAll(message.id) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = stringResource(R.string.reply_all), modifier = Modifier.size(18.dp))
                            }
                        }
                        if (showForward) {
                            OutlinedButton(onClick = { onForward(message.id) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.forward), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val body: @Composable (MailMessage?) -> Unit = { bodyMessage ->
            when {
                state.isLoading && bodyMessage == null -> LoadingIndicator()
                bodyMessage == null -> Text("Message not found", modifier = Modifier.padding(padding))
                else -> MessageBody(
                    message = bodyMessage,
                    showingImages = showImages,
                    shrinkToFit = appSettings.shrinkEmailToFit,
                    state = state,
                    onReply = { onReply(bodyMessage.id) },
                    onReplyAll = { onReplyAll(bodyMessage.id) },
                    onForward = { onForward(bodyMessage.id) },
                    onComposeTo = onComposeTo,
                    viewModel = viewModel,
                    modifier = Modifier.padding(padding)
                )
            }
        }
        if (pagerState != null) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                // We mount one full message-body Column per page. Until
                // `viewModel.selectMessage(targetId)` re-fires in reaction to
                // the snapshotFlow collector above, `state.message` may still
                // hold the previous message; we render the new page's body
                // using whatever `state.message` currently is, even if it's
                // the previous one (the LaunchedEffect below flips it as
                // soon as the new page is hydrated).
                val pageId = ids.getOrNull(page) ?: return@HorizontalPager
                // Avoid an infinite splash while a freshly swiped page is
                // loading its body; show the previous body until ready.
                body(state.message.takeIf { it?.id == pageId })
            }
        } else {
            // Single-message (deep-link) path: identical to the pre-pager
            // behaviour - one Column, no swipe, back goes to the caller.
            body(message)
        }
    }
}

/**
 * The bulk of the per-message UI - subject, sender/recipients, attachment
 * row, PGP banner, decrypted body / HTML body / plain text. Pulled out so
 * the pager can mount one instance per page without duplicating the entire
 * body of [MessageDetailScreen].
 */
@Composable
private fun MessageBody(
    message: MailMessage,
    showingImages: Boolean,
    shrinkToFit: Boolean,
    state: MessageDetailViewModel.UiState,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    onComposeTo: (String) -> Unit,
    viewModel: MessageDetailViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
                val senderAddress = sender?.address?.takeIf { it.isNotBlank() }
                if (senderAddress != null) {
                    Text(
                        text = senderAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onComposeTo(senderAddress) }
                            .semantics {
                                contentDescription = "compose_to_sender"
                            }
                    )
                }
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
                if (!showingImages) {
                    ImagesBlockedBanner(onShowOnce = viewModel::showImagesForThisMessage)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                HtmlEmailContent(
                    html = message.bodyHtml!!,
                    loadImages = showingImages,
                    shrinkToFit = shrinkToFit,
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

/**
 * Format an epoch-millis timestamp as an RFC 5322-style Date header value
 * (e.g. "Mon, 21 Jul 2026 14:05:32 +0100") for the message-headers dialog.
 */
private fun formatHeaderDate(epochMillis: Long): String =
    java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

/**
 * A WebView locked down to render HTML mail bodies safely.
 *
 * - JavaScript is explicitly disabled.
 * - File and content access are disabled.
 * - Mixed content is blocked.
 * - DOM storage is disabled.
 * - Remote image loads are blocked until user explicitly enables them.
 * - Link taps are intercepted and routed to the system browser via [launchExternal].
 */
@Suppress("ViewConstructor")
class SafeWebView(
    context: android.content.Context,
    private val launchExternal: (Uri) -> Unit = { uri ->
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
) : WebView(context) {
    var loadImages: Boolean = false
        set(value) {
            field = value
            settings.loadsImagesAutomatically = value
            settings.blockNetworkImage = !value
        }

    var shrinkToFit: Boolean = true
        set(value) {
            field = value
            settings.useWideViewPort = value
            settings.loadWithOverviewMode = value
            settings.layoutAlgorithm = if (value) {
                android.webkit.WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            } else {
                android.webkit.WebSettings.LayoutAlgorithm.NORMAL
            }
        }

    init {
        settings.javaScriptEnabled = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.domStorageEnabled = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url ?: return false
                if (target.scheme == "http" || target.scheme == "https" || target.scheme == "mailto") {
                    launchExternal(target)
                }
                return true
            }
        }
        loadImages = false
        shrinkToFit = true
    }

    fun loadHtml(html: String) {
        loadDataWithBaseURL(IMAGE_BASE_URL, html, "text/html", "utf-8", null)
    }
}

private val TopBarHeight = 64.dp
private val BottomBarHeight = 64.dp
private val BodyPaddingHeight = 32.dp

@Composable
private fun HtmlEmailContent(
    html: String,
    loadImages: Boolean,
    shrinkToFit: Boolean,
    modifier: Modifier = Modifier
) {
    val containerHeightPx = LocalWindowInfo.current.containerSize.height
    val bodyHeightCap = with(LocalDensity.current) { containerHeightPx.toDp() } -
        TopBarHeight - BottomBarHeight - BodyPaddingHeight
    var webView: SafeWebView? by remember { mutableStateOf(null) }
    AndroidView(
        modifier = modifier.heightIn(max = bodyHeightCap),
        factory = { ctx ->
            SafeWebView(ctx).also {
                webView = it
                it.loadImages = loadImages
                it.shrinkToFit = shrinkToFit
                it.loadHtml(html)
            }
        },
        update = {
            it.loadImages = loadImages
            it.shrinkToFit = shrinkToFit
            it.loadHtml(html)
        }
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
private fun ImagesBlockedBanner(onShowOnce: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.images_blocked_banner_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.images_blocked_banner_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onShowOnce) {
                Text(stringResource(R.string.images_blocked_banner_action))
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
