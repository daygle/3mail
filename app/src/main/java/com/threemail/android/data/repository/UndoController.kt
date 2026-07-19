package com.threemail.android.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The kind of action pending undo; drives the snackbar label only. */
enum class UndoKind { ARCHIVE, DELETE, MOVE, SPAM, SPAM_BATCH }

/**
 * App-scoped controller for Gmail-style "undo" of a destructive triage action.
 *
 * The pattern is *deferred commit*: the caller applies the local change
 * (a folder move) immediately so the row leaves the list, then hands us a
 * `commit` closure (the server-side operation) and a `revert` closure (undo the
 * local move). We hold the server op for [WINDOW_MS]; if the user taps Undo we
 * cancel it and run `revert`, otherwise we run `commit`.
 *
 * Deferring the server op is what makes undo reliable on IMAP: a message moved
 * on the server gets a fresh UID, so an immediate move-then-move-back would lose
 * track of it. By delaying the server move until after the undo window, `commit`
 * always operates on the message at its original UID, and `undo` never touches
 * the server at all.
 *
 * Scope is a standalone [SupervisorJob]: the commit must still fire after the
 * originating screen (and its ViewModel scope) is gone.
 */
@Singleton
class UndoController @Inject constructor() {

    data class Pending(
        val kind: UndoKind,
        val commit: suspend () -> Unit,
        val revert: suspend () -> Unit,
        /** Distinguishes successive pendings so a stale timer can't clobber a newer one. */
        val token: Long = nextToken()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending
    private var commitJob: Job? = null

    /**
     * Enqueue an already-locally-applied action. Any still-pending action is
     * committed first (its window ends the moment a new action starts).
     */
    fun enqueue(kind: UndoKind, commit: suspend () -> Unit, revert: suspend () -> Unit) {
        flush()
        val entry = Pending(kind, commit, revert)
        _pending.value = entry
        commitJob = scope.launch {
            delay(WINDOW_MS)
            if (_pending.value?.token == entry.token) {
                _pending.value = null
                runCatching { entry.commit() }
            }
        }
    }

    /** User tapped Undo: cancel the pending server op and revert the local change. */
    fun undo() {
        val entry = _pending.value ?: return
        commitJob?.cancel()
        _pending.value = null
        scope.launch { runCatching { entry.revert() } }
    }

    /** Commit the current pending immediately (e.g. before enqueuing the next one). */
    fun flush() {
        val entry = _pending.value ?: return
        commitJob?.cancel()
        _pending.value = null
        scope.launch { runCatching { entry.commit() } }
    }

    companion object {
        const val WINDOW_MS = 5000L
        private var counter = 0L
        private fun nextToken(): Long = ++counter
    }
}
