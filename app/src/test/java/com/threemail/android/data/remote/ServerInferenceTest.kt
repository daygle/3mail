package com.threemail.android.data.remote

import com.threemail.android.data.remote.imap.ImapClient
import com.threemail.android.data.remote.pop3.Pop3Client
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Domain matching in [ServerHosts] and in the IMAP/POP3 fallback hosts must
 * ignore case: email domains are case-insensitive (RFC 5321 §2.3.5) and so are
 * DNS names (RFC 4343).
 */
class ServerInferenceTest {

    private fun account(
        email: String,
        accountType: AccountType = AccountType.IMAP,
        incomingServer: String? = null,
        outgoingServer: String? = null
    ): Account = Account(
        email = email,
        displayName = "User",
        accountType = accountType,
        incomingServer = incomingServer,
        outgoingServer = outgoingServer
    )

    @Test
    fun `imap fallback hosts match the email domain case-insensitively`() {
        val gmail = ImapClient(account("User@GMAIL.com"))
        assertEquals("imap.gmail.com", gmail.getDefaultServer())
        assertEquals("smtp.gmail.com", gmail.getSmtpServer())

        val outlook = ImapClient(account("User@Outlook.COM"))
        assertEquals("outlook.office365.com", outlook.getDefaultServer())
        assertEquals("smtp.office365.com", outlook.getSmtpServer())

        assertEquals("smtp.office365.com", ImapClient(account("user@HotMail.com")).getSmtpServer())
        assertEquals("imap.mail.me.com", ImapClient(account("user@ICloud.com")).getDefaultServer())
    }

    @Test
    fun `pop3 fallback hosts match the email domain case-insensitively`() {
        val gmail = Pop3Client(account("User@GMAIL.com", AccountType.POP3))
        assertEquals("pop.gmail.com", gmail.incomingServer())
        assertEquals("smtp.gmail.com", gmail.smtpServer())

        val outlook = Pop3Client(account("User@Outlook.COM", AccountType.POP3))
        assertEquals("outlook.office365.com", outlook.incomingServer())
        assertEquals("smtp.office365.com", outlook.smtpServer())
    }

    @Test
    fun `inferred smtp host rewrites the protocol label case-insensitively`() {
        assertEquals("smtp.example.com", ServerHosts.smtpFor("imap.example.com", "imap"))
        assertEquals("smtp.example.com", ServerHosts.smtpFor("IMAP.example.com", "imap"))
        assertEquals("smtp.example.com", ServerHosts.smtpFor("POP.example.com", "pop"))
        assertNull(ServerHosts.smtpFor(null, "imap"))

        assertEquals(
            "smtp.example.com",
            Pop3Client(account("user@example.com", AccountType.POP3, incomingServer = "POP.example.com")).smtpServer()
        )
        assertEquals(
            "smtp.example.com",
            ImapClient(account("user@example.com", incomingServer = "IMAP.example.com")).getSmtpServer()
        )
    }

    @Test
    fun `isDomain compares the domain part only`() {
        assertTrue(ServerHosts.isDomain("user@gmail.com", "gmail.com"))
        assertTrue(ServerHosts.isDomain("User@GMAIL.COM", "gmail.com"))
        assertFalse(ServerHosts.isDomain("gmail.com@example.com", "gmail.com"))
        assertFalse(ServerHosts.isDomain("user@notgmail.com", "gmail.com"))
    }
}
