package com.threemail.android.ui.screens.compose

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.FolderType
import com.threemail.android.util.AddressParser
import com.threemail.android.util.MailText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val settingsRepository: SettingsRepository,
    private val imapClientFactory: ImapClientFactory,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val selectedAccount: Account? = null,
        val to: String = "",
        val cc: String = "",
        val bcc: String = "",
        val subject: String = "",
        val body: String = "",
        val attachments: List<Attachment> = emptyList(),
        val showCcBcc: Boolean = false,
        val isSending: Boolean = false,
        val isSavingDraft: Boolean = false,
        val isSent: Boolean = false,
        val isDraftSaved: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val mode: String = savedStateHandle.get<String>("mode") ?: "new"
    private val refId: Long = savedStateHandle.get<Long>("refId") ?: -1L

    // Threading headers carried through when replying.
    private var inReplyTo: String? = null
    private var references: String? = null

    init {
        viewModelScope.launch {
            val accounts = accountRepository.getAccounts().first()
            val signature = settingsRepository.settings.first().signature
            val self = accounts.firstOrNull()
            val original = if (refId >= 0) mailRepository.getMessageById(refId) else null

            var to = ""
            var cc = ""
            var subject = ""
            var body = signatureBlock(signature)
            var showCcBcc = false

            if (original != null) {
                inReplyTo = original.messageId
                references = original.messageId
                when (mode) {
                    "reply" -> {
                        to = AddressParser.format(original.from)
                        subject = MailText.replySubject(original.subject)
                        body = signatureBlock(signature) + MailText.replyQuote(original)
                    }
                    "replyAll" -> {
                        val (toList, ccList) = MailText.replyAllRecipients(original, self?.email ?: "")
                        to = AddressParser.format(toList)
                        cc = AddressParser.format(ccList)
                        showCcBcc = ccList.isNotEmpty()
                        subject = MailText.replySubject(original.subject)
                        body = signatureBlock(signature) + MailText.replyQuote(original)
                    }
                    "forward" -> {
                        subject = MailText.forwardSubject(original.subject)
                        body = signatureBlock(signature) + MailText.forwardQuote(original)
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                accounts = accounts,
                selectedAccount = self,
                to = to,
                cc = cc,
                subject = subject,
                body = body,
                showCcBcc = showCcBcc
            )
        }
    }

    private fun signatureBlock(signature: String): String =
        if (signature.isBlank()) "" else "\n\n-- \n$signature"

    fun selectAccount(account: Account) {
        _uiState.value = _uiState.value.copy(selectedAccount = account)
    }

    fun onRecoverableAuthHandled() {
        _uiState.value = _uiState.value.copy(recoverableAuthIntent = null)
    }

    fun toggleCcBcc() {
        _uiState.value = _uiState.value.copy(showCcBcc = !_uiState.value.showCcBcc)
    }

    fun updateTo(value: String) { _uiState.value = _uiState.value.copy(to = value) }
    fun updateCc(value: String) { _uiState.value = _uiState.value.copy(cc = value) }
    fun updateBcc(value: String) { _uiState.value = _uiState.value.copy(bcc = value) }
    fun updateSubject(value: String) { _uiState.value = _uiState.value.copy(subject = value) }
    fun updateBody(value: String) { _uiState.value = _uiState.value.copy(body = value) }

    fun addAttachment(attachment: Attachment) {
        _uiState.value = _uiState.value.copy(attachments = _uiState.value.attachments + attachment)
    }

    fun removeAttachment(attachment: Attachment) {
        _uiState.value = _uiState.value.copy(attachments = _uiState.value.attachments - attachment)
    }

    fun send() {
        val account = _uiState.value.selectedAccount ?: run {
            _uiState.value = _uiState.value.copy(error = "No account selected")
            return
        }
        _uiState.value = _uiState.value.copy(isSending = true, error = null, recoverableAuthIntent = null)
        viewModelScope.launch {
            try {
                val client = imapClientFactory.create(account)
                val result = client.sendMessage(
                    to = AddressParser.parse(_uiState.value.to),
                    cc = AddressParser.parse(_uiState.value.cc),
                    bcc = AddressParser.parse(_uiState.value.bcc),
                    subject = _uiState.value.subject,
                    body = _uiState.value.body,
                    attachments = _uiState.value.attachments,
                    inReplyTo = inReplyTo,
                    references = references
                )
                result.onSuccess {
                    _uiState.value = _uiState.value.copy(isSending = false, isSent = true)
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isSending = false, error = it.message)
                }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(isSending = false, recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSending = false, error = e.message)
            }
        }
    }

    fun saveDraft() {
        val account = _uiState.value.selectedAccount ?: return
        _uiState.value = _uiState.value.copy(isSavingDraft = true, error = null)
        viewModelScope.launch {
            try {
                val folders = mailRepository.getFoldersOnce(account.id)
                val drafts = folders.firstOrNull { it.type == FolderType.DRAFTS }
                if (drafts == null) {
                    _uiState.value = _uiState.value.copy(isSavingDraft = false, error = "No drafts folder available")
                    return@launch
                }
                val client = imapClientFactory.create(account)
                client.appendDraft(
                    draftsServerId = drafts.serverId,
                    to = AddressParser.parse(_uiState.value.to),
                    cc = AddressParser.parse(_uiState.value.cc),
                    bcc = AddressParser.parse(_uiState.value.bcc),
                    subject = _uiState.value.subject,
                    body = _uiState.value.body,
                    attachments = _uiState.value.attachments
                ).onSuccess {
                    _uiState.value = _uiState.value.copy(isSavingDraft = false, isDraftSaved = true)
                }.onFailure {
                    _uiState.value = _uiState.value.copy(isSavingDraft = false, error = it.message)
                }
            } catch (e: RecoverableAuthException) {
                _uiState.value = _uiState.value.copy(isSavingDraft = false, recoverableAuthIntent = e.intent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSavingDraft = false, error = e.message)
            }
        }
    }

    fun retryAfterRecoverableAuth() {
        send()
    }
}
