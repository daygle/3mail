package com.threemail.android.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.domain.model.EmailAddress
import com.threemail.android.domain.model.MailMessage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Locks the new-mail notification content the user relies on: the From address
 * is always shown (prefixed with the display name when present), and the
 * subject stays visible in both the collapsed and expanded layouts.
 *
 * Assertions match across every posted notification rather than picking one by
 * position: [NotificationHelper.showNewMailNotifications] posts a per-message
 * notification AND a group summary, and the shadow manager makes no ordering
 * guarantee between them.
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

    private fun postAll(message: MailMessage): List<Notification> {
        val helper = NotificationHelper(context)
        helper.createNotificationChannels()
        helper.showNewMailNotifications(listOf(message))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(manager).allNotifications
    }

    private fun Notification.title(): String? =
        extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()

    private fun Notification.text(): String? =
        extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()

    private fun Notification.bigText(): String? =
        extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()

    @Test
    fun `title shows display name and email when the sender has a name`() {
        val notifs = postAll(
            message(1, "Lunch?", listOf(EmailAddress("Jane Doe", "jane@example.com")))
        )
        assertTrue(
            "a notification title should read 'Jane Doe <jane@example.com>'",
            notifs.any { it.title() == "Jane Doe <jane@example.com>" }
        )
    }

    @Test
    fun `title falls back to the bare address when there is no display name`() {
        val notifs = postAll(
            message(2, "Ping", listOf(EmailAddress(name = "", address = "bob@example.com")))
        )
        assertTrue(
            "a notification title should be the bare address 'bob@example.com'",
            notifs.any { it.title() == "bob@example.com" }
        )
    }

    @Test
    fun `subject is the collapsed text and leads the expanded text`() {
        val notifs = postAll(
            message(
                3,
                subject = "Quarterly report",
                from = listOf(EmailAddress("Acct", "acct@example.com")),
                bodyPreview = "Please review the attached numbers."
            )
        )
        // Collapsed content text is the subject.
        assertTrue(
            "a notification's collapsed text should be the subject",
            notifs.any { it.text() == "Quarterly report" }
        )
        // Expanded view still leads with the subject, then includes the preview.
        assertTrue(
            "the expanded text should lead with the subject and include the preview",
            notifs.any { n ->
                val big = n.bigText()
                big != null &&
                    big.startsWith("Quarterly report") &&
                    big.contains("Please review the attached numbers.")
            }
        )
    }
}
