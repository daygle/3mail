package com.threemail.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.threemail.android.MainActivity
import com.threemail.android.R
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
    }

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

    fun showNewMailNotification(count: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.new_mail_notification_title))
            .setContentText(
                context.resources.getQuantityString(
                    R.plurals.new_mail_notification_body, count, count
                )
            )
            // Tapping the notification opens the app on its inbox.
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
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
