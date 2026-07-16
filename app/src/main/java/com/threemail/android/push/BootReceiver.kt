package com.threemail.android.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-arms IMAP IDLE push after the device reboots. Without this, the
 * [ImapIdleService] only comes back when the user next opens the app, so push
 * is silently dead from boot until launch.
 *
 * The service re-derives which accounts should be on push from the accounts
 * table, so this receiver just asks [PushController] to refresh - no account
 * id needs to be threaded through the boot Intent.
 *
 * Android 15+ (targetSdk 35) forbids starting a `dataSync` foreground service
 * from a boot broadcast. The start is therefore best-effort: on Android 14 and
 * below it restores push immediately; on 15+ the start may be rejected and push
 * resumes the next time the app is foregrounded (the current behaviour).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var pushController: PushController

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { pushController.refresh() }
            .onFailure { Log.w(TAG, "Boot push refresh failed to enqueue", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
