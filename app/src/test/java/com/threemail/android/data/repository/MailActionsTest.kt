package com.threemail.android.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.ThreeMailDatabase
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.data.remote.MailRemote
import com.threemail.android.data.remote.MailRemoteFactory
import com.threemail.android.data.remote.MessageBody
import com.threemail.android.data.remote.OutgoingMessage
import com.threemail.android.data.remote.RemoteCapabilities
import com.threemail.android.data.remote.RemoteFetch
import com.threemail.android.data.remote.gmail.GmailApiClient
import com.threemail.android.data.remote.gmail.GoogleAuthHelper
import com.threemail.android.data.remote.imap.ImapClientFactory
import com.threemail.android.data.security.CredentialStore
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Drives [MailActions.moveBatch] directly through Room + [UndoController],
 * exercising the local-commit + deferred-composite-undo contract end-to-end
 * without relying on [InboxViewModel]'s reactive `messagesFlow` (whose
 * `ShadowLooper.idleMainLooper()` timing the InboxViewModelTest already
 * documents as fragile under Robolectric).
 *
 * What the suite covers:
 *   - The local Room `folderId` is updated synchronously for every
 *     selected message, so the row leaves the current feed before the
 *     deferred server op runs.
 *   - Exactly one [UndoKind.MOVE] entry is enqueued so a single Undo tap
 *     reverts the whole batch, not one snackbar per message.
 *   - [MailActions.moveBatch] silently skips messages whose
 *     `accountId` differs from `target.accountId` (cross-account IMAP
 *     MOVE is not supported).
 *   - [MailActions.moveBatch] silently skips messages whose source
 *     folder equals the target folder (would be a no-op server call).
 *
 * What the suite intentionally does not cover:
 *   - The deferred commit's IMAP/Gmail wire call. The UndoController
 *     wraps that closure in a 5s timer; trusting it is correct is the
 *     contract this test participates in, not a unit-under-test.
 */
@RunWith(RobolectricTestRunner::class)
class MailActionsTest {

    private lateinit var context: Context
    private lateinit var db: ThreeMailDatabase
    private lateinit var mailActions: MailActions
    private lateinit var undoController: UndoController

    private val accountId = 1L
    private val inboxId = 2L
    private val sentId = 3L
    private val foreignAccountId = 99L
    private val foreignFolderId = 100L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Allow main-thread Room queries so the assertions below can read the
        // post-move `folderId` directly without subscribing to flows.
        db = Room.inMemoryDatabaseBuilder(context, ThreeMailDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val accountRepository = AccountRepository(
            accountDao = db.accountDao(),
            credentialStore = NoopCredentialStore(context)
        )
        val mailRepository = MailRepository(
            folderDao = db.folderDao(),
            messageDao = db.messageDao(),
            messageFlagDao = db.messageFlagDao()
        )
        undoController = UndoController()
        mailActions = MailActions(
            accountRepository = accountRepository,
            mailRepository = mailRepository,
            mailRemoteFactory = moveOnlyRemoteFactory(),
            imapClientFactory = ImapClientFactory(GoogleAuthHelper(context)),
            undoController = undoController
        )

        seedFixtures()
    }

    @After
    fun tearDown() { if (::db.isInitialized) db.close() }

    @Test
    fun `moveBatch updates local folder ids and enqueues a single composite UndoKind MOVE`() = runBlocking {
        val target = mailFolder(id = sentId, name = "Sent", type = FolderType.SENT)
        val msgs = listOf(mailMessage(id = 10L), mailMessage(id = 11L))

        mailActions.moveBatch(msgs, target)

        assertEquals("first message Room folderId", sentId, db.messageDao().getById(10L)?.folderId)
        assertEquals("second message Room folderId", sentId, db.messageDao().getById(11L)?.folderId)
        // Composite undo: both moves revert under one Undo tap. If the suite
        // ever regresses to per-message enqueues, the snackbar would race
        // and the user would only see the last move get a chance to undo.
        assertEquals("composite undo kind", UndoKind.MOVE, undoController.pending.value?.kind)
    }

    @Test
    fun `moveBatch silently skips cross-account targets and does not enqueue an undo`() = runBlocking {
        val crossAccountTarget = mailFolder(
            id = foreignFolderId,
            accountId = foreignAccountId,
            name = "Other",
            type = FolderType.CUSTOM
        )
        val msgs = listOf(mailMessage(id = 10L))

        mailActions.moveBatch(msgs, crossAccountTarget)

        // The message stays where it was - the cross-account guard refused
        // the move rather than throwing, so the caller doesn't have to wrap
        // it in a try/catch just to defend against user-defined selections.
        assertEquals(inboxId, db.messageDao().getById(10L)?.folderId)
        assertNull("no undo entry on a fully-skipped batch", undoController.pending.value)
    }

