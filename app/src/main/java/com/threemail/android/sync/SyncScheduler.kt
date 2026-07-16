package com.threemail.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun schedulePeriodicSync(intervalMinutes: Long = 15, replace: Boolean = false) {
        val interval = intervalMinutes.coerceAtLeast(15) // WorkManager minimum period.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<MailSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun schedulePeriodicCalendarSync(intervalMinutes: Long = 30, replace: Boolean = false) {
        val interval = intervalMinutes.coerceAtLeast(15)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CALENDAR_SYNC_WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Schedules a single TrashCleanupWorker for the given trigger (open / close).
     * ExistingWorkPolicy.REPLACE means a new occurrence replaces the in-flight one
     * so we never queue duplicate cleanups when the app is bounced repeatedly.
     */
    fun enqueueTrashCleanup(trigger: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<TrashCleanupWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_TRIGGER to trigger))
            .build()

        val uniqueName = when (trigger) {
            TrashCleanupWorker.TRIGGER_LAUNCH -> TRASH_LAUNCH_WORK_NAME
            TrashCleanupWorker.TRIGGER_QUIT -> TRASH_QUIT_WORK_NAME
            else -> "${TRASH_LAUNCH_WORK_NAME}_$trigger"
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(CALENDAR_SYNC_WORK_NAME)
    }

    /**
     * Enqueues a [MailSyncWorker] that targets a single account. Used by the
     * IMAP IDLE push service when a new-mail notification arrives so the UI
     * updates without waiting for the next periodic tick.
     *
     * Uses [ExistingWorkPolicy.REPLACE] keyed by account ID so two consecutive
     * push events from the same account collapse - we don't need to spam the
     * server with redundant refreshes.
     */
    fun enqueueImmediateSync(accountId: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MailSyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$IMMEDIATE_SYNC_PREFIX$accountId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        private const val SYNC_WORK_NAME = "threemail_periodic_sync"
        private const val CALENDAR_SYNC_WORK_NAME = "threemail_calendar_periodic_sync"
        private const val TRASH_LAUNCH_WORK_NAME = "threemail_trash_cleanup_launch"
        private const val TRASH_QUIT_WORK_NAME = "threemail_trash_cleanup_quit"
        private const val IMMEDIATE_SYNC_PREFIX = "threemail_immediate_sync_"
        const val KEY_TRIGGER: String = "triggerKey"
        const val KEY_ACCOUNT_ID: String = "accountId"
    }
}
