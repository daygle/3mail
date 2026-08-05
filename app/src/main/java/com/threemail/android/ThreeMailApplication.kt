package com.threemail.android

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.threemail.android.data.local.dao.MessageDao
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.notifications.LauncherBadge
import com.threemail.android.notifications.NotificationHelper
import com.threemail.android.sync.SyncScheduler
import com.threemail.android.sync.ThreeMailWorkerFactory
import com.threemail.android.sync.TrashCleanupWorker
import com.threemail.android.widgets.UnreadCountWidget
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltAndroidApp
class ThreeMailApplication : Application(), Configuration.Provider {

    /**
     * Custom factory that owns the (worker class) -> (deps) dispatch for
     * every [androidx.work.CoroutineWorker] in the app. Hilt's own
     * [androidx.hilt.work.HiltWorkerFactory] is unusable on this project's
     * toolchain (KSP 2.3.10 + androidx.hilt 1.4.0), so we wire workers
     * by hand. See [ThreeMailWorkerFactory] for the long version.
     */
    @Inject
    lateinit var workerFactory: ThreeMailWorkerFactory

    /**
     * `by lazy` rather than a getter: WorkManager can initialize itself
     * from a different process (e.g. [androidx.work.impl.background.systemalarm.RescheduleReceiver]
     * firing on a background thread after a kill-restart) and the lazy
     * initialization guarantees we only build the `Configuration` once
     * after [workerFactory] has been injected - rather than risking a
     * half-injected state where the getter fires before Hilt's
     * [android.app.Application.onCreate] hooks run.
     *
     * `by lazy` directly in the override slot is safe: the `Lazy<T>`
     * delegate is hidden behind the property getter; callers see only
     * the built `Configuration`.
     */
    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var accountRepository: AccountRepository

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
    private val legacyTrashMigrationMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createNotificationChannels()
        scheduleMailSyncs()
        syncScheduler.schedulePeriodicCalendarSync()
        registerTrashCleanup()
        registerBadgeObserver()
    }

    /**
     * Schedules the per-account periodic mail syncs. Each account runs on its
     * own cadence - its per-account override when set, otherwise the global
     * default - so this reads both the account list and the default interval
     * before reconciling. Runs off the main thread because it touches Room.
     */
    private fun scheduleMailSyncs() {
        appScope.launch {
            try {
                val accounts = accountRepository.getAccountsOnce()
                val defaultInterval = settingsRepository.settings.first().syncIntervalMinutes
                syncScheduler.reconcileAccountSyncs(accounts, defaultInterval)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule per-account mail syncs", e)
            }
        }
    }

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
                migrateLegacyTrashSettings()
                val accounts = accountRepository.getAccountsOnce()
                val enabledAccounts = accounts.filter { account ->
                    when (trigger) {
                        TrashCleanupWorker.TRIGGER_LAUNCH -> account.emptyTrashOnLaunch
                        TrashCleanupWorker.TRIGGER_QUIT -> account.emptyTrashOnQuit
                        else -> false
                    }
                }
                if (enabledAccounts.isEmpty()) {
                    Log.d(TAG, "Trash cleanup suppressed for trigger=$trigger (no account enabled)")
                    return@launch
                }
                Log.i(TAG, "Enqueuing trash cleanup for trigger=$trigger for ${enabledAccounts.size} account(s)")
                syncScheduler.enqueueTrashCleanup(trigger)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enqueue trash cleanup", e)
            }
        }
    }

    /**
     * One-time compatibility bridge for the v27 global Trash switches. Room
     * cannot read DataStore during its SQL migration, so copy the old values
     * when the app first reaches a lifecycle trigger, then remove the legacy
     * keys. Existing IMAP/Gmail accounts retain the behavior they had before
     * the settings became per-mailbox; POP3 is intentionally excluded because
     * it has no server-side Trash cleanup support.
     */
    private suspend fun migrateLegacyTrashSettings() {
        legacyTrashMigrationMutex.withLock {
            val legacy = settingsRepository.readLegacyTrashSettings() ?: return
            val (onLaunch, onQuit) = legacy
            accountRepository.getAccountsOnce()
                .filter { it.accountType == com.threemail.android.domain.model.AccountType.IMAP ||
                    it.accountType == com.threemail.android.domain.model.AccountType.GMAIL }
                .forEach { account ->
                    accountRepository.setEmptyTrashOnLaunch(account.id, onLaunch)
                    accountRepository.setEmptyTrashOnQuit(account.id, onQuit)
                }
            settingsRepository.clearLegacyTrashSettings()
        }
    }

    /**
     * Starts observing the unread inbox count for the launcher badge.
     *
     * The Flow survives process death: it absorbs the most recent count as soon
     * as Room emits.
     *
     * IMAP IDLE push is intentionally NOT started here. The push service runs as
     * a `dataSync` foreground service, and starting a foreground service from
     * the Application's cold-start path (a non-foreground context) is restricted
     * on API 31+ and can crash the app during the launch/permission window.
     * [MainActivity] refreshes push from its foreground lifecycle instead, and
     * [com.threemail.android.push.BootReceiver] handles the post-boot case.
     */
    private fun registerBadgeObserver() {
        appScope.launch {
            messageDao.observeTotalUnreadAcrossInboxes()
                .collectLatest { count ->
                    launcherBadge.setCount(count)
                    UnreadCountWidget.requestUpdate(this@ThreeMailApplication)
                }
        }
    }

    companion object {
        private const val TAG = "ThreeMailApplication"
    }
}
