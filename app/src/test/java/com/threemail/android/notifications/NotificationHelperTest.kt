package com.threemail.android.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.MailMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Locks the new-mail notification content the user relies on: the From address
 * is always shown (prefixed with the display name when present), and the
 * subject stays visible in both the collapsed and expanded layouts.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun message(
        id: Long,
        subject: String,
        from: List<EmailAddress>,
        bodyPreview: String = ""
    ) = MailMessage(
        id = id,
        accountId = 1L,
        folderId = 1L,
        messageId = "msg-$id",
        subject = subject,
        from = from,
        to = emptyList(),
        date = id,
        bodyPreview = bodyPreview
    )

    private fun postAndGet(message: MailMessage): android.app.Notification {
        val helper = NotificationHelper(context)
        helper.createNotificationChannels()
        helper.showNewMailNotifications(listOf(message))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // showNewMailNotifications posts the per-message notification(s) first,
        // then the group summary, so the first posted notification is the
        // per-message one under test.
        return shadowOf(manager).allNotifications.first()
    }

    private fun android.app.Notification.title(): String? =
        extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()

    private fun android.app.Notification.text(): String? =
        extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()

    private fun android.app.Notification.bigText(): String? =
        extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()

    @Test
    fun `title shows display name and email when the sender has a name`() {
        val notif = postAndGet(
            message(1, "Lunch?", listOf(EmailAddress("Jane Doe", "jane@example.com")))
        )
        assertNotNull(notif)
        assertEquals("Jane Doe <jane@example.com>", notif.title())
    }

    @Test
    fun `title falls back to the bare address when there is no display name`() {
        val notif = postAndGet(
            message(2, "Ping", listOf(EmailAddress(name = "", address = "bob@example.com")))
        )
        assertEquals("bob@example.com", notif.title())
    }

    @Test
    fun `subject is the collapsed text and leads the expanded text`() {
        val notif = postAndGet(
            message(
                3,
                subject = "Quarterly report",
                from = listOf(EmailAddress("Acct", "acct@example.com")),
                bodyPreview = "Please review the attached numbers."
            )
        )
        // Collapsed content text is the subject.
        assertEquals("Quarterly report", notif.text())
        // Expanded view still leads with the subject, then the preview.
        val big = notif.bigText()
        assertNotNull(big)
        assertTrue("expanded text should start with the subject", big!!.startsWith("Quarterly report"))
        assertTrue("expanded text should include the preview", big.contains("Please review the attached numbers."))
    }
}
