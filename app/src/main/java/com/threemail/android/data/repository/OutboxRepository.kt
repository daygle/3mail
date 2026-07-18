package com.threemail.android.data.repository

import com.threemail.android.data.local.dao.OutboxDao
import com.threemail.android.data.local.entity.OutboxMessageEntity
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.EmailAddress
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** An outbox row paired with its decoded [OutgoingMessage], ready to send. */
data class OutboxEntry(
    val id: Long,
    val accountId: Long,
    val attemptCount: Int,
    val message: OutgoingMessage
)

/**
 * Persists outgoing mail so a send survives failure / process death. Compose
 * enqueues here; [com.threemail.android.sync.SendMailWorker] drains the queue.
 *
 * Recipients and attachments are stored as JSON, mirroring how `MailRepository`
 * persists `MessageEntity` address lists. Unlike that path, the attachment
 * serialization here also carries `isInline` / `contentId` so inline images
 * survive the round-trip.
 */
@Singleton
class OutboxRepository @Inject constructor(
    private val outboxDao: OutboxDao
) {

    /** Persists [message] for [accountId] and returns the new outbox row id. */
    suspend fun enqueue(accountId: Long, message: OutgoingMessage): Long =
        outboxDao.insert(
            OutboxMessageEntity(
                accountId = accountId,
                toJson = serializeAddresses(message.to),
                ccJson = serializeAddresses(message.cc),
                bccJson = serializeAddresses(message.bcc),
                subject = message.subject,
                textBody = message.textBody,
                htmlBody = message.htmlBody,
                attachmentsJson = serializeAttachments(message.attachments),
                inReplyTo = message.inReplyTo,
                references = message.references,
                fromName = message.fromName,
                fromAddress = message.fromAddress,
                requestReadReceipt = message.requestReadReceipt
            )
        )

    suspend fun pending(): List<OutboxEntry> = outboxDao.getAll().map { it.toEntry() }

    fun observeCount(): Flow<Int> = outboxDao.observeCount()

    suspend fun delete(id: Long) = outboxDao.deleteById(id)

    suspend fun recordFailure(id: Long, attemptCount: Int, error: String?) =
        outboxDao.recordAttempt(id, attemptCount, System.currentTimeMillis(), error)

    private fun OutboxMessageEntity.toEntry(): OutboxEntry = OutboxEntry(
        id = id,
        accountId = accountId,
        attemptCount = attemptCount,
        message = OutgoingMessage(
            to = parseAddresses(toJson),
            cc = parseAddresses(ccJson),
            bcc = parseAddresses(bccJson),
            subject = subject,
            textBody = textBody,
            htmlBody = htmlBody,
            attachments = parseAttachments(attachmentsJson),
            inReplyTo = inReplyTo,
            references = references,
            fromName = fromName,
            fromAddress = fromAddress,
            requestReadReceipt = requestReadReceipt
        )
    )

    private fun serializeAddresses(addresses: List<EmailAddress>): String {
        val json = JSONArray()
        addresses.forEach {
            json.put(JSONObject().put("name", it.name).put("address", it.address))
        }
        return json.toString()
    }

    private fun parseAddresses(json: String): List<EmailAddress> = try {
        val array = JSONArray(json)
        (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            EmailAddress(name = obj.optString("name", ""), address = obj.optString("address", ""))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun serializeAttachments(attachments: List<Attachment>): String {
        val json = JSONArray()
        attachments.forEach {
            json.put(
                JSONObject()
                    .put("fileName", it.fileName)
                    .put("mimeType", it.mimeType)
                    .put("size", it.size)
                    .put("localPath", it.localPath)
                    .put("remoteId", it.remoteId)
                    .put("isInline", it.isInline)
                    .put("contentId", it.contentId)
            )
        }
        return json.toString()
    }

    private fun parseAttachments(json: String): List<Attachment> = try {
        val array = JSONArray(json)
        (0 until array.length()).map {
            val obj = array.getJSONObject(it)
            Attachment(
                fileName = obj.optString("fileName", ""),
                mimeType = obj.optString("mimeType", ""),
                size = obj.optLong("size", 0),
                localPath = obj.optString("localPath").takeIf { p -> p.isNotBlank() },
                remoteId = obj.optString("remoteId").takeIf { p -> p.isNotBlank() },
                isInline = obj.optBoolean("isInline", false),
                contentId = obj.optString("contentId").takeIf { p -> p.isNotBlank() }
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