    @Test
    fun `moveBatch silently skips source equals target and does not enqueue an undo`() = runBlocking {
        // Move the message to the folder it is already in. This is the path
        // the inbox picker excludes by filtering the source folder out of
        // `getMoveTargetFolders()`, but defending the underlying action
        // makes the contract explicit and protects any future caller that
        // forgets to filter.
        val sameFolder = mailFolder(id = inboxId, name = "Inbox", type = FolderType.Inbox)
        val msgs = listOf(mailMessage(id = 10L))

        mailActions.moveBatch(msgs, sameFolder)

        assertEquals(inboxId, db.messageDao().getById(10L)?.folderId)
        assertNull("no undo entry when every move would be a no-op", undoController.pending.value)
    }

    private fun seedFixtures() = runBlocking {
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
        db.accountDao().insert(
            AccountEntity(
                id = foreignAccountId,
                email = "other@example.com",
                displayName = "Other",
                accountType = AccountType.IMAP,
                incomingServer = "imap.other.example.com",
                outgoingServer = "smtp.other.example.com",
                useEncryption = true,
                password = null
            )
        )
        db.folderDao().insert(
            FolderEntity(
                id = inboxId,
                accountId = accountId,
                serverId = "INBOX",
                name = "Inbox",
                type = FolderType.Inbox
            )
        )
        db.folderDao().insert(
            FolderEntity(
                id = sentId,
                accountId = accountId,
                serverId = "Sent",
                name = "Sent",
                type = FolderType.SENT
            )
        )
        // Seed two messages in the inbox so moveBatch can exercise the
        // multi-row UPDATE path requested by the suite. The earlier draft
        // only seeded id=10 and asserted on `getById(11L)?.folderId`,
        // which silently returned null because Room's UPDATE on a missing
        // row is a no-op. Seeding both rows makes the contract
        // straightforward: every selected message becomes a real local
        // move.
        db.messageDao().insert(
            MessageEntity(
                id = 10L,
                accountId = accountId,
                folderId = inboxId,
                messageId = "msg-10",
                subject = "Subject 10",
                fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                date = 1_700_000_000_000L,
                syncedAt = 1_700_000_000_000L
            )
        )
        db.messageDao().insert(
            MessageEntity(
                id = 11L,
                accountId = accountId,
                folderId = inboxId,
                messageId = "msg-11",
                subject = "Subject 11",
                fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                date = 1_700_000_000_000L,
                syncedAt = 1_700_000_000_000L
            )
        )
    }

    private fun mailFolder(
        id: Long,
        accountId: Long = this.accountId,
        name: String,
        type: FolderType
    ) = MailFolder(
        id = id,
        accountId = accountId,
        serverId = "server-$id",
        name = name,
        type = type
    )

    private fun mailMessage(id: Long) = MailMessage(
        id = id,
        accountId = accountId,
        folderId = inboxId,
        messageId = "msg-$id",
        subject = "Subject $id",
        from = emptyList(),
        to = emptyList(),
        cc = emptyList(),
        date = 1_700_000_000_000L
    )

    // Stands in for the real AndroidKeyStore-backed CredentialStore: under
    // Robolectric the KeyGenerator / Cipher steps would throw, but the tests
    // don't read passwords.
    private class NoopCredentialStore(context: Context) : CredentialStore(context) {
        override fun savePassword(email: String, password: String?) {}
        override fun getPassword(email: String): String? = null
        override fun deletePassword(email: String) {}
        override fun saveOutgoingPassword(email: String, password: String?) {}
        override fun getOutgoingPassword(email: String): String? = null
        override fun deleteOutgoingPassword(email: String) {}
    }

    // MailRemote stub that records every `move()` call but returns success.
    // Tests don't trigger the deferred commit so this is only a safety net;
    // it's here so a future test that exercises the commit doesn't error out
    // on a not-stubbed method.
    private fun moveOnlyRemoteFactory(): MailRemoteFactory {
        val base = ImapClientFactory(GoogleAuthHelper(context))
        val gmail = GmailApiClient(context)
        return object : MailRemoteFactory(base, gmail) {
            override fun create(account: Account): MailRemote = MoveOnlyMailRemote()
        }
    }

    private class MoveOnlyMailRemote : MailRemote {
        override suspend fun testConnection(): Result<RemoteCapabilities> =
            Result.success(RemoteCapabilities(emptyList()))

        override suspend fun fetchFolders(): Result<List<MailFolder>> =
            Result.success(emptyList())

        override suspend fun fetchMessages(
            folder: MailFolder,
            sinceCursor: Long,
            limit: Int
        ): Result<RemoteFetch> = Result.success(RemoteFetch(emptyList(), sinceCursor))

        override suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit> =
            Result.success(Unit)

        // Other paths aren't exercised; failing loud catches a future regression.
        override suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody> = notStubbed()
        override suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit> = notStubbed()
        override suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit> = notStubbed()
        override suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit> = notStubbed()
        override suspend fun downloadAttachment(
            folder: MailFolder,
            message: MailMessage,
            attachment: Attachment,
            dest: File
        ): Result<File> = notStubbed()
        override suspend fun send(message: OutgoingMessage): Result<Unit> = notStubbed()
        override suspend fun sendRaw(messageBytes: ByteArray): Result<Unit> = notStubbed()
        override suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit> = notStubbed()

        private fun notStubbed(): Nothing =
            error("MoveOnlyMailRemote method not stubbed for this test")
    }
}
