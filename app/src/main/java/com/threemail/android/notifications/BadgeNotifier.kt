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
        NotificationManagerCompat.from(context)
            .notify(NotificationHelper.BADGE_NOTIFICATION_ID, notification)
    }

    override fun cancel() {
        NotificationManagerCompat.from(context)
            .cancel(NotificationHelper.BADGE_NOTIFICATION_ID)
    }
}
