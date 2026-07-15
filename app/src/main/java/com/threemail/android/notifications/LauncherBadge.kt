package com.threemail.android.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import com.threemail.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maintains a sticky "unread inbox" notification that:
 *
 *  - lives on a dedicated [NotificationHelper.BADGE_CHANNEL_ID] (IMPORTANCE_LOW +
 *    `setShowBadge(true)`) so the OS surfaces the unread count on the launcher
 *    icon via `NotificationCompat.Builder.setNumber`;
 *  - is intentionally **not** `setOngoing(true)` — launchers ignore ongoing
 *    notifications when computing badge counts. The notification is sticky
 *    only because we re-post the same ID whenever the unread count changes;
 *  - uses `setOnlyAlertOnce(true)` + `setSilent(true)` so we never repeat the
 *    notification sound/vibration when synchronising counts.
 *
 * When the count reaches zero we cancel the notification so the launcher
 * badge disappears.
 */
@Singleton
class LauncherBadge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: BadgeNotifier
) {
    fun setCount(count: Int) {
        if (count <= 0) {
            notifier.cancel()
            return
        }
        val notification = NotificationCompat.Builder(context, NotificationHelper.BADGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.unread_badge_title))
            .setContentText(context.getString(R.string.unread_badge_subtitle, count))
            .setNumber(count)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notifier.post(notification)
    }
}
