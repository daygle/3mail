package com.threemail.android

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.notifications.NotificationHelper
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.sync.TrashCleanupWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }

    override val workConfiguration: Configuration
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

    companion object {
        private const val TAG = "ThreeMailApplication"
    }
}
