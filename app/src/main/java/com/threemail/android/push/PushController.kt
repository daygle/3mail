package com.threemail.android.push

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public surface for the push system. Callers use this to ask the
 * [ImapIdleService] to wire IDLE subscriptions to specific accounts.
 *
 * The service is the source of truth for which accounts are currently being
 * pushed — it reads the accounts table on every `REFRESH`. These methods
 * only enqueue Intent work; the service is in charge of all bookkeeping.
 */
@Singleton
class PushController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Read once — `Context.packageName` is stable for the lifetime of the app. */
    private val packageName: String = context.packageName

    /**
     * Asks the service to refresh its push subscriptions against the current
     * set of accounts. Starts the FGS if one isn't already running.
     */
    fun refresh() = dispatch(buildIntent(ImapIdleService.ACTION_REFRESH))

    /** Subscribes to push for a single account. */
    fun enablePushFor(accountId: Long) =
        dispatch(buildIntent(ImapIdleService.ACTION_START_FOR_ACCOUNT), accountId)

    /** Drops push for a single account. */
    fun disablePushFor(accountId: Long) =
        dispatch(buildIntent(ImapIdleService.ACTION_STOP_FOR_ACCOUNT), accountId)

    /** Stops the foreground service entirely (used on the disabled toggle). */
    fun stopAll() {
        context.stopService(Intent(context, ImapIdleService::class.java))
    }

    private fun dispatch(intent: Intent, accountId: Long? = null) {
        if (accountId != null) intent.putExtra(ImapIdleService.EXTRA_ACCOUNT_ID, accountId)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun buildIntent(action: String): Intent =
        Intent(action).setClassName(packageName, ImapIdleService::class.java.name)

    companion object {
        /**
         * Construct the [Intent] the service receives without needing a
         * [Context], so unit tests can verify action + component without
         * pulling in Robolectric.
         */
        @JvmStatic
        fun buildIntent(packageName: String, action: String): Intent =
            Intent(action).setClassName(packageName, ImapIdleService::class.java.name)
    }
}
