package com.threemail.android.ui.screens.compose

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.ContactRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.repository.OutboxRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.Contact
import com.threemail.android.domain.model.FolderType
import com.threemail.android.util.AddressParser
import com.threemail.android.util.MailText
import com.threemail.android.util.Markdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Identifies which recipient field the user is currently editing. */
enum class RecipientField { TO, CC, BCC }

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val settingsRepository: SettingsRepository,
    private val mailRemoteFactory: MailRemoteFactory,
    private val contactRepository: ContactRepository,
    private val outboxRepository: OutboxRepository,
    private val syncScheduler: SyncScheduler,
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
        val shouldClose: Boolean = false,
        val error: String? = null,
        val recoverableAuthIntent: Intent? = null,
        val contactSuggestions: List<Contact> = emptyList(),
        val activeRecipientField: RecipientField = RecipientField.TO,
        val contactsPermissionDenied: Boolean = false,
        val contactsPermissionRequested: Boolean = false,
        /** Edge-triggered so the screen can launch the READ_CONTOS dialog once. */
        val requestContactsPermission: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    private val mode: String = savedStateHandle.get<String>("mode") ?: "new"
    private val refId: Long = savedStateHandle.get<Long>("refId") ?: -1L

    // Threading headers carried through when replying.
    private var inReplyTo: String? = null
    private var references: String? = null

    /** Last-segment text from active recipient field; debounced then dispatched to the contact repo. */
    private val contactQueries = MutableSharedFlow<String>(extraBufferCapacity = 64)

    init {
        viewModelScope.launch {
            runCatching {
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
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to load compose draft")
            }
        }

        viewModelScope.launch {
            runCatching {
                contactQueries
                    .debounce(150L)
                    .mapLatest { query ->
                        if (contactRepository.hasPermission()) contactRepository.search(query)
                        else emptyList()
                    }
                    .collect { suggestions ->
                        _uiState.value = _uiState.value.copy(
                            contactSuggestions = suggestions,
                            contactsPermissionDenied = !contactRepository.hasPermission()
                        )
                    }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message ?: "Contact search failed")
            }
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

    fun updateTo(value: String) {
        _uiState.value = _uiState.value.copy(to = value, activeRecipientField = RecipientField.TO)
        scheduleContactSearch(value)
    }

    fun updateCc(value: String) {
        _uiState.value = _uiState.value.copy(cc = value, activeRecipientField = RecipientField.CC)
        scheduleContactSearch(value)
    }

    fun updateBcc(value: String) {
        _uiState.value = _uiState.value.copy(bcc = value, activeRecipientField = RecipientField.BCC)
        scheduleContactSearch(value)
    }

    fun updateSubject(value: String) { _uiState.value = _uiState.value.copy(subject = value) }
    fun updateBody(value: String) { _uiState.value = _uiState.value.copy(body = value) }

    fun addAttachment(attachment: Attachment) {
        _uiState.value = _uiState.value.copy(attachments = _uiState.value.attachments + attachment)
    }

    fun removeAttachment(attachment: Attachment) {
        _uiState.value = _uiState.value.copy(attachments = _uiState.value.attachments - attachment)
    }

    /**
     * Inserts an inline image by appending the markdown snippet `![image](cid:...)`
     * to the body and registering the matching Content-ID attachment so
     * [com.threemail.android.data.remote.MimeBuilder] emits the right
     * multipart/related structure. If a previous inline attachment with the
     * same local path and filename already exists, its content-id is reused so
     * the receiver doesn't get two duplicate MIME parts for the same bytes.
     */
    fun addInlineImage(contentId: String, fileName: String, mimeType: String, size: Long, localPath: String) {
        val state = _uiState.value
        val existing = state.attachments.firstOrNull {
            it.isInline && it.localPath == localPath && it.fileName == fileName && it.contentId != null
        }
        val effectiveContentId = existing?.contentId ?: contentId
        val snippet = "![image](cid:${effectiveContentId}@3mail)"
        val newBody = when {
            state.body.isBlank() -> snippet
            state.body.endsWith("\n") -> "$state.body$snippet"
            else -> "$state.body\n$snippet"
        }
        if (existing != null) {
            _uiState.value = state.copy(body = newBody)
        } else {
            val attachment = Attachment(
                fileName = fileName,
                mimeType = mimeType,
                size = size,
                localPath = localPath,
                isInline = true,
                contentId = effectiveContentId
            )
            _uiState.value = state.copy(
                body = newBody,
                attachments = state.attachments + attachment
            )
        }
    }

    /** Screen callback fired when the OS READ_CONTACTS dialog completes. */
    fun onContactsPermissionResult(granted: Boolean) {
        _uiState.value = _uiState.value.copy(
            requestContactsPermission = false,
            contactsPermissionDenied = !granted
        )
        if (granted) {
            // Immediately search the current active-field last segment so contacts surface without a fresh keystroke.
            val segment = currentRecipientFieldText().substringAfterLast(',').trim()
            contactQueries.tryEmit(segment)
        }
    }

    /**
     * Replaces the last comma-separated segment of the active recipient field
     * with the picked contact's formatted address, then appends ", " so the
     * user can keep typing. Matches Gmail / iOS autocomplete behavior.
     *
     * Display names containing `,` or `;` are stripped of those characters
     * rather than quoted, because [AddressParser.parse] splits on those
     * delimiters unconditionally and wouldn't round-trip a quoted name.
     */
    fun pickContact(contact: Contact, emailIndex: Int = 0) {
        val state = _uiState.value
        val email = contact.emails.getOrNull(emailIndex)?.takeIf { it.isNotBlank() } ?: return
        val safeName = contact.displayName
            .replace(",", "")
            .replace(";", "")
            .replace("\n", " ")
            .trim()
        val formatted = if (safeName.isBlank()) email
        else "${safeName} <${email}>"
        val activeText = currentRecipientFieldText()
        val lastComma = activeText.lastIndexOf(',')
        val newActive = if (lastComma == -1) {
            "$formatted, "
        } else {
            val prefix = activeText.substring(0, lastComma).trimEnd { it == ',' || it == ' ' }
            "$prefix, $formatted, "
        }
        val newTo = if (state.activeRecipientField == RecipientField.TO) newActive else state.to
        val newCc = if (state.activeRecipientField == RecipientField.CC) newActive else state.cc
        val newBcc = if (state.activeRecipientField == RecipientField.BCC) newActive else state.bcc
        _uiState.value = state.copy(
            to = newTo,
            cc = newCc,
            bcc = newBcc
        )
        // Drop any pending suggestions; the next keystroke re-arms the search.
        contactQueries.tryEmit("")
    }

    private fun scheduleContactSearch(fieldText: String) {
        val segment = fieldText.substringAfterLast(',').trim()
        contactQueries.tryEmit(segment)
        if (!contactRepository.hasPermission() && !_uiState.value.contactsPermissionRequested) {
            _uiState.value = _uiState.value.copy(
                contactsPermissionRequested = true,
                requestContactsPermission = true
            )
        }
    }

    private fun currentRecipientFieldText(): String = when (_uiState.value.activeRecipientField) {
        RecipientField.TO -> _uiState.value.to
        RecipientField.CC -> _uiState.value.cc
        RecipientField.BCC -> _uiState.value.bcc
    }

    fun send() {
        val account = _uiState.value.selectedAccount ?: run {
            _uiState.value = _uiState.value.copy(error = noAccountMessage())
            return
        }
        _uiState.value = _uiState.value.copy(isSending = true, error = null, recoverableAuthIntent = null)
        viewModelScope.launch {
            try {
                val body = _uiState.value.body
                // Persist to the outbox and let SendMailWorker deliver it. The
                // send now survives network loss and process death instead of
                // being lost if the immediate SMTP/Gmail call fails.
                outboxRepository.enqueue(
                    account.id,
                    OutgoingMessage(
                        to = AddressParser.parse(_uiState.value.to),
                        cc = AddressParser.parse(_uiState.value.cc),
                        bcc = AddressParser.parse(_uiState.value.bcc),
                        subject = _uiState.value.subject,
                        textBody = body,
                        htmlBody = Markdown.toHtml(body),
                        attachments = _uiState.value.attachments,
                        inReplyTo = inReplyTo,
                        references = references
                    )
                )
                syncScheduler.enqueueSendMail()
                _uiState.value = _uiState.value.copy(isSending = false, isSent = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSending = false, error = e.message)
            }
        }
    }

    fun saveDraft() {
        saveDraftInternal(closeAfter = false)
    }

    fun saveAndClose() {
        saveDraftInternal(closeAfter = true)
    }

    private fun saveDraftInternal(closeAfter: Boolean) {
        val account = _uiState.value.selectedAccount ?: run {
            _uiState.value = _uiState.value.copy(error = noAccountMessage())
            return
        }
        _uiState.value = _uiState.value.copy(isSavingDraft = true, error = null)
        viewModelScope.launch {
            try {
                val folders = mailRepository.getFoldersOnce(account.id)
                val drafts = folders.firstOrNull { it.type == FolderType.DRAFTS }
                if (drafts == null) {
                    _uiState.value = _uiState.value.copy(isSavingDraft = false, error = noDraftsFolderMessage())
                    return@launch
                }
                val remote = mailRemoteFactory.create(account)
                val body = _uiState.value.body
                remote.appendDraft(
                    drafts,
                    OutgoingMessage(
                        to = AddressParser.parse(_uiState.value.to),
                        cc = AddressParser.parse(_uiState.value.cc),
                        bcc = AddressParser.parse(_uiState.value.bcc),
                        subject = _uiState.value.subject,
                        textBody = body,
                        htmlBody = Markdown.toHtml(body),
                        attachments = _uiState.value.attachments
                    )
                ).onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isSavingDraft = false,
                        isDraftSaved = true,
                        shouldClose = closeAfter
                    )
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

    private fun noAccountMessage(): String = "No account selected"
    private fun noDraftsFolderMessage(): String = "No drafts folder available"

    fun retryAfterRecoverableAuth() {
        send()
    }

    fun consumeCloseSignal() {
        _uiState.value = _uiState.value.copy(shouldClose = false)
    }
}
