package com.threemail.android.notifications

import android.app.Notification
import androidx.core.app.NotificationManagerCompat
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction the [com.threemail.android.notifications.LauncherBadge] posts through.
 *
 * Extracted so the badge logic can be unit-tested with an in-memory fake
 * rather than driving the real NotificationManager.
 */
interface BadgeNotifier {
    fun post(notification: Notification)
    fun cancel()
}

@Singleton
class SystemBadgeNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) : BadgeNotifier {
    override fun post(notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS (API 33+) is revocable; if the user has notifications
        // off there's nothing to badge. Guard first, then still catch the race
        // where the grant is pulled between the check and the notify() call so a
        // background badge update never crashes the caller.
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(NotificationHelper.BADGE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked mid-flight; drop the badge silently.
        }
    }

    override fun cancel() {
        NotificationManagerCompat.from(context)
            .cancel(NotificationHelper.BADGE_NOTIFICATION_ID)
    }
}
