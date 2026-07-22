package com.threemail.android.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.ThreeMailDatabase
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
