package com.threemail.android.ui.screens.message

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.Attachment
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
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val message: MailMessage? = null,
        val isLoading: Boolean = false,
        val isLoadingBody: Boolean = false,
        val isDeleted: Boolean = false,
        val downloadingAttachment: String? = null,
        val openFile: File? = null,
        val error: String? = null
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
                    // Mark read locally + on the server the first time it is opened.
                    if (!it.isRead) mailActions.setRead(it, true)
                    if (it.bodyHtml.isNullOrBlank() && it.bodyPlain.isNullOrBlank()) {
                        fetchBody(it)
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
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isLoadingBody = false, error = it.message)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingBody = false, error = e.message)
            }
        }
    }

    fun toggleStar() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailActions.setStarred(message, !message.isStarred)
            _uiState.value = _uiState.value.copy(message = message.copy(isStarred = !message.isStarred))
        }
    }

    fun toggleRead() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            val newRead = !message.isRead
            mailActions.setRead(message, newRead)
            _uiState.value = _uiState.value.copy(message = message.copy(isRead = newRead))
        }
    }

    fun delete() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailActions.delete(message)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    fun archive() {
        val message = _uiState.value.message ?: return
        viewModelScope.launch {
            mailActions.archive(message)
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
