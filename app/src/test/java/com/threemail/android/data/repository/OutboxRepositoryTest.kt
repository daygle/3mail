package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.OutboxDao
import com.threemail.android.data.local.entity.OutboxMessageEntity
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Runs under Robolectric because [OutboxRepository] serializes with
 * `org.json`, which is only present on the Android classpath.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxRepositoryTest {

    private class FakeOutboxDao : OutboxDao {
        val stored = mutableListOf<OutboxMessageEntity>()
        private var nextId = 1L

        override suspend fun insert(message: OutboxMessageEntity): Long {
            val withId = message.copy(id = nextId++)
            stored.add(withId)
            return withId.id
        }

        override suspend fun getAll(): List<OutboxMessageEntity> = stored.sortedBy { it.createdAt }

        override fun observeCount(): Flow<Int> = flowOf(stored.size)

        override suspend fun deleteById(id: Long) {
            stored.removeAll { it.id == id }
        }

        override suspend fun recordAttempt(
            id: Long,
            attemptCount: Int,
            lastAttemptAt: Long,
            lastError: String?
        ) {
            val idx = stored.indexOfFirst { it.id == id }
            if (idx >= 0) {
                stored[idx] = stored[idx].copy(
                    attemptCount = attemptCount,
                    lastAttemptAt = lastAttemptAt,
                    lastError = lastError
                )
            }
        }
    }

    @Test
    fun `enqueue then pending round-trips recipients, headers and inline attachment`() = runTest {
        val repo = OutboxRepository(FakeOutboxDao())
        val message = OutgoingMessage(
            to = listOf(EmailAddress("Alice", "alice@example.com")),
            cc = listOf(EmailAddress(address = "carol@example.com")),
            bcc = emptyList(),
            subject = "Hello",
            textBody = "plain body",
            htmlBody = "<p>html body</p>",
            attachments = listOf(
                Attachment(
                    fileName = "logo.png",
                    mimeType = "image/png",
                    size = 1234,
                    localPath = "/data/logo.png",
                    isInline = true,
                    contentId = "logo1"
                )
            ),
            inReplyTo = "<a@b>",
            references = "<a@b> <c@d>"
        )

        val id = repo.enqueue(42L, message)
        val entries = repo.pending()

        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals(id, entry.id)
        assertEquals(42L, entry.accountId)
        assertEquals(message.to, entry.message.to)
        assertEquals(message.cc, entry.message.cc)
        assertEquals("Hello", entry.message.subject)
        assertEquals("plain body", entry.message.textBody)
        assertEquals("<p>html body</p>", entry.message.htmlBody)
        assertEquals("<a@b>", entry.message.inReplyTo)
        assertEquals("<a@b> <c@d>", entry.message.references)

        assertEquals(1, entry.message.attachments.size)
        val attachment = entry.message.attachments.first()
        assertEquals("logo.png", attachment.fileName)
        assertEquals("/data/logo.png", attachment.localPath)
        assertTrue("inline flag should survive the round-trip", attachment.isInline)
        assertEquals("logo1", attachment.contentId)
    }

    @Test
    fun `delete removes the entry from the queue`() = runTest {
        val repo = OutboxRepository(FakeOutboxDao())
        val id = repo.enqueue(
            1L,
            OutgoingMessage(
                to = listOf(EmailAddress(address = "a@b.com")),
                subject = "s",
                textBody = "t"
            )
        )

        repo.delete(id)

        assertTrue(repo.pending().isEmpty())
    }
}
