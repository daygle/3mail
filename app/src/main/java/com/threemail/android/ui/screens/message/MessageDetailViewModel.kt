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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

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
         * and consults [appSettings.afterDeleteNavigation] for the user's
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
         * one, resolved once at load time so the screen can advance to it
         * after a delete without re-querying the DB. Null when the user is
         * on the oldest message in the folder, when the folder is empty,
         * or when the resolver query failed - in all those cases the
         * screen falls back to popping back to the list.
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
        val headersError: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val messageId: Long = savedStateHandle.get<Long>("messageId") ?: 0L

    init {
        // Mirror the global "load images" preference into UiState so the
        // banner can flip on the very first frame after navigation without
        // waiting for the first Settings emit to round-trip the DataStore.
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(loadImagesSetting = settings.loadImages)
            }
        }
        loadMessage(messageId)
    }

    private fun loadMessage(messageId: Long) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val message = mailRepository.getMessageById(messageId)
                _uiState.value = UiState(message = message, isLoading = false)
                message?.let {
                    loadFolders(it)
                    // Mark read locally + on the server the first time it is opened.
                    if (!it.isRead) mailActions.setRead(it, true)
                    if (it.bodyHtml.isNullOrBlank() && it.bodyPlain.isNullOrBlank()) {
                        fetchBody(it)
                    } else {
                        maybeDecrypt(it)
                    }
                    // Resolve the post-delete "go to next" target now so the
                    // screen's LaunchedEffect fires instantly on a delete
                    // tap without a fresh DB roundtrip. Best-effort: a
                    // thrown exception leaves `nextMessageId` null, which
                    // the screen treats as "fall back to list".
                    resolveNextMessageId(it.folderId, messageId)
                }
            } catch (e: Exception) {
                _uiState.value = UiState(error = e.message, isLoading = false)
            }
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
