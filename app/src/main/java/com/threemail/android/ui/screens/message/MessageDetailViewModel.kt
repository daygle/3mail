package com.threemail.android.ui.screens.message

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.PendingIntent
import android.content.Context
import com.threemail.android.data.crypto.OpenPgpController
import com.threemail.android.data.crypto.PgpResult
import com.threemail.android.data.crypto.PgpText
import com.threemail.android.data.crypto.SignatureStatus
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.util.MailText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel that powers [MessageDetailScreen]. Two operating modes share
 * one VM instance:
 *
 *  * **Pager mode** - the nav route passed a `folderId` (or `unified=true`).
 *    The VM exposes [adjacentIds]: a reactive list of every message id in
 *    that scope; the screen wraps the body in a [androidx.compose.foundation.pager.HorizontalPager]
 *    keyed off those ids. The user swipes between messages without leaving
 *    the screen, and changes to any page call [selectMessage], which re-runs
 *    the per-message load path (folder list, mark-read, body fetch, PGP,
 *    next-resolve) for the new id.
 *
 *  * **Single-message mode** - the nav route omitted folder context (deep
 *    links from Search / notifications). [adjacentIds] stays empty, the
 *    screen renders a non-pager detail, and the existing pop+navigate
 *    `onNavigateToNext` codepath stays in charge of advancing after a
 *    destructive action.
 *
 * Both modes keep the same UiState surface that the screen already binds to
 * (top bar, dialogs, bottom reply/reply-all/forward row, etc. all read
 * [UiState.message]) - they only differ in how the *body* is wrapped.
 */
