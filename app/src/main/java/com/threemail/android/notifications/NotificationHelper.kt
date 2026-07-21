package com.threemail.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.threemail.android.MainActivity
import com.threemail.android.R
import com.threemail.android.domain.model.MailMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val CHANNEL_ID = "new_mail_channel"
        const val NOTIFICATION_ID = 1001

        const val TRASH_CHANNEL_ID = "trash_cleanup_channel"
        const val TRASH_NOTIFICATION_ID = 1002

        /**
         * Channel that powers the launcher icon's unread count.
         * - IMPORTANCE_LOW + setShowBadge(true) so launchers (Pixel, Samsung)
         *   display the count without buzzing.
         * - Most launchers ignore FLAG_ONGOING_EVENT when computing badge
         *   counts, which is why the count notification is NOT setOngoing(true).
         *   It stays "sticky" only because we re-post the same ID whenever
         *   the unread count changes.
         */
        const val BADGE_CHANNEL_ID = "unread_badge_channel"
        const val BADGE_NOTIFICATION_ID = 1003

        /** Channel for the IMAP IDLE foreground service. */
        const val PUSH_CHANNEL_ID = "push_channel"

        /** Intent extras shared with [NotificationActionReceiver] and MainActivity. */
        const val EXTRA_MESSAGE_ID = "com.threemail.android.notifications.extra.MESSAGE_ID"
        const val EXTRA_NOTIFICATION_ID = "com.threemail.android.notifications.extra.NOTIFICATION_ID"

        /** Group key that ties the per-message notifications under one summary. */
        private const val NEW_MAIL_GROUP = "com.threemail.android.notifications.NEW_MAIL"

        /** Cap how many individual message notifications we post at once. */
        private const val MAX_INDIVIDUAL_NOTIFICATIONS = 8

        /**
         * Offset added to a message's local row id to derive its notification
         * id, keeping per-message ids clear of the fixed ids 1001-1004.
         */
        private const val NEW_MAIL_ID_OFFSET = 100_000L
    }

    private fun messageNotificationId(messageId: Long): Int =
        (NEW_MAIL_ID_OFFSET + messageId).toInt()

    fun createNotificationChannels() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val mailName = context.getString(R.string.new_mail_notification_channel_name)
        val mailDescription = context.getString(R.string.new_mail_notification_channel_description)
        val mailChannel = NotificationChannel(CHANNEL_ID, mailName, NotificationManager.IMPORTANCE_DEFAULT).apply {
            this.description = mailDescription
        }
        notificationManager.createNotificationChannel(mailChannel)

        val trashName = context.getString(R.string.trash_cleanup_notification_channel_name)
        val trashDescription = context.getString(R.string.trash_cleanup_notification_channel_description)
        val trashChannel = NotificationChannel(TRASH_CHANNEL_ID, trashName, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = trashDescription
        }
        notificationManager.createNotificationChannel(trashChannel)

        val badgeName = context.getString(R.string.unread_badge_channel_name)
        val badgeDescription = context.getString(R.string.unread_badge_channel_description)
        val badgeChannel = NotificationChannel(BADGE_CHANNEL_ID, badgeName, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = badgeDescription
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(badgeChannel)

        val pushName = context.getString(R.string.push_channel_name)
        val pushDescription = context.getString(R.string.push_channel_description)
        val pushChannel = NotificationChannel(PUSH_CHANNEL_ID, pushName, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = pushDescription
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(pushChannel)
    }

    /**
     * Post one notification per new message (tap opens that email; Mark read /
     * Delete action buttons operate on it), grouped under a single summary so
     * the shade stays tidy. Only the most recent [MAX_INDIVIDUAL_NOTIFICATIONS]
     * get their own entry; the summary always reflects the full count.
     */
    fun showNewMailNotifications(messages: List<MailMessage>) {
        if (messages.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val recent = messages.sortedByDescending { it.date }.take(MAX_INDIVIDUAL_NOTIFICATIONS)
        recent.forEach { message ->
            manager.notify(messageNotificationId(message.id), buildMessageNotification(message))
        }
        manager.notify(NOTIFICATION_ID, buildSummaryNotification(messages, recent))
    }

    private fun buildMessageNotification(message: MailMessage): Notification {
        val sender = message.from.firstOrNull()
            ?.let { it.name.ifBlank { it.address } }
            ?: context.getString(R.string.notification_unknown_sender)
        val subject = message.subject.ifBlank {
            context.getString(R.string.notification_no_subject)
        }
        val preview = message.bodyPreview.ifBlank { subject }
        val notificationId = messageNotificationId(message.id)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(sender)
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openMessageIntent(message.id))
            .setAutoCancel(true)
            .setGroup(NEW_MAIL_GROUP)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                0,
                context.getString(R.string.mark_read),
                actionIntent(NotificationActionReceiver.ACTION_MARK_READ, message.id, notificationId)
            )
            .addAction(
                0,
                context.getString(R.string.delete),
                actionIntent(NotificationActionReceiver.ACTION_DELETE, message.id, notificationId)
            )
            .build()
    }

    private fun buildSummaryNotification(
        messages: List<MailMessage>,
        recent: List<MailMessage>
    ): Notification {
        val inbox = NotificationCompat.InboxStyle()
        recent.forEach { message ->
            val sender = message.from.firstOrNull()
                ?.let { it.name.ifBlank { it.address } }
                ?: context.getString(R.string.notification_unknown_sender)
            val subject = message.subject.ifBlank {
                context.getString(R.string.notification_no_subject)
            }
            inbox.addLine("$sender  $subject")
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.new_mail_notification_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.new_mail_notification_body, messages.size, messages.size
                )
            )
            .setStyle(inbox)
            .setContentIntent(openAppIntent())
            .setGroup(NEW_MAIL_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * PendingIntent that brings [MainActivity] to the front (or launches it)
     * when a notification is tapped. `SINGLE_TOP` reuses the existing task so
     * we don't stack a second copy of the app on top of a running instance.
     */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Deep-link PendingIntent that opens [MainActivity] on a specific message. */
    private fun openMessageIntent(messageId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getActivity(
            context,
            messageNotificationId(messageId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Broadcast PendingIntent for a notification action button. */
    private fun actionIntent(action: String, messageId: Long, notificationId: Int): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Shown when the trash-cleanup worker exhausts all retry attempts and still fails for every account. */
    fun showTrashCleanupFailure(accountCount: Int) {
        val notification = NotificationCompat.Builder(context, TRASH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.trash_cleanup_failure_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.trash_cleanup_failure_body, accountCount, accountCount
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(TRASH_NOTIFICATION_ID, notification)
    }
}
