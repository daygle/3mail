package com.threemail.android.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.OutboxRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Drains the outbox queue. Runs whenever Compose enqueues a message (and on
 * WorkManager's exponential backoff after a failure), so a send survives
 * network loss and process death instead of being lost when the immediate call
 * fails.
 *
 * Each message is deleted on success. On failure the attempt is recorded and
 * the worker asks to be retried, unless the message has exceeded [MAX_ATTEMPTS]
 * - at which point it stays in the outbox with its last error rather than
 * retrying forever (e.g. a permanently bad recipient).
 */
@HiltWorker
class SendMailWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val outboxRepository: OutboxRepository,
    private val accountRepository: AccountRepository,
    private val mailRemoteFactory: MailRemoteFactory
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pending = outboxRepository.pending()
        if (pending.isEmpty()) return Result.success()

        var shouldRetry = false
        for (entry in pending) {
            val account = accountRepository.getAccountById(entry.accountId)
            if (account == null) {
                // The account was removed while this message sat in the queue;
                // drop the orphan so it doesn't block the queue forever.
                outboxRepository.delete(entry.id)
                continue
            }

            val error: String? = try {
                val result = mailRemoteFactory.create(account).send(entry.message)
                if (result.isSuccess) {
                    outboxRepository.delete(entry.id)
                    null
                } else {
                    result.exceptionOrNull()?.message ?: "Send failed"
                }
            } catch (e: RecoverableAuthException) {
                // Needs interactive re-auth we can't perform from a worker; leave
                // it queued and let a later foreground re-auth unblock it.
                "Authentication required"
            } catch (e: Exception) {
                e.message ?: "Send failed"
            }

            if (error != null) {
                val attempts = entry.attemptCount + 1
                outboxRepository.recordFailure(entry.id, attempts, error)
                if (attempts < MAX_ATTEMPTS) shouldRetry = true
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}
