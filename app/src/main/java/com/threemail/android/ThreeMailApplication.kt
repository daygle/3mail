package com.threemail.android

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.threemail.android.data.local.dao.MessageDao
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.notifications.LauncherBadge
import com.threemail.android.notifications.NotificationHelper
import com.threemail.android.push.PushController
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.sync.TrashCleanupWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ThreeMailApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var accountRepository: AccountRepository

    @Inject
    lateinit var pushController: PushController

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    lateinit var launcherBadge: LauncherBadge

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Used to gate the launch cleanup so warm resumes (config change, returning from
     * another app) do not spam the server. The flag resets naturally when the process
     * restarts, which is exactly when "on open" should fire.
     */
    private var hasRunTrashOnLaunch = false

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createNotificationChannels()
        syncScheduler.schedulePeriodicSync()
        syncScheduler.schedulePeriodicCalendarSync()
        registerTrashCleanup()
        registerPushAndBadge()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun registerTrashCleanup() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (hasRunTrashOnLaunch) return
                hasRunTrashOnLaunch = true
                triggerTrashCleanupIfEnabled(TrashCleanupWorker.TRIGGER_LAUNCH)
            }

            override fun onStop(owner: LifecycleOwner) {
                triggerTrashCleanupIfEnabled(TrashCleanupWorker.TRIGGER_QUIT)
            }
        })
    }

    private fun triggerTrashCleanupIfEnabled(trigger: String) {
        appScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                val enabled = when (trigger) {
                    TrashCleanupWorker.TRIGGER_LAUNCH -> settings.emptyTrashOnLaunch
                    TrashCleanupWorker.TRIGGER_QUIT -> settings.emptyTrashOnQuit
                    else -> false
                }
                if (!enabled) {
                    Log.d(TAG, "Trash cleanup suppressed for trigger=$trigger (toggle off)")
                    return@launch
                }
                Log.i(TAG, "Enqueuing trash cleanup for trigger=$trigger")
                syncScheduler.enqueueTrashCleanup(trigger)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue trash cleanup", e)
            }
        }
    }

    /**
     * Registers IMAP IDLE push for every IMAP account (de-duped by the service)
     * and starts observing the unread inbox count for the launcher badge.
     *
     * Both flows survive process death: the push service is `START_STICKY` and
     * re-derives its subscriptions from the DB on every cold start; the badge
     * Flow absorbs the most recent count as soon as Room emits.
     */
    private fun registerPushAndBadge() {
        runCatching { pushController.refresh() }
            .onFailure { Log.w(TAG, "Push refresh failed to enqueue", it) }
        appScope.launch {
            messageDao.observeTotalUnreadAcrossInboxes()
                .collectLatest { count -> launcherBadge.setCount(count) }
        }
    }

    companion object {
        private const val TAG = "ThreeMailApplication"
    }
}