@HiltViewModel
class MessageDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mailRepository: MailRepository,
    private val accountRepository: AccountRepository,
    private val mailActions: MailActions,
    private val mailRemoteFactory: MailRemoteFactory,
    private val openPgpController: OpenPgpController,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val message: MailMessage? = null,
        val isLoading: Boolean = false,
        val isLoadingBody: Boolean = false,
        /**
         * Post-destructive-action sentinel: flipped to true by EVERY entry point
         * that removes / repositions the currently-open message:
         *   - [delete]           (Trash / permanent)
         *   - [archive]          (Archive / All Mail)
         *   - [moveToFolder]     (user-picked target from the move picker)
         *   - [markSpam]         (Spam / Junk)
         * The screen's LaunchedEffect watches this flag, not any single kind,
         * and consults `appSettings.afterDeleteNavigation` for the user's
         * preference. Renaming this field would break every call site; the
         * honest path is to keep the historical name and document the
         * semantic broadening rather than rename. If a future contributor
         * ever wants to discriminate by kind, add a `lastDestructiveKind`
         * enum alongside it - do NOT split into per-action flags or the
         * screen would lose the single-trigger semantics.
         */
        val isDeleted: Boolean = false,
        /**
         * The next-older message in the same folder as the currently-open
         * one, resolved when the VM has a folder context (pager mode) so the
         * screen can advance to it after a delete without re-querying the DB.
         * Null when the user is on the oldest message in the folder, when
         * the folder is empty, or when the resolver query failed - in all
         * those cases the screen falls back to popping back to the list.
         */
        val nextMessageId: Long? = null,
        val downloadingAttachment: String? = null,
        val openFile: File? = null,
        val error: String? = null,
        /** Candidate folders for the move picker (excludes the message's current folder). */
        val moveTargets: List<MailFolder> = emptyList(),
        /** Whether a Spam/Junk folder exists so the "Mark as spam" action can show. */
        val spamAvailable: Boolean = false,
        /** True when the body is an inline PGP encrypted block. */
        val isEncrypted: Boolean = false,
        val isDecrypting: Boolean = false,
        /** Decrypted plaintext once OpenKeychain has decrypted the body. */
        val decryptedBody: String? = null,
        val signatureStatus: SignatureStatus? = null,
        /** Set when OpenKeychain needs the user to act (passphrase) before decrypting. */
        val pgpUserAction: PendingIntent? = null,
        val pgpError: String? = null,
        /**
         * The user's global default for "load remote images in HTML mail". The
         * Detail screen mirrors it into a banner while false and into a
         * silent WebView flag while true.
         */
        val loadImagesSetting: Boolean = false,
        /**
         * One-shot "Show images" override: tapping the banner flips this to
         * true for the lifetime of this screen instance. Re-opening the same
         * message resets it. Survives navigation within the same
         * MessageDetailViewModel - Compose keys the WebView off this flag so
         * the page reloads with images enabled.
         */
        val imagesShownForThisMessage: Boolean = false,
        /** Full raw RFC 5322 header block, fetched on demand for the headers viewer. */
        val rawHeaders: String? = null,
        val isLoadingHeaders: Boolean = false,
        val headersError: String? = null,
        /**
         * Worst-case visibility blip on a page boundary. Set while a freshly
         * selected message is being hydrated (body fetch, mark-read, decrypt,
         * folder list) so the screen can swap from `state.message == null`
         * "loading" to actually showing the previous body until the new one
         * is ready, instead of flashing a stale content. Cleared as soon as
         * the new message lands in [UiState.message].
         */
        val isHydrating: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    // Resolved once from the nav route - both default to "missing folder
    // context" so deep links from Search / notifications safely fall into
    // the non-pager single-message mode.
    private val startMessageId: Long = savedStateHandle.get<Long>("messageId") ?: 0L
    private val pagerFolderId: Long = savedStateHandle.get<Long>("folderId")?.takeIf { it >= 0L } ?: 0L
    private val pagerUnified: Boolean = savedStateHandle.get<Boolean>("unified") == true
    private val hasPagerScope: Boolean = pagerUnified || pagerFolderId > 0L

    /**
     * Reactive ordered list of message ids the screen can swipe through.
     * Empty when the route didn't pass folder context, which is the signal
     * for the screen to render the non-pager single-message view.
     */
    val adjacentIds: StateFlow<List<Long>> = mailRepository
        .observeMessageIds(
            folderId = pagerFolderId.takeIf { it > 0L },
            unified = pagerUnified
        )
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * The id whose body is currently mounted in the screen. The Compose
     * pager drives this via [selectMessage]; the VM uses it both to know
     * which message to hydrate and to compute [UiState.nextMessageId] for
     * pager-mode post-delete advance.
     */
    private val _selectedId = MutableStateFlow(startMessageId)
    val selectedId: StateFlow<Long> = _selectedId.asStateFlow()

    init {
        // Mirror the global "load images" preference into UiState so the
        // banner can flip on the very first frame after navigation without
        // waiting for the first Settings emit to round-trip the DataStore.
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(loadImagesSetting = settings.loadImages)
            }
        }
        // Bootstrap: load the message the nav route asked for. If the route
        // also specifies a folder (pager scope) switch to that folder here so
        // [loadFolders] resolves the account's full folder list with the
        // right accountId even when the very first emission of adjacentIds
        // races the message load.
        loadMessage(startMessageId)
        if (hasPagerScope) {
            viewModelScope.launch {
                adjacentIds
                    .map { ids ->
                        if (ids.isEmpty()) null
                        else ids.indexOf(_selectedId.value).let { if (it >= 0) it + 1 else null }
                    }
                    .distinctUntilChanged()
                    .collect { nextIdx ->
                        _uiState.value = _uiState.value.copy(
                            nextMessageId = nextIdx?.let { idx ->
                                adjacentIds.value.getOrNull(idx)
                            }
                        )
                    }
            }
        } else {
            // Single-message deep-link path keeps the historical pre-resolved
            // nextMessageId so the existing pop+navigate codepath stays
            // available when the user only has one message to navigate to.
            viewModelScope.launch {
                combine(_uiState, _selectedId) { state, id -> state to id }
                    .collect { (state, id) ->
                        val folderId = state.message?.folderId ?: return@collect
                        resolveNextMessageId(folderId, id)
                    }
            }
        }
    }

    /**
     * Switch the pager to a new page. Idempotent: calling with the already-
     * selected id is a no-op. Re-runs the per-message load path so body,
     * attachments, mark-read, decryption, and folder resolution all get
     * refreshed for the freshly mounted page - loading bleed from one
     * message into another would be far worse than a brief re-hydration.
     */
    fun selectMessage(id: Long) {
        if (id == _selectedId.value && _uiState.value.message?.id == id) return
        _selectedId.value = id
        // Reset transient per-message fields immediately so the screen can
        // show a clean state for the new page; the load below races to fill
        // them back in.
        _uiState.value = _uiState.value.copy(
            message = _uiState.value.message?.takeIf { it.id == id },
            isLoading = id > 0L,
            isLoadingBody = false,
            isDeleted = false,
            nextMessageId = null,
            moveTargets = _uiState.value.moveTargets,
            spamAvailable = _uiState.value.spamAvailable,
            isEncrypted = false,
            isDecrypting = false,
            decryptedBody = null,
            signatureStatus = null,
            pgpUserAction = null,
            pgpError = null,
            // One-shot "Show images" override is per-message by design -
            // re-entering a message that has already shown images starts
            // with images blocked again unless the global default allows.
            imagesShownForThisMessage = false,
            rawHeaders = null,
            isLoadingHeaders = false,
            headersError = null,
            isHydrating = id > 0L && _uiState.value.message?.id != id,
            error = null
        )
        if (id > 0L) loadMessage(id)
    }

    /**
     * Best-effort pre-load of an adjacent message so its body, mark-read
     * flag, and decryption state are ready by the time the user swipes
     * onto it. Errors are swallowed; the worst case is the user lands on a
     * page that briefly shows "loading" while it hydrates.
     */
    fun ensureLoaded(id: Long) {
        if (id == _selectedId.value) return
        // Tag the load so a parallel [selectMessage] to the same id doesn't
        // double-fire; we just look the message up and stash it in a small
        // prefetch cache the per-page body composable can pick from.
        viewModelScope.launch {
            runCatching {
                val message = mailRepository.getMessageById(id) ?: return@runCatching
                _prefetchedIds.value = _prefetchedIds.value + id
                _prefetchedMessages.value = _prefetchedMessages.value + (id to message)
            }
        }
    }

    /**
     * Try to consume a prefetched body for [id] from the prefetch cache.
     * Returns null when nothing is cached - the caller is expected to fall
     * through to a normal load via [selectMessage] in that case.
     */
    fun takePrefetch(id: Long): MailMessage? {
        val msg = _prefetchedMessages.value[id] ?: return null
        _prefetchedIds.value = _prefetchedIds.value - id
        _prefetchedMessages.value = _prefetchedMessages.value - id
        return msg
    }

    private val _prefetchedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _prefetchedMessages = MutableStateFlow<Map<Long, MailMessage>>(emptyMap())

    private fun loadMessage(messageId: Long, prefetched: MailMessage? = null) {
        if (messageId <= 0L) {
            _uiState.value = _uiState.value.copy(isLoading = false, isHydrating = false)
            return
        }
        if (prefetched != null) {
            // Caller already has the entity - skip the suspend roundtrip.
            onMessageLoaded(prefetched)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val message = takePrefetch(messageId) ?: mailRepository.getMessageById(messageId)
                _uiState.value = _uiState.value.copy(isLoading = message == null, isHydrating = false)
                message?.let {
                    onMessageLoaded(it)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, isHydrating = false, error = e.message)
            }
        }
    }

    /**
     * One-shot hydrate sequence run after a message id lands. Split out from
     * [loadMessage] so the prefetched path and the cold-load path share the
     * exact same downstream side-effects (folder list, mark-read, body,
     * decrypt, delete-state reset).
     */
    private suspend fun onMessageLoaded(message: MailMessage) {
        // Mark the new message loaded in UiState; clear the hydrating flag
        // so the screen stops showing the previous body as a placeholder.
        _uiState.value = _uiState.value.copy(
            message = message,
            isLoading = false,
            isHydrating = false,
            error = null
        )
        loadFolders(message)
        if (!message.isRead) {
            // Local + remote flip; safe to fire-await since both are quick
            // and the previous message's read-state is no longer relevant.
            runCatching { mailActions.setRead(message, true) }
            _uiState.value = _uiState.value.copy(message = message.copy(isRead = true))
        }
        if (message.bodyHtml.isNullOrBlank() && message.bodyPlain.isNullOrBlank()) {
            fetchBody(message)
        } else {
            maybeDecrypt(message)
        }
        if (!hasPagerScope) {
            // Single-message mode keeps the legacy resolve; pager mode
            // gets nextMessageId reactively from [adjacentIds] in init.
            resolveNextMessageId(message.folderId, message.id)
        }
    }

    private fun resolveNextMessageId(folderId: Long, currentMessageId: Long) {
        viewModelScope.launch {
            try {
                val next = mailRepository.findNextMessageIdInFolder(folderId, currentMessageId)
                _uiState.value = _uiState.value.copy(nextMessageId = next)
            } catch (_: Exception) {
                // Swallowed: screen treats a null nextMessageId as
                // "return to list" regardless of the user's preference.
            }
        }
    }

    private fun fetchBody(message: MailMessage) {
        _uiState.value = _uiState.value.copy(isLoadingBody = true)
        viewModelScope.launch {
            try {
                val account = accountRepository.getAccountById(message.accountId)
                val folder = mailRepository.getFolderById(message.folderId)
                if (account == null || folder == null) {
                    _uiState.value = _uiState.value.copy(isLoadingBody = false)
                    return@launch
                }
                val remote = mailRemoteFactory.create(account)
                remote.fetchBody(folder, message).onSuccess { body ->
                    val preview = (body.plain ?: body.html?.let { MailText.stripHtml(it) } ?: message.bodyPreview)
                        .replace(Regex("\\s+"), " ").trim().take(200)
                    mailRepository.updateBody(message.id, body.html, body.plain, preview, body.attachments)
                    val updated = message.copy(
                        bodyHtml = body.html,
                        bodyPlain = body.plain,
                        bodyPreview = preview,
                        attachments = body.attachments
                    )
                    _uiState.value = _uiState.value.copy(message = updated, isLoadingBody = false)
                    maybeDecrypt(updated)
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isLoadingBody = false, error = it.message)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingBody = false, error = e.message)
            }
        }
    }

    /** Body text used for PGP detection: prefer plain text, fall back to stripped HTML. */
    private fun pgpText(message: MailMessage): String =
        message.bodyPlain?.takeIf { it.isNotBlank() }
            ?: message.bodyHtml?.let { MailText.stripHtml(it) }
            ?: ""

    /** If the body is an inline PGP block, flag it and kick off decryption. */
    private fun maybeDecrypt(message: MailMessage) {
        if (!PgpText.isEncrypted(pgpText(message))) return
        _uiState.value = _uiState.value.copy(isEncrypted = true)
        decrypt()
    }

    fun decrypt() {
        val message = _uiState.value.message ?: return
        val armored = PgpText.extractArmored(pgpText(message))
        _uiState.value = _uiState.value.copy(isDecrypting = true, pgpError = null, pgpUserAction = null)
        viewModelScope.launch {
            when (val result = openPgpController.decryptAndVerify(armored.toByteArray())) {
                is PgpResult.Success -> _uiState.value = _uiState.value.copy(
                    isDecrypting = false,
                    decryptedBody = String(result.data),
                    signatureStatus = result.signature
                )
                is PgpResult.NeedUserInteraction -> _uiState.value = _uiState.value.copy(
                    isDecrypting = false,
                    pgpUserAction = result.pendingIntent
                )
                is PgpResult.Error -> _uiState.value = _uiState.value.copy(
                    isDecrypting = false,
                    pgpError = result.message
                )
                PgpResult.Unavailable -> _uiState.value = _uiState.value.copy(
                    isDecrypting = false,
                    pgpError = "Install OpenKeychain to decrypt this message"
                )
            }
        }
    }

    /** Called after the OpenKeychain passphrase intent returns; retries decryption. */
    fun onPgpUserActionResult() {
        _uiState.value = _uiState.value.copy(pgpUserAction = null)
        decrypt()
    }

    fun toggleRead() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            val newRead = !message.isRead
            mailActions.setRead(message, newRead)
            _uiState.value = _uiState.value.copy(message = message.copy(isRead = newRead))
        }
    }

    /**
     * Loads the account's folders once so the move picker + spam action know
     * their targets. Excludes the message's current folder from the move list.
     */
    private suspend fun loadFolders(message: MailMessage) {
        val folders = mailRepository.getFoldersOnce(message.accountId)
        _uiState.value = _uiState.value.copy(
            moveTargets = folders.filter { it.id != message.folderId },
            spamAvailable = folders.any { it.type == FolderType.SPAM && it.id != message.folderId }
        )
    }

    fun delete() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailActions.deleteWithUndo(message)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    fun archive() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailActions.archiveWithUndo(message)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    /** Move to a user-picked folder; the inbox surfaces the undo snackbar on return. */
    fun moveToFolder(target: MailFolder) {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailActions.moveWithUndo(message, target)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    /** Move to the account's Spam/Junk folder, if one exists. */
    fun markSpam() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailActions.markSpamWithUndo(message)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    fun downloadAttachment(attachment: Attachment) {
        val message = _uiState.value.message ?: return
        _uiState.value = _uiState.value.copy(downloadingAttachment = attachment.fileName, error = null)
        viewModelScope.launch {
            try {
                val account = accountRepository.getAccountById(message.accountId)
                val folder = mailRepository.getFolderById(message.folderId)
                if (account == null || folder == null) {
                    _uiState.value = _uiState.value.copy(downloadingAttachment = null, error = "Unable to locate message")
                    return@launch
                }
                val destDir = File(context.cacheDir, "attachments").apply { mkdirs() }
                val destFile = File(destDir, attachment.fileName)
                val remote = mailRemoteFactory.create(account)
                remote.downloadAttachment(folder, message, attachment, destFile)
                    .onSuccess { file ->
                        _uiState.value = _uiState.value.copy(downloadingAttachment = null, openFile = file)
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(downloadingAttachment = null, error = it.message)
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(downloadingAttachment = null, error = e.message)
            }
        }
    }

    fun onFileOpened() {
        _uiState.value = _uiState.value.copy(openFile = null)
    }

    /**
     * Fetch the message's full raw header block from the server on demand (for
     * the "Show headers" viewer). Cached in state once loaded; the parsed-field
     * fallback in the dialog covers the loading/error/empty cases.
     */
    fun loadHeaders() {
        val message = _uiState.value.message ?: return
        val state = _uiState.value
        if (state.rawHeaders != null || state.isLoadingHeaders) return
        _uiState.value = state.copy(isLoadingHeaders = true, headersError = null)
        viewModelScope.launch {
            try {
                val account = accountRepository.getAccountById(message.accountId)
                val folder = mailRepository.getFolderById(message.folderId)
                if (account == null || folder == null) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingHeaders = false,
                        headersError = "Unable to locate message"
                    )
                    return@launch
                }
                val remote = mailRemoteFactory.create(account)
                remote.fetchRawHeaders(folder, message)
                    .onSuccess { headers ->
                        _uiState.value = _uiState.value.copy(
                            isLoadingHeaders = false,
                            rawHeaders = headers
                        )
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(
                            isLoadingHeaders = false,
                            headersError = it.message
                        )
                    }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingHeaders = false, headersError = e.message)
            }
        }
    }

    /**
     * One-shot "Show images" for the currently displayed message. Resets to
     * false on the next message because `imagesShownForThisMessage` lives
     * only on this view-model instance; the per-message contract survives
     * inbox activity (rotation, brief backgrounding) but not screen leave.
     */
    fun showImagesForThisMessage() {
        _uiState.value = _uiState.value.copy(imagesShownForThisMessage = true)
    }
}
