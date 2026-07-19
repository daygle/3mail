package com.threemail.android.ui.screens.compose

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threemail.android.data.crypto.OpenPgpController
import com.threemail.android.data.crypto.PgpResult
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.ContactRepository
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.repository.OutboxRepository
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.Contact
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.Identity
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
    private val mailRemoteFactory: MailRemoteFactory,
    private val contactRepository: ContactRepository,
    private val outboxRepository: OutboxRepository,
    private val syncScheduler: SyncScheduler,
    private val openPgpController: OpenPgpController,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val accounts: List<Account> = emptyList(),
        val selectedAccount: Account? = null,
        /** Send-as identities available for the selected account (primary first). */
        val identities: List<Identity> = emptyList(),
        val selectedIdentity: Identity? = null,
        /** Whether to request a read receipt for this message. */
        val requestReadReceipt: Boolean = false,
        /** Whether OpenKeychain is installed so the encrypt toggle can be offered. */
        val pgpAvailable: Boolean = false,
        /** Whether to OpenPGP sign+encrypt this message on send (inline PGP). */
        val encrypt: Boolean = false,
        /** Set when OpenKeychain needs the user to act (passphrase / key pick) before we can encrypt. */
        val pgpUserAction: PendingIntent? = null,
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

    /**
     * The signature block currently applied to the body. Tracked so that
     * switching accounts in a fresh "new" compose can swap the signature only
     * when the user hasn't started editing (i.e. the body is still exactly the
     * previously-applied block).
     */
    private var appliedSignatureBlock: String = ""

    /** Last-segment text from active recipient field; debounced then dispatched to the contact repo. */
    private val contactQueries = MutableSharedFlow<String>(extraBufferCapacity = 64)

    init {
        viewModelScope.launch {
            runCatching {
                val accounts = accountRepository.getAccounts().first()
                val self = accounts.firstOrNull()
                val identities = identitiesFor(self)
                val selectedIdentity = identities.firstOrNull()
                // Prefer the identity's own signature, then the account's; a
                // blank value at both layers omits the signature entirely.
                val signature = effectiveSignature(self, selectedIdentity)
                appliedSignatureBlock = signatureBlock(signature)
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
                    identities = identities,
                    selectedIdentity = selectedIdentity,
                    pgpAvailable = openPgpController.isKeychainInstalled(),
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

    /** Identity signature when set, else the account's; blank omits the signature entirely. */
    private fun effectiveSignature(account: Account?, identity: Identity?): String =
        identity?.signature?.takeIf { it.isNotBlank() }
            ?: account?.signature?.takeIf { it.isNotBlank() }
            ?: ""

    /** The account's primary address as the first identity, then its aliases. */
    private fun identitiesFor(account: Account?): List<Identity> {
        if (account == null) return emptyList()
        val primary = Identity(
            displayName = account.displayName,
            email = account.email,
            signature = account.signature
        )
        return listOf(primary) + account.identities
    }

    fun selectAccount(account: Account) {
        val state = _uiState.value
        val identities = identitiesFor(account)
        val selectedIdentity = identities.firstOrNull()
        // On a fresh "new" compose whose body is still the untouched signature
        // block, swap in the newly-selected account's signature so the sender's
        // signature follows the From account. We deliberately don't touch a
        // reply/forward body (which carries a quote) or a body the user has
        // already edited.
        val newBlock = signatureBlock(effectiveSignature(account, selectedIdentity))
        if (mode == "new" && state.body == appliedSignatureBlock) {
            appliedSignatureBlock = newBlock
            _uiState.value = state.copy(
                selectedAccount = account,
                identities = identities,
                selectedIdentity = selectedIdentity,
                body = newBlock
            )
        } else {
            _uiState.value = state.copy(
                selectedAccount = account,
                identities = identities,
                selectedIdentity = selectedIdentity
            )
        }
    }

    /** Pick a send-as identity for this message; swaps the signature like accounts do. */
    fun selectIdentity(identity: Identity) {
        val state = _uiState.value
        val newBlock = signatureBlock(effectiveSignature(state.selectedAccount, identity))
        if (mode == "new" && state.body == appliedSignatureBlock) {
            appliedSignatureBlock = newBlock
            _uiState.value = state.copy(selectedIdentity = identity, body = newBlock)
        } else {
            _uiState.value = state.copy(selectedIdentity = identity)
        }
    }

    fun toggleReadReceipt() {
        _uiState.value = _uiState.value.copy(requestReadReceipt = !_uiState.value.requestReadReceipt)
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
            state.body.endsWith("\n") -> "${state.body}$snippet"
            else -> "${state.body}\n$snippet"
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

    fun toggleEncrypt() {
        _uiState.value = _uiState.value.copy(encrypt = !_uiState.value.encrypt)
    }

    /** Called by the screen after the OpenKeychain user-interaction intent returns; retries the send. */
    fun onPgpUserActionResult() {
        _uiState.value = _uiState.value.copy(pgpUserAction = null)
        send()
    }

    fun send() {
        val account = _uiState.value.selectedAccount ?: run {
            _uiState.value = _uiState.value.copy(error = noAccountMessage())
            return
        }
        _uiState.value = _uiState.value.copy(isSending = true, error = null, recoverableAuthIntent = null, pgpUserAction = null)
        viewModelScope.launch {
            try {
                val identity = _uiState.value.selectedIdentity
                val body = _uiState.value.body
                val base = OutgoingMessage(
                    to = AddressParser.parse(_uiState.value.to),
                    cc = AddressParser.parse(_uiState.value.cc),
                    bcc = AddressParser.parse(_uiState.value.bcc),
                    subject = _uiState.value.subject,
                    textBody = body,
                    htmlBody = Markdown.toHtml(body),
                    attachments = _uiState.value.attachments,
                    inReplyTo = inReplyTo,
                    references = references,
                    fromName = identity?.displayName,
                    fromAddress = identity?.email,
                    requestReadReceipt = _uiState.value.requestReadReceipt
                )

                val outgoing = if (_uiState.value.encrypt && openPgpController.isKeychainInstalled()) {
                    val encrypted = encryptBody(account, base) ?: return@launch
                    encrypted
                } else {
                    base
                }

                // Persist to the outbox and let SendMailWorker deliver it, so a
                // send survives network loss / process death.
                outboxRepository.enqueue(account.id, outgoing)
                syncScheduler.enqueueSendMail()
                _uiState.value = _uiState.value.copy(isSending = false, isSent = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSending = false, error = e.message)
            }
        }
    }

    /**
     * Sign+encrypt the body as inline PGP via OpenKeychain. Recipients are every
     * addressee plus the sender (so the sent copy stays readable). Returns the
     * outgoing message with the armored ciphertext as its plain-text body (and
     * no HTML alternative), or null when the send can't proceed yet - either
     * because OpenKeychain needs user interaction (surfaced via [UiState.pgpUserAction]
     * for the screen to launch, then [onPgpUserActionResult] retries) or an error
     * occurred. Attachments are sent as-is (inline PGP encrypts the body only).
     */
    private suspend fun encryptBody(account: Account, base: OutgoingMessage): OutgoingMessage? {
        val recipients = (base.to + base.cc + base.bcc).map { it.address } + account.email
        return when (val result = openPgpController.signAndEncrypt(base.textBody.toByteArray(), recipients)) {
            is PgpResult.Success -> base.copy(textBody = String(result.data), htmlBody = null)
            is PgpResult.NeedUserInteraction -> {
                _uiState.value = _uiState.value.copy(isSending = false, pgpUserAction = result.pendingIntent)
                null
            }
            is PgpResult.Error -> {
                _uiState.value = _uiState.value.copy(isSending = false, error = result.message)
                null
            }
            PgpResult.Unavailable -> {
                _uiState.value = _uiState.value.copy(isSending = false, error = "OpenKeychain is not available")
                null
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
                val identity = _uiState.value.selectedIdentity
                remote.appendDraft(
                    drafts,
                    OutgoingMessage(
                        to = AddressParser.parse(_uiState.value.to),
                        cc = AddressParser.parse(_uiState.value.cc),
                        bcc = AddressParser.parse(_uiState.value.bcc),
                        subject = _uiState.value.subject,
                        textBody = body,
                        htmlBody = Markdown.toHtml(body),
                        attachments = _uiState.value.attachments,
                        fromName = identity?.displayName,
                        fromAddress = identity?.email
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
