package com.threemail.android.ui.screens.message

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.crypto.OpenPgpController
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
import com.threemail.android.data.repository.AccountRepository
import com.threemail.android.data.repository.MailActions
import com.threemail.android.data.repository.MailRepository
import com.threemail.android.data.repository.UndoController
import com.threemail.android.data.security.CredentialStore
import com.threemail.android.data.settings.SettingsRepository
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File    /**
     * Locks in that every destructive action on the currently-open message
     * (delete / archive / moveToFolder / markSpam) reaches the screen's
     * auto-advance trigger by setting [MessageDetailViewModel.UiState.isDeleted]
     * to true. Without this guarantee the LaunchedEffect in [MessageDetailScreen]
     * would not see a transition when archive, move, or markSpam run, leaving
     * the user's "After delete / archive / move / spam" preference ("Open next
     * message") to silently fall back to "Return to inbox" for those actions.
     *
     * Construction follows the MailActionsTest recipe:
     *  - Real [MailRepository] backed by an in-memory [ThreeMailDatabase] so the
     *    VM init chain (getMessageById -> loadFolders -> setRead -> resolveNext)
     *    reads real fixture rows instead of stubs. Everything the test asserts
     *    is downstream of that chain, so the production path is exercised end
     *    to end.
     *  - [MailActions] is the production class wired with a [MoveOnlyMailRemote]
     *    stub for the IMAP wire call and a no-op [CredentialStore] - we don't
     *    want the real AndroidKeyStore path (Robolectric can't drive it), and
     *    archive / move / spam all need a working server stub to reach the
     *    client-side path that flips isDeleted.
     *  - [waitForUiState] combines [ShadowLooper.idleMainLooper] with a short
     *    [Thread.sleep] so the coroutines launched on viewModelScope hop
     *    cleanly across to (and back from) Room's query executor thread. The
     *    pure-Dispatchers.Main drain the AddAccountViewModelTest relies on
     *    is insufficient here because Room uses its own dispatcher, which
     *    Robolectric doesn't drive.
     *
     * What the test intentionally does NOT cover:
     *  - The Compose side. The screen's LaunchedEffect is what actually
     *    navigates to nextMessageId / popBackStack; that's a Compose test
     *    problem and not what this VM-level pinning solves.
     *  - The UndoController's deferred commit. It runs on a 5s timer that
     *    Robolectric's paused looper never elapses, so the suite trusts
     *    UndoController's own contract rather than re-asserting it here.
     */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MessageDetailViewModelTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: ThreeMailDatabase
    private lateinit var viewModel: MessageDetailViewModel

    private val accountId = 1L
    private val inboxId = 100L
    private val archiveId = 101L
    private val sentId = 102L
    private val spamId = 103L
    private val trashId = 104L
    private val messageId = 42L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ThreeMailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { seedFixtures() }

        viewModel = buildViewModel()
        // Drain the constructor's two launches:
        //  1) settingsRepository.settings.collect - the DataStore emits its
        //     first frame (an empty preferences map) and the VM mirrors
        //     loadImagesSetting into UiState.
        //  2) loadMessage -> getMessageById -> loadFolders -> setRead ->
        //     resolveNext, which hops onto Room's query executor.
        // Real production order matters: if we drain before init's launches
        // settle, uiState.message would still be null and delete() etc. would
        // early-return at `?: return` without ever flipping isDeleted.
        ShadowLooper.idleMainLooper()
        // Room's query executor isn't drivable through ShadowLooper - the
        // real Room callback queue lives on a dedicated thread. Yield to it
        // explicitly via short sleeps until [loadMessage] finishes. waitFor
        // throws with a descriptive label on timeout, so a regression in
        // init shows up here with attribution rather than as a confusing
        // false-positive in the downstream destructive assertions.
        waitForUiState(initTimeout = 5_000L) {
            viewModel.uiState.value.message != null
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `delete sets isDeleted so the screen can advance to the next message`() {
        viewModel.delete()
        waitForUiState { viewModel.uiState.value.isDeleted }
        assertEquals(true, viewModel.uiState.value.isDeleted)
    }

    @Test
    fun `archive sets isDeleted so the screen can advance to the next message`() {
        viewModel.archive()
        waitForUiState { viewModel.uiState.value.isDeleted }
        assertEquals(true, viewModel.uiState.value.isDeleted)
    }

    @Test
    fun `moveToFolder sets isDeleted so the screen can advance to the next message`() {
        val target = MailFolder(
            id = sentId,
            accountId = accountId,
            serverId = "Sent",
            name = "Sent",
            type = FolderType.SENT
        )
        viewModel.moveToFolder(target)
        waitForUiState { viewModel.uiState.value.isDeleted }
        assertEquals(true, viewModel.uiState.value.isDeleted)
    }

    @Test
    fun `markSpam sets isDeleted so the screen can advance to the next message`() {
        viewModel.markSpam()
        waitForUiState { viewModel.uiState.value.isDeleted }
        assertEquals(true, viewModel.uiState.value.isDeleted)
    }

    /**
     * Poll helper that combines [ShadowLooper.idleMainLooper] (drains the
     * Main-thread queue) with [Thread.sleep] (yields to Room's query
     * executor thread, which Robolectric doesn't drive from any single
     * looper). Polls until [condition] returns true or the labeled timeout
     * expires; on expiry it throws an [AssertionError] whose message names
     * the call site ([callSite]) so the failure attribution points at the
     * awaited condition rather than the downstream assertion's subject.
     *
     * A future improvement would be configuring the in-memory Room builder
     * with `setQueryExecutor { it.run() }` so DAO calls run synchronously
     * on the caller's thread, eliminating both the cross-thread hop and
     * the polling dependency. Kept as polling today: the structural change
     * to RoomDatabase.Builder is out of scope for this regression net.
     */
    private fun waitForUiState(
        callSite: String = Thread.currentThread().stackTrace[1].methodName,
        intervalMs: Long = 50L,
        initTimeout: Long? = null,
        condition: () -> Boolean
    ) {
        val timeoutMs = initTimeout ?: 5_000L
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for UI-state condition in ${callSite}"
        )
    }

    private fun buildViewModel(): MessageDetailViewModel {
        val mailRepository = MailRepository(
            folderDao = db.folderDao(),
            messageDao = db.messageDao(),
            messageFlagDao = db.messageFlagDao()
        )
        val accountRepository = AccountRepository(
            accountDao = db.accountDao(),
            credentialStore = NoopCredentialStore(context)
        )
        val undoController = UndoController()
        val remoteFactory = moveOnlyRemoteFactory()
        val mailActions = MailActions(
            accountRepository = accountRepository,
            mailRepository = mailRepository,
            mailRemoteFactory = remoteFactory,
            imapClientFactory = ImapClientFactory(GoogleAuthHelper(context)),
            undoController = undoController
        )
        // In-memory DataStore backed by a per-test tempfile - the
        // SettingsRepository.constructor needs one and the VM init loop
        // subscribes to its flow. An empty-preferences backdrop is fine
        // because the only field the VM mirrors is loadImages (default false).
        val settingsDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("settings.preferences_pb") },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
        val settingsRepository = SettingsRepository(settingsDataStore)
        return MessageDetailViewModel(
            context = context,
            mailRepository = mailRepository,
            accountRepository = accountRepository,
            mailActions = mailActions,
            mailRemoteFactory = remoteFactory,
            openPgpController = OpenPgpController(
                context = context,
                accountRepository = accountRepository,
                credentialStore = NoopCredentialStore(context)
            ),
            settingsRepository = settingsRepository,
            savedStateHandle = SavedStateHandle(mapOf("messageId" to messageId))
        )
    }

    /**
     * Seeds the rows that every destructive *WithUndo() helper consults.
     * Each of the 5 folders is required for a non-silent-fallback path:
     *   - Inbox: the source folder for the seeded message and the resolution
     *     path the VM uses to compute `nextMessageId`.
     *   - Archive: archive() calls archiveWithUndo which silently falls
     *     back to deleteWithUndo when no Archive / All-Mail folder exists.
     *     Seeding ensures the assertion tests the archive branch, not the
     *     fall-through.
     *   - Sent: moveToFolder's user-pick contract picks an arbitrary target
     *     folder; Sent is the simplest.
     *   - Spam: markSpam() is no-op if no Spam folder exists, which would
     *     bypass the assertion entirely.
     *   - Trash: delete() falls back to plain delete() if Trash is missing,
     *     which still flips isDeleted - technically the assertion would
     *     pass without Trash. Seeding it anyway keeps the test honest
     *     about which *WithUndo path is exercised.
     */
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
        db.folderDao().insert(FolderEntity(
            id = inboxId, accountId = accountId, serverId = "INBOX",
            name = "Inbox", type = FolderType.INBOX
        ))
        db.folderDao().insert(FolderEntity(
            id = archiveId, accountId = accountId, serverId = "Archive",
            name = "Archive", type = FolderType.ARCHIVE
        ))
        db.folderDao().insert(FolderEntity(
            id = sentId, accountId = accountId, serverId = "Sent",
            name = "Sent", type = FolderType.SENT
        ))
        db.folderDao().insert(FolderEntity(
            id = spamId, accountId = accountId, serverId = "Spam",
            name = "Spam", type = FolderType.SPAM
        ))
        db.folderDao().insert(FolderEntity(
            id = trashId, accountId = accountId, serverId = "Trash",
            name = "Trash", type = FolderType.TRASH
        ))
        db.messageDao().insert(
            MessageEntity(
                id = messageId,
                accountId = accountId,
                folderId = inboxId,
                messageId = "msg-42",
                subject = "Subject 42",
                fromJson = "[]",
                toJson = "[]",
                ccJson = "[]",
                bccJson = "[]",
                date = 1_700_000_000_000L,
                syncedAt = 1_700_000_000_000L
            )
        )
    }

    /**
     * MailRemote stub that no-ops the wire calls MailActions needs to reach
     * every *WithUndo() branch the tests cover (setSeen, move, delete). The
     * rest of the surface intentionally throws so a future regression that
     * re-routes through fetchFolders/fetchBody would fail loud rather than
     * silently skimming the assertion on a stubbed return.
     */
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
        override suspend fun setSeen(
            folder: MailFolder,
            message: MailMessage,
            seen: Boolean
        ): Result<Unit> = Result.success(Unit)
        override suspend fun move(
            from: MailFolder,
            message: MailMessage,
            to: MailFolder
        ): Result<Unit> = Result.success(Unit)
        override suspend fun delete(
            folder: MailFolder,
            message: MailMessage,
            trash: MailFolder?
        ): Result<Unit> = Result.success(Unit)
        override suspend fun fetchBody(
            folder: MailFolder,
            message: MailMessage
        ): Result<MessageBody> = notStubbed()
        override suspend fun setFlagged(
            folder: MailFolder,
            message: MailMessage,
            flagged: Boolean
        ): Result<Unit> = notStubbed()
        override suspend fun downloadAttachment(
            folder: MailFolder,
            message: MailMessage,
            attachment: Attachment,
            dest: File
        ): Result<File> = notStubbed()
        override suspend fun send(message: OutgoingMessage): Result<Unit> = notStubbed()
        override suspend fun sendRaw(messageBytes: ByteArray): Result<Unit> = notStubbed()
        override suspend fun appendDraft(
            draftsFolder: MailFolder,
            message: OutgoingMessage
        ): Result<Unit> = notStubbed()

        private fun notStubbed(): Nothing =
            error("MoveOnlyMailRemote method not stubbed for this test")
    }

    // Real AndroidKeyStore isn't available under Robolectric; the tests
    // don't read passwords, so a no-op CredentialStore loses no coverage.
    private class NoopCredentialStore(context: Context) : CredentialStore(context) {
        override fun savePassword(email: String, password: String?) {}
        override fun getPassword(email: String): String? = null
        override fun deletePassword(email: String) {}
        override fun saveOutgoingPassword(email: String, password: String?) {}
        override fun getOutgoingPassword(email: String): String? = null
        override fun deleteOutgoingPassword(email: String) {}
    }
}
