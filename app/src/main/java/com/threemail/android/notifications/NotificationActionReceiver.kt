package com.threemail.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.repository.OutboxRepository
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.util.MailText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "Mark read" / "Archive" / "Delete" action buttons on a new-mail notification.
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
    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var outboxRepository: OutboxRepository
    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val messageId = intent.getLongExtra(NotificationHelper.EXTRA_MESSAGE_ID, -1L)
        val notificationId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIFICATION_ID, -1)
        if (messageId <= 0L) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val message = mailRepository.getMessageById(messageId)
                val succeeded = if (message == null) {
                    false
                } else {
                    when (action) {
                        ACTION_MARK_READ -> mailActions.setRead(message, true).isSuccess
                        ACTION_ARCHIVE -> mailActions.archive(message).isSuccess
                        ACTION_DELETE -> mailActions.delete(message).isSuccess
                        ACTION_REPLY -> queueReply(intent, message)
                        else -> false
                    }
                }
                if (succeeded && notificationId > 0) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun queueReply(intent: Intent, message: com.threemail.android.domain.model.MailMessage): Boolean {
        val input = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationHelper.REMOTE_INPUT_REPLY)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        val account = accountRepository.getAccountById(message.accountId) ?: return false
        val (to, cc) = MailText.replyAllRecipients(message, account.email)
        if (to.isEmpty() && cc.isEmpty()) return false
        outboxRepository.enqueue(
            account.id,
            OutgoingMessage(
                to = to,
                cc = cc,
                subject = MailText.replySubject(message.subject),
                textBody = input,
                inReplyTo = message.messageId,
                references = message.threadId ?: message.messageId
            )
        )
        syncScheduler.enqueueSendMail()
        return true
    }

    companion object {
        const val ACTION_MARK_READ = "com.threemail.android.notifications.action.MARK_READ"
        const val ACTION_ARCHIVE = "com.threemail.android.notifications.action.ARCHIVE"
        const val ACTION_DELETE = "com.threemail.android.notifications.action.DELETE"
        const val ACTION_REPLY = "com.threemail.android.notifications.action.REPLY"
    }
}
