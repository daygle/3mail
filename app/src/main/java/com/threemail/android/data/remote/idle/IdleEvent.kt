package com.threemail.android.data.remote.idle

/**
 * Events produced by an IMAP IDLE subscription on a single folder.
 *
 * These are deliberately minimal - callers only need to know that something
 * changed on the server, the rough delta, and (for diagnostics) whether the
 * connection went away so they can decide whether to reconnect.
 */
sealed interface IdleEvent {
    /** First emission right after the folder opens: includes the current message count. */
    data class Open(val messageCount: Int) : IdleEvent

    /**
     * Server signalled new mail (or our periodic interrupt re-armed IDLE and
     * a delivery arrived in the gap). [delta] is `messageCount - lastSeen`.
     */
    data class NewMail(val messageCount: Int, val delta: Int) : IdleEvent

    /**
     * IDLE failed or the server dropped the connection. The caller should
     * reconnect (with backoff) - [cause] is the original exception's message.
     */
    data class Disconnected(val cause: String) : IdleEvent
}
