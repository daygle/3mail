package com.threemail.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.notifications.NotificationHelper
import com.threemail.android.sync.SyncScheduler
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

    @Inject
    lateinit var mailActions: MailActions

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createNotificationChannels()
        syncScheduler.schedulePeriodicSync()

        // Honour "empty trash on open" — best effort, ignore failures.
        appScope.launch {
            runCatching {
                if (settingsRepository.settings.first().emptyTrashOnExit) {
                    mailActions.emptyTrash()
                }
            }
        }
    }

    override val workConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
