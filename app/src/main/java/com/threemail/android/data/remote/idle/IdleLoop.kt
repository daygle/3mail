package com.threemail.android.data.remote.idle

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs an IMAP IDLE loop on top of an [IdleFolderOps].
 *
 * The loop always:
 * - emits one [IdleEvent.Open] once the folder is ready;
 * - calls [IdleFolderOps.idle] until it returns or [IDLE_RENEW_MS] elapses;
 * - emits a [IdleEvent.NewMail] with the delta and the new [messageCount];
 * - re-enters IDLE.
 *
 * Every iteration's body - including the `messageCount()` reads - runs inside
 * a single `try`/`catch` so that *any* failure surfaces as
 * [IdleEvent.Disconnected]. A leaked exception would otherwise escape the
 * `flow {}` builder and terminate the foreground service's collect block
 * without ever triggering the reconnect branch.
 *
 * The loop is fully cancellable: a coroutine cancellation throws
 * [kotlinx.coroutines.CancellationException], which we re-throw unchanged so
 * structured concurrency cleans up the surrounding scope.
 */
class IdleLoop(private val ops: IdleFolderOps) {

    fun events(): Flow<IdleEvent> = flow {
        // Initial Open - guarded so a broken folder close-on-start still
        // surfaces as Disconnected rather than crashing the flow.
        val initialCount = try {
            ops.messageCount()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(IdleEvent.Disconnected(e.message ?: e::class.simpleName.orEmpty()))
            return@flow
        }
        emit(IdleEvent.Open(initialCount))
        var lastCount = initialCount

        while (currentCoroutineContext().isActive) {
            try {
                // Most servers cap IDLE at 29 minutes (RFC 2177) - guard with
                // a 25-minute ceiling so we re-IDLE before the server drops us.
                val idleReentered = withTimeoutOrNull(IDLE_RENEW_MS) {
                    ops.idle()
                    true
                } ?: false
                val newCount = ops.messageCount()
                val delta = (newCount - lastCount).coerceAtLeast(0)
                if (delta > 0) emit(IdleEvent.NewMail(newCount, delta))
                // If the timeout fired (`idleReentered == false`) we re-armed
                // IDLE on our own - that's an internal detail, not a new-mail
                // signal unless the message count actually changed.
                lastCount = newCount
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                emit(IdleEvent.Disconnected(e.message ?: e::class.simpleName.orEmpty()))
                delay(DISCONNECT_BACKOFF_MS)
                // After the backoff the loop exits; the surrounding service
                // is responsible for reconnecting (it owns retry policy).
                return@flow
            }
        }
    }

    private companion object {
        // Stay under the 29-minute RFC 2177 server timeout.
        const val IDLE_RENEW_MS: Long = 25 * 60 * 1000L
        // Battery-friendly pause after a disconnect so we don't tight-loop.
        const val DISCONNECT_BACKOFF_MS: Long = 5_000L
    }
}
