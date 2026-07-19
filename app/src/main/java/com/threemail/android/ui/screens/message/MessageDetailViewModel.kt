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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val message: MailMessage? = null,
        val isLoading: Boolean = false,
        val isLoadingBody: Boolean = false,
        val isDeleted: Boolean = false,
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
        val pgpError: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val messageId: Long = savedStateHandle.get<Long>("messageId") ?: 0L

    init {
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
                }
            } catch (e: Exception) {
                _uiState.value = UiState(error = e.message, isLoading = false)
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
}
