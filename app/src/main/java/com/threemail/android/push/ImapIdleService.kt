package com.threemail.android.push

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.threemail.android.R
import com.threemail.android.data.remote.idle.IdleEvent
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.domain.model.AccountType
import com.threemail.android.notifications.NotificationHelper
import com.threemail.android.sync.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Foreground service that owns one [kotlinx.coroutines.Job] per account that
 * has push enabled. Each job runs [com.threemail.android.data.remote.imap.ImapClient.idle]
 * on its inbox folder and reacts to [IdleEvent]s by triggering immediate sync.
 *
 * Lifecycle:
 *  - Started by [PushController] which kicks intents to start/stop/refresh.
 *  - `START_STICKY` so the OS re-creates it after a kill (we
 *    re-derive the active set from the accounts table in `onStartCommand`
 *    when intent is null).
 *  - `stopSelf` once no accounts remain registered, so the foreground slot
 *    doesn't linger when the user signs out of all accounts.
 */
@AndroidEntryPoint
class ImapIdleService : Service() {

    @Inject lateinit var accountRepository: AccountRepository
    @Inject lateinit var imapClientFactory: ImapClientFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var settingsRepository: SettingsRepository

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO + CoroutineName("ImapIdleService"))

    /**
     * One IDLE connection per watched (account, folder) pair. INBOX is always
     * watched; [com.threemail.android.domain.model.Account.pushFolders] adds
     * extra folders, each getting its own key/connection.
     */
    private data class PushKey(val accountId: Long, val folder: String)

    private val jobs = ConcurrentHashMap<PushKey, Job>()
    private val registered = ConcurrentHashMap.newKeySet<PushKey>()

    override fun onCreate() {
        super.onCreate()
        promoteToForeground(activeCount = 0)
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Bound callers are out of scope; the service is start-only.
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // OS may recreate us with a `null` Intent after a `START_STICKY` kill;
        // in that case we fall back to a refresh so we resume from the DB.
        when (intent?.action) {
            ACTION_START_FOR_ACCOUNT ->
                intent.getLongExtra(EXTRA_ACCOUNT_ID, -1L)
                    .takeIf { it > 0 }?.let(::ensureAccountPush)
            ACTION_STOP_FOR_ACCOUNT ->
                intent.getLongExtra(EXTRA_ACCOUNT_ID, -1L)
                    .takeIf { it > 0 }?.let(::stopAccountPush)
            ACTION_REFRESH, null -> refreshAll()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        jobs.values.forEach { runCatching { it.cancel() } }
        jobs.clear()
        supervisor.cancel()
        super.onDestroy()
    }

    /** INBOX (always) plus any opt-in extra push folders for this account. */
    private fun watchedFolders(account: com.threemail.android.domain.model.Account): List<String> =
        (listOf(INBOX_FOLDER) + account.pushFolders).distinct()

    /**
     * Re-derive the set of (account, folder) targets that should be on push and
     * ensure each has a running IDLE job. The service MUST refresh its push set
     * on `onStartCommand(null)` so that an OS kill + `START_STICKY` recreate
     * doesn't leave us connected to nothing.
     *
     * v1 simplification: takes a snapshot of accounts + settings. Mid-session
     * account changes (sign-in / sign-out, push toggle, push-folder edits)
     * require another [PushController.refresh]/`enablePushFor` call - typically
     * fired from the call site that performed the mutation.
     */
    private fun refreshAll() {
        scope.launch {
            val pushEnabled = settingsRepository.settings.first().pushEnabled
            val candidates = if (!pushEnabled) emptyList() else accountRepository.getAccounts().first()
                .filter {
                    it.accountType == AccountType.IMAP &&
                        it.isActive &&
                        it.syncEnabled &&
                        it.pushEnabled
                }
            // Desired watch set across every eligible account: INBOX + extras.
            val desired = candidates.flatMap { account ->
                watchedFolders(account).map { PushKey(account.id, it) }
            }.toSet()
            registered.clear()
            registered.addAll(desired)
            jobs.keys.filter { it !in desired }.forEach(::stopJob)
            if (desired.isEmpty()) {
                // Nothing to push (push off, or fresh install with no accounts).
                // Stand down instead of holding an empty foreground service.
                Log.d(TAG, "No push targets - standing down")
                maybeStop()
                return@launch
            }
            promoteToForeground(desired.mapTo(HashSet()) { it.accountId }.size)
            desired.forEach(::ensureJob)
        }
    }

    /**
     * Reconcile a single account's watch set: open IDLE for INBOX + its extra
     * push folders, and cancel any connection for a folder it no longer wants.
     * Fired by [PushController.enablePushFor] after the account's push flag or
     * push-folder list changes.
     */
    private fun ensureAccountPush(accountId: Long) {
        scope.launch {
            val account = accountRepository.getAccountById(accountId)
            val pushEnabled = settingsRepository.settings.first().pushEnabled
            val eligible = account != null &&
                account.accountType == AccountType.IMAP &&
                account.isActive &&
                account.syncEnabled &&
                account.pushEnabled &&
                pushEnabled
            val desired = if (eligible && account != null) {
                watchedFolders(account).map { PushKey(accountId, it) }.toSet()
            } else {
                emptySet()
            }
            // Swap this account's registered targets for the freshly-derived set.
            registered.removeIf { it.accountId == accountId }
            registered.addAll(desired)
            jobs.keys
                .filter { it.accountId == accountId && it !in desired }
                .forEach(::stopJob)
            if (desired.isEmpty()) {
                maybeStop()
                return@launch
            }
            desired.forEach(::ensureJob)
            promoteToForeground(distinctAccountCount())
        }
    }

    private fun ensureJob(key: PushKey) {
        jobs[key]?.let { existing ->
            if (existing.isActive) return
        }
        jobs[key] = scope.launch(CoroutineName("idle-${key.accountId}-${key.folder}")) {
            try {
                val account = accountRepository.getAccountById(key.accountId) ?: return@launch
                val client = imapClientFactory.create(account)
                if (!client.supportsIdle()) {
                    Log.w(TAG, "Server for ${account.email} does not advertise IDLE - skipping push")
                    return@launch
                }
                client.idle(key.folder).collect { event ->
                    handleIdleEvent(key, event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "IDLE loop crashed for $key", e)
            } finally {
                jobs.remove(key)
                maybeStop()
            }
        }
        promoteToForeground(distinctAccountCount())
    }

    private fun stopJob(key: PushKey) {
        jobs.remove(key)?.let { runCatching { it.cancel() } }
        promoteToForeground(distinctAccountCount())
        maybeStop()
    }

    private fun stopAccountPush(accountId: Long) {
        registered.removeIf { it.accountId == accountId }
        jobs.keys.filter { it.accountId == accountId }.forEach { key ->
            jobs.remove(key)?.let { runCatching { it.cancel() } }
        }
        promoteToForeground(distinctAccountCount())
        maybeStop()
    }

    /** Distinct accounts currently watched - drives the FGS subtitle count. */
    private fun distinctAccountCount(): Int =
        jobs.keys.mapTo(HashSet()) { it.accountId }.size

    private fun handleIdleEvent(key: PushKey, event: IdleEvent) {
        when (event) {
            is IdleEvent.Open ->
                Log.d(TAG, "IDLE opened for $key (${event.messageCount} messages)")
            is IdleEvent.NewMail -> {
                Log.i(TAG, "IDLE new mail for $key (delta=${event.delta})")
                // Account-level immediate sync; MailSyncWorker deep-syncs INBOX
                // plus this account's push folders, so the folder that fired is
                // fetched (and notified) regardless of which one it was.
                syncScheduler.enqueueImmediateSync(key.accountId)
            }
            is IdleEvent.Disconnected -> {
                Log.w(TAG, "IDLE disconnected for $key: ${event.cause}")
                jobs.remove(key)
                // Best-effort reconnect with a fixed backoff so a momentary
                // network blip doesn't burn the battery looping.
                scope.launch {
                    delay(RECONNECT_BACKOFF_MS)
                    if (key in registered) ensureJob(key)
                }
            }
        }
    }

    private fun promoteToForeground(activeCount: Int) {
        val notification = buildPushNotification(activeCount)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        try {
            ServiceCompat.startForeground(this, PUSH_NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Android 15+ can reject starting a dataSync foreground service from
            // a backgrounded entry point (e.g. the BOOT_COMPLETED receiver).
            // Stand down rather than crash; push resumes next time the app is
            // foregrounded and calls refresh().
            Log.w(TAG, "Unable to enter foreground for push; standing down", e)
            stopSelf()
        }
    }

    private fun buildPushNotification(activeCount: Int): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.PUSH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.idle_service_title))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.idle_service_subtitle, activeCount, activeCount
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun maybeStop() {
        if (jobs.isEmpty() && registered.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    companion object {
        const val ACTION_START_FOR_ACCOUNT = "com.threemail.android.push.START_FOR_ACCOUNT"
        const val ACTION_STOP_FOR_ACCOUNT = "com.threemail.android.push.STOP_FOR_ACCOUNT"
        const val ACTION_REFRESH = "com.threemail.android.push.REFRESH"
        const val EXTRA_ACCOUNT_ID = "accountId"
        const val PUSH_NOTIFICATION_ID = 1004

        /** The always-watched inbox folder serverId. */
        private const val INBOX_FOLDER = "INBOX"

        private const val RECONNECT_BACKOFF_MS = 15_000L
        private const val TAG = "ImapIdleService"
    }
}
