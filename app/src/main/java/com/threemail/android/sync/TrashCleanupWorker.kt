package com.threemail.android.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.threemail.android.data.remote.gmail.RecoverableAuthException
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.notifications.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import android.util.Log

@HiltWorker
class TrashCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val accountRepository: AccountRepository,
    private val mailRepository: MailRepository,
    private val mailActions: MailActions,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Only consider this a hard failure after we've had a couple of chances to recover.
        val hardFailureThreshold = 2

        return try {
            val accounts = accountRepository.getAccounts().first()
                .filter { it.isActive && it.syncEnabled }
                .filter { it.accountType == AccountType.GMAIL || it.accountType == AccountType.IMAP }

            if (accounts.isEmpty()) {
                return Result.success()
            }

            var failureCount = 0
            var expungedCount = 0

            accounts.forEach { account ->
                runCatching {
                    val folders = mailRepository.getFoldersOnce(account.id)
                    val trash = folders.firstOrNull { it.type == FolderType.TRASH }
                        ?: throw IllegalStateException("No TRASH folder for ${account.email}")
                    val result = mailActions.emptyTrash(account, trash)
                    result.onSuccess { expungedCount += it }
                    result.getOrThrow()
                }.onFailure { error ->
                    if (error is RecoverableAuthException) throw error
                    failureCount++
                    Log.w(TAG, "Trash cleanup failed for account=${account.id}", error)
                }
            }

            // RecoverableAuthException needs the UI to grant additional consent - propagate by retrying once.
            if (failureCount == accounts.size && runAttemptCount < hardFailureThreshold) {
                Result.retry()
            } else if (failureCount == accounts.size) {
                notificationHelper.showTrashCleanupFailure(failureCount)
                Result.failure()
            } else {
                Log.i(TAG, "Trash cleanup done. expunged=$expungedCount failures=$failureCount/${accounts.size}")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TrashCleanupWorker crashed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TrashCleanupWorker"
        const val TRIGGER_LAUNCH = "launch"
        const val TRIGGER_QUIT = "quit"
    }
}
