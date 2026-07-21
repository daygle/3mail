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
}
