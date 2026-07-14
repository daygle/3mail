package com.threemail.android.data.remote.gmail

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.threemail.android.domain.model.Account
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class GmailApiClient @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {

    fun buildService(account: Account): Gmail {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf("https://mail.google.com/")
        )
        credential.selectedAccountName = account.email

        return Gmail.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("3mail")
            .build()
    }
}
