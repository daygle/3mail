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
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val registered = ConcurrentHashMap.newKeySet<Long>()

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

    /**
     * Re-derive the set of accounts that should be on push and ensure each
     * one has a running IDLE job. The service MUST refresh its push set on
     * `onStartCommand(null)` so that an OS kill + `START_STICKY` recreate
     * doesn't leave us connected to nothing.
     *
     * v1 simplification: takes a snapshot of accounts + settings. Mid-session
     * account changes (sign-in / sign-out, push toggle) require another
     * [PushController.refresh] call — typically fired from the call site that
     * performed the mutation. A future iteration should subscribe via the
     * existing [kotlinx.coroutines.flow.Flow] APIs to react automatically.
     */
    private fun refreshAll() {
        scope.launch {
            val candidates = accountRepository.getAccounts().first()
                .filter {
                    it.accountType == AccountType.IMAP &&
                        it.isActive &&
                        it.syncEnabled &&
                        it.pushEnabled
                }
            registered.clear()
            registered.addAll(candidates.map { it.id })
            jobs.keys.filter { it !in registered }.forEach(::stopAccountPush)
            val pushEnabled = settingsRepository.settings.first().pushEnabled
            if (!pushEnabled) {
                Log.d(TAG, "Push disabled in settings — refreshing without subscriptions")
                maybeStop()
                return@launch
            }
            promoteToForeground(candidates.size)
            candidates.forEach { ensureAccountPush(it.id) }
        }
    }

    private fun ensureAccountPush(accountId: Long) {
        jobs[accountId]?.let { existing ->
            if (existing.isActive) return
        }
        registered.add(accountId)
        jobs[accountId] = scope.launch(CoroutineName("idle-$accountId")) {
            try {
                val account = accountRepository.getAccountById(accountId) ?: return@launch
                val client = imapClientFactory.create(account)
                if (!client.supportsIdle()) {
                    Log.w(TAG, "Server for ${account.email} does not advertise IDLE — skipping push")
                    return@launch
                }
                client.idle("INBOX").collect { event ->
                    handleIdleEvent(accountId, event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "IDLE loop crashed for account $accountId", e)
            } finally {
                jobs.remove(accountId)
                maybeStop()
            }
        }
        promoteToForeground(jobs.size)
    }

    private fun stopAccountPush(accountId: Long) {
        jobs.remove(accountId)?.let { runCatching { it.cancel() } }
        registered.remove(accountId)
        promoteToForeground(jobs.size)
        maybeStop()
    }

    private fun handleIdleEvent(accountId: Long, event: IdleEvent) {
        when (event) {
            is IdleEvent.Open ->
                Log.d(TAG, "IDLE opened for account $accountId (${event.messageCount} messages)")
            is IdleEvent.NewMail -> {
                Log.i(TAG, "IDLE new mail for account $accountId (delta=${event.delta})")
                syncScheduler.enqueueImmediateSync(accountId)
            }
            is IdleEvent.Disconnected -> {
                Log.w(TAG, "IDLE disconnected for account $accountId: ${event.cause}")
                jobs.remove(accountId)
                // Best-effort reconnect with a fixed backoff so a momentary
                // network blip doesn't burn the battery looping.
                scope.launch {
                    delay(RECONNECT_BACKOFF_MS)
                    if (accountId in registered) ensureAccountPush(accountId)
                }
            }
        }
    }

    private fun promoteToForeground(activeCount: Int) {
        val notification = buildPushNotification(activeCount)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, PUSH_NOTIFICATION_ID, notification, type)
    }

    private fun buildPushNotification(activeCount: Int): Notification {
        return NotificationCompat.Builder(this, NotificationHelper.PUSH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.idle_service_title))
            .setContentText(getString(R.string.idle_service_subtitle, activeCount))
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

        private const val RECONNECT_BACKOFF_MS = 15_000L
        private const val TAG = "ImapIdleService"
    }
}
