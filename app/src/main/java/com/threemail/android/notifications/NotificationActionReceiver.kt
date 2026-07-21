package com.threemail.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "Mark read" / "Delete" action buttons on a new-mail notification.
 *
 * Invoked via an explicit-component PendingIntent (see [NotificationHelper]), so
 * it needs no intent-filter and stays `exported="false"`. Hilt injects the mail
 * layer; the work runs off the main thread via [goAsync] + a coroutine, and the
 * matching notification is cancelled once the action completes.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var mailActions: MailActions
    @Inject lateinit var mailRepository: MailRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val messageId = intent.getLongExtra(NotificationHelper.EXTRA_MESSAGE_ID, -1L)
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, -1)
        if (messageId <= 0L) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val message = mailRepository.getMessageById(messageId)
                if (message != null) {
                    when (action) {
                        ACTION_MARK_READ -> mailActions.setRead(message, true)
                        ACTION_DELETE -> mailActions.delete(message)
                    }
                }
            } finally {
                if (notificationId > 0) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.threemail.android.notifications.action.MARK_READ"
        const val ACTION_DELETE = "com.threemail.android.notifications.action.DELETE"
    }
}
