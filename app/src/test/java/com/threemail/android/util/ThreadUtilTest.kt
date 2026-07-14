package com.threemail.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadUtilTest {

    @Test
    fun `uses first reference as thread root`() {
        val id = ThreadUtil.deriveThreadId(
            messageId = "<reply@host>",
            references = "<root@host> <mid@host>",
            inReplyTo = "<mid@host>"
        )
        assertEquals("root@host", id)
    }

    @Test
    fun `falls back to in reply to`() {
        val id = ThreadUtil.deriveThreadId("<reply@host>", null, "<orig@host>")
        assertEquals("orig@host", id)
    }

    @Test
    fun `falls back to own message id`() {
        val id = ThreadUtil.deriveThreadId("<self@host>", null, null)
        assertEquals("self@host", id)
    }

    @Test
    fun `returns null when nothing available`() {
        assertNull(ThreadUtil.deriveThreadId(null, null, null))
    }

    @Test
    fun `original and reply share a thread id`() {
        val original = ThreadUtil.deriveThreadId("<root@host>", null, null)
        val reply = ThreadUtil.deriveThreadId("<reply@host>", "<root@host>", "<root@host>")
        assertEquals(original, reply)
    }
}
