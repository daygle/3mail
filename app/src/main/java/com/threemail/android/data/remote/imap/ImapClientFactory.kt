package com.threemail.android.data.remote.imap

import com.threemail.android.data.remote.gmail.GoogleAuthHelper
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImapClientFactory @Inject constructor(
    private val googleAuthHelper: GoogleAuthHelper
) {

    fun create(account: Account): ImapClient {
        val tokenProvider: suspend () -> String? = when (account.accountType) {
            AccountType.GMAIL -> {
                { googleAuthHelper.getAccessToken(account.email) }
            }
            AccountType.IMAP -> {
                { null }
            }
        }
        return ImapClient(account, tokenProvider)
    }
}
