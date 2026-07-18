package com.threemail.android.data.remote

import com.threemail.android.data.remote.gmail.GmailApiClient
import com.threemail.android.data.remote.gmail.GmailRemote
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.remote.imap.ImapRemote
import com.threemail.android.data.remote.pop3.Pop3Client
import com.threemail.android.data.remote.pop3.Pop3Remote
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the right [MailRemote] for an account: the native Gmail REST API for
 * Gmail accounts, IMAP/SMTP for everything else. Call sites depend on this factory
 * and never branch on account type themselves.
 */
/**
 * `open` so unit tests in `AddAccountViewModelTest` can subclass it with a
 * stubbed [MailRemote] without pulling in mockito. Production subclasses
 * are not expected - Hilt always wires this concrete class.
 */
@Singleton
open class MailRemoteFactory @Inject constructor(
    private val imapClientFactory: ImapClientFactory,
    private val gmailApiClient: GmailApiClient
) {
    open fun create(account: Account): MailRemote = when (account.accountType) {
        AccountType.GMAIL -> GmailRemote(account, gmailApiClient)
        AccountType.IMAP -> ImapRemote(imapClientFactory.create(account))
        AccountType.POP3 -> Pop3Remote(Pop3Client(account))
    }
}
