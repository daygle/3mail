package com.threemail.android.util

import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.MailMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MailTextTest {

    private fun message() = MailMessage(
        accountId = 1,
        folderId = 1,
        messageId = "<m1@host>",
        subject = "Hello",
        from = listOf(EmailAddress("Alice", "alice@x.com")),
        to = listOf(EmailAddress("Me", "me@x.com"), EmailAddress("Carol", "carol@x.com")),
        cc = listOf(EmailAddress("Dan", "dan@x.com")),
        date = 0L,
        bodyPlain = "Original body"
    )

    @Test
    fun `reply subject only prefixed once`() {
        assertEquals("Re: Hello", MailText.replySubject("Hello"))
        assertEquals("Re: Hello", MailText.replySubject("Re: Hello"))
        assertEquals("RE: Hello", MailText.replySubject("RE: Hello"))
    }

    @Test
    fun `forward subject only prefixed once`() {
        assertEquals("Fwd: Hello", MailText.forwardSubject("Hello"))
        assertEquals("Fwd: Hello", MailText.forwardSubject("Fwd: Hello"))
    }

    @Test
    fun `reply quote contains original body`() {
        val quote = MailText.replyQuote(message())
        assertTrue(quote.contains("> Original body"))
        assertTrue(quote.contains("alice@x.com"))
    }

    @Test
    fun `reply all excludes self and dedupes`() {
        val (to, cc) = MailText.replyAllRecipients(message(), "me@x.com")
        assertTrue(to.any { it.address == "alice@x.com" })
        assertTrue(to.any { it.address == "carol@x.com" })
        assertTrue(to.none { it.address == "me@x.com" })
        assertTrue(cc.any { it.address == "dan@x.com" })
    }

    @Test
    fun `strip html removes tags and decodes entities`() {
        assertEquals("Hi & bye", MailText.stripHtml("<p>Hi &amp; bye</p>"))
    }
}
