package com.threemail.android.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.ThreeMailDatabase
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MessageBody
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.RemoteCapabilities
import com.threemail.android.data.remote.RemoteFetch
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Regression coverage for [MailRepository.saveFolders].
 *
 * The bug: `saveFolders` used to re-`@Insert(onConflict = REPLACE)` every
 * folder on each sync. On an existing folder a REPLACE is a DELETE-then-INSERT,
 * and `messages.folderId` carries `ON DELETE CASCADE`, so the re-save silently
 * cascade-deleted every cached message in the folder. The periodic
 * `MailSyncWorker` re-fetches only INBOX/SENT/DRAFTS, so any other folder went
 * permanently empty after the next full sync - "a folder I know has emails
 * shows nothing". This test locks in that a folder re-save keeps its messages.
 */
@RunWith(RobolectricTestRunner::class)
class MailRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: ThreeMailDatabase
    private lateinit var repository: MailRepository

    private val accountId = 1L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ThreeMailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MailRepository(
            folderDao = db.folderDao(),
            messageDao = db.messageDao(),
            messageFlagDao = db.messageFlagDao()
        )
        runBlocking {
            db.accountDao().insert(
                AccountEntity(
                    id = accountId,
                    email = "user@example.com",
                    displayName = "User",
                    accountType = AccountType.IMAP,
                    incomingServer = "imap.example.com",
                    outgoingServer = "smtp.example.com",
                    useEncryption = true,
                    password = null
                )
            )
        }
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `re-saving folders preserves cached messages`() = runBlocking {
        val customFolder = MailFolder(
            accountId = accountId,
            serverId = "Archive",
            name = "Archive",
            type = FolderType.ARCHIVE
        )
        // First save assigns the folder a stable id.
        val saved = repository.saveFolders(listOf(customFolder)).single()

        // Populate it the way the auto-sync-on-select path does.
        db.messageDao().insertAll(
            (1L..5L).map { i ->
                MessageEntity(
                    accountId = accountId,
                    folderId = saved.id,
                    messageId = "msg-$i",
                    subject = "Subject $i",
                    fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                    date = 1_700_000_000_000L - i,
                    syncedAt = 1_700_000_000_000L
                )
            }
        )
        assertEquals(5, repository.getFolderMessageCount(saved.id))

        // A subsequent full sync re-saves the same folder list. This must NOT
        // cascade-delete the folder's messages.
        repository.saveFolders(listOf(customFolder))

        assertEquals(
            "re-saving folders must not cascade-delete cached messages",
            5,
            db.messageDao().observeByFolderWithFlags(saved.id).first().size
        )
    }

    /**
     * Regression coverage for the favourite-folder ordering bug: reordering
     * the favourites persisted new `position` values, but `getFolders` only
     * derived a `HashSet` of favourite serverIds and discarded the ordering,
     * so the drawer kept drawing them in the folder query's type/name order
     * and the reorder appeared to do nothing. This locks in that a reorder is
     * reflected in each folder's `favoritePosition`.
     */
    @Test
    fun `getFolders reflects the persisted favourite order`() = runBlocking {
        // Named so the folder query's ORDER BY name would sort them A, B, C -
        // distinct from the reorder we apply, so a regression that ignored the
        // persisted order would produce a different (alphabetical) result.
        val folders = listOf("Alpha", "Bravo", "Charlie").map { id ->
            MailFolder(accountId = accountId, serverId = id, name = id, type = FolderType.CUSTOM)
        }
        repository.saveFolders(folders)
        folders.forEach { repository.setFolderFavorite(accountId, it.serverId, isFavorite = true) }

        // Drag Charlie to the top: persisted order becomes Charlie, Alpha, Bravo.
        repository.reorderFavorites(accountId, listOf("Charlie", "Alpha", "Bravo"))

        val byServerId = repository.getFolders(accountId).first().associateBy { it.serverId }
        assertEquals(0, byServerId.getValue("Charlie").favoritePosition)
        assertEquals(1, byServerId.getValue("Alpha").favoritePosition)
        assertEquals(2, byServerId.getValue("Bravo").favoritePosition)

        // Ranked favourites, sorted the way the drawer sorts them.
        assertEquals(
            listOf("Charlie", "Alpha", "Bravo"),
            byServerId.values
                .filter { it.isFavorite }
                .sortedBy { it.favoritePosition }
                .map { it.serverId }
        )
    }

    /**
     * Regression coverage for "inbox still shows emails deleted from another
     * client": pull-to-refresh (and the worker) call reconcileDeletions, which
     * must drop cached messages the server no longer returns.
     */
    @Test
    fun `reconcileDeletions drops messages the server no longer has`() = runBlocking {
        val folderId = db.folderDao().insert(
            com.threemail.android.data.local.entity.FolderEntity(
                accountId = accountId, serverId = "INBOX", name = "Inbox", type = FolderType.Inbox
            )
        )
        val folder = MailFolder(id = folderId, accountId = accountId, serverId = "INBOX", name = "Inbox", type = FolderType.Inbox)
        insertMessage(folderId, uid = 10L, messageId = "a")
        insertMessage(folderId, uid = 20L, messageId = "b") // this one was deleted elsewhere
        insertMessage(folderId, uid = 30L, messageId = "c")

        // Server reports only uids 10 and 30 still exist; 20 was expunged.
        val remote = FakeExistenceRemote(existing = Result.success(setOf(10L, 30L)))
        val removed = repository.reconcileDeletions(remote, folder).getOrThrow()

        assertEquals(1, removed)
        assertEquals(
            listOf("a", "c"),
            repository.getMessagesOnce(folderId).map { it.messageId }.sorted()
        )
    }

    @Test
    fun `reconcileDeletions never wipes the cache when the server probe fails`() = runBlocking {
        val folderId = db.folderDao().insert(
            com.threemail.android.data.local.entity.FolderEntity(
                accountId = accountId, serverId = "INBOX", name = "Inbox", type = FolderType.Inbox
            )
        )
        val folder = MailFolder(id = folderId, accountId = accountId, serverId = "INBOX", name = "Inbox", type = FolderType.Inbox)
        insertMessage(folderId, uid = 10L, messageId = "a")
        insertMessage(folderId, uid = 20L, messageId = "b")

        // A transient network error must NOT be read as "everything was deleted".
        val remote = FakeExistenceRemote(existing = Result.failure(java.io.IOException("offline")))
        val result = repository.reconcileDeletions(remote, folder)

        assertTrue(result.isFailure)
        assertEquals(2, repository.getMessagesOnce(folderId).size)
    }

    @Test
    fun `pruneFolders removes folders absent from the server and cascades their messages`() = runBlocking {
        val keepId = db.folderDao().insert(
            com.threemail.android.data.local.entity.FolderEntity(
                accountId = accountId, serverId = "INBOX", name = "Inbox", type = FolderType.Inbox
            )
        )
        val goneId = db.folderDao().insert(
            com.threemail.android.data.local.entity.FolderEntity(
                accountId = accountId, serverId = "Old", name = "Old", type = FolderType.CUSTOM
            )
        )
        insertMessage(keepId, uid = 1L, messageId = "keep")
        insertMessage(goneId, uid = 2L, messageId = "gone")

        // Server still lists INBOX but not "Old".
        val pruned = repository.pruneFolders(accountId, setOf("INBOX"))

        assertEquals(1, pruned)
        assertEquals(listOf("INBOX"), repository.getFoldersOnce(accountId).map { it.serverId })
        // The pruned folder's cached messages cascade away; INBOX's stay.
        assertEquals(1, repository.getFolderMessageCount(keepId))
        assertEquals(0, repository.getFolderMessageCount(goneId))
    }

    @Test
    fun `pruneFolders is a no-op when the keep set is empty`() = runBlocking {
        db.folderDao().insert(
            com.threemail.android.data.local.entity.FolderEntity(
                accountId = accountId, serverId = "INBOX", name = "Inbox", type = FolderType.Inbox
            )
        )
        // An empty keep set (e.g. a failed fetch) must never wipe the tree.
        val pruned = repository.pruneFolders(accountId, emptySet())

        assertEquals(0, pruned)
        assertEquals(listOf("INBOX"), repository.getFoldersOnce(accountId).map { it.serverId })
    }

    private suspend fun insertMessage(folderId: Long, uid: Long, messageId: String) {
        db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    accountId = accountId,
                    folderId = folderId,
                    messageId = messageId,
                    uid = uid,
                    subject = "Subject $messageId",
                    fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                    date = 1_700_000_000_000L,
                    syncedAt = 1_700_000_000_000L
                )
            )
        )
    }

    /**
     * Minimal [MailRemote] that answers only listExistingMessageUids (with a
     * caller-supplied result) and fails loudly on any other call, so the test
     * exercises exactly the reconcile probe.
     */
    private class FakeExistenceRemote(
        private val existing: Result<Set<Long>>
    ) : MailRemote {
        override suspend fun listExistingMessageUids(
            folder: MailFolder,
            cachedUids: List<Long>
        ): Result<Set<Long>> = existing

        override suspend fun testConnection(): Result<RemoteCapabilities> = notStubbed()
        override suspend fun fetchFolders(): Result<List<MailFolder>> = notStubbed()
        override suspend fun fetchMessages(folder: MailFolder, sinceCursor: Long, limit: Int): Result<RemoteFetch> = notStubbed()
        override suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody> = notStubbed()
        override suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit> = notStubbed()
        override suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit> = notStubbed()
        override suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit> = notStubbed()
        override suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit> = notStubbed()
        override suspend fun downloadAttachment(folder: MailFolder, message: MailMessage, attachment: Attachment, dest: File): Result<File> = notStubbed()
        override suspend fun send(message: OutgoingMessage): Result<Unit> = notStubbed()
        override suspend fun sendRaw(messageBytes: ByteArray): Result<Unit> = notStubbed()
        override suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit> = notStubbed()

        private fun notStubbed(): Nothing = error("FakeExistenceRemote method not stubbed for this test")
    }
}
