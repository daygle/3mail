package com.threemail.android.data.remote.idle

/**
 * The subset of [com.sun.mail.imap.IMAPFolder] the IDLE loop touches.
 *
 * Extracted so the loop can be unit-tested with a fake implementation
 * (no real IMAP server required). Production code constructs an
 * anonymous-object adapter around an `IMAPFolder`.
 */
interface IdleFolderOps {
    /**
     * Blocks until the server signals new mail, or returns when the
     * underlying folder sends the periodic alarm that JavaMail uses to
     * retire IDLE after the 29-minute RFC 2177 timeout.
     *
     * Implementations MUST be safe to cancel from another thread —
     * throwing is acceptable; we only rely on the call returning.
     */
    fun idle()

    /** Current message count reported by the folder (mid-IDLE the value is cached). */
    fun messageCount(): Int

    /** Closes the folder. Catches its own exceptions so the loop can clean up reliably. */
    fun close()
}
