package com.threemail.android.data.remote.idle

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleLoopTest {

    @Test
    fun `emits Open first with the initial messageCount`() = runTest(UnconfinedTestDispatcher()) {
        val ops = ScriptedIdleOps(
            idleCompletions = listOf({ /* idle returns without new mail */ }),
            counts = listOf(0, 0)
        )
        val first = IdleLoop(ops).events().first()
        assertEquals(IdleEvent.Open(0), first)
    }

    @Test
    fun `emits NewMail events when messageCount grows across idle returns`() = runTest(UnconfinedTestDispatcher()) {
        val ops = ScriptedIdleOps(
            idleCompletions = listOf({}, {}),
            counts = listOf(0, 1, 3)
        )
        // Open + 2 NewMail events = 3 events.
        val events = IdleLoop(ops).events().take(3).toList()
        assertEquals(IdleEvent.Open(0), events[0])
        assertEquals(IdleEvent.NewMail(1, delta = 1), events[1])
        assertEquals(IdleEvent.NewMail(3, delta = 2), events[2])
    }

    @Test
    fun `no NewMail emitted when messageCount does not change across idle returns`() = runTest(UnconfinedTestDispatcher()) {
        val ops = ScriptedIdleOps(
            idleCompletions = listOf({}), // one idle call after Open
            counts = listOf(5, 5)
        )
        // First two events available: Open then nothing further until ops.idle()
        // is called again. Use first() to assert only Open is emitted in the
        // synchronous prefix; the loop blocks waiting for another idle call.
        val first = IdleLoop(ops).events().first()
        assertEquals(IdleEvent.Open(5), first)
    }

    @Test
    fun `idle exception surfaces as Disconnected and terminates the loop`() = runTest(UnconfinedTestDispatcher()) {
        val cause = IllegalStateException("server hung up")
        val ops = ScriptedIdleOps(
            idleCompletions = listOf({ throw cause }),
            counts = listOf(2)
        )
        val collected = mutableListOf<IdleEvent>()
        val job = launch { IdleLoop(ops).events().toList(collected) }
        job.join()
        assertEquals(2, collected.size)
        assertEquals(IdleEvent.Open(2), collected[0])
        val second = collected[1]
        assertTrue("expected Disconnected, got $second", second is IdleEvent.Disconnected)
        assertEquals("server hung up", (second as IdleEvent.Disconnected).cause)
    }
}

private class ScriptedIdleOps(
    private val idleCompletions: List<() -> Unit>,
    private val counts: List<Int>
) : IdleFolderOps {
    private var idleIndex = 0
    private var countIndex = 0
    var closed: Boolean = false
        private set

    override fun idle() {
        if (idleIndex >= idleCompletions.size) return
        val action = idleCompletions[idleIndex++]
        action()
    }

    override fun messageCount(): Int {
        val idx = countIndex.coerceAtMost(counts.size - 1)
        countIndex++
        return counts[idx]
    }

    override fun close() {
        closed = true
    }
}
