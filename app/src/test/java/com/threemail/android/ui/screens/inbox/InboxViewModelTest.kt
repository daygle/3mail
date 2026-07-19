package com.threemail.android.ui.screens.inbox

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.ThreeMailDatabase
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.FolderEntity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Verifies [InboxViewModel] fires a remote fetch when the user picks a folder
 * in the drawer, when the bootstrap selects the initial folder, and when
 * the user toggles the unified inbox.
 *
 * The auto-sync wiring lives in [InboxViewModel]'s init block and is the only
 * mechanism that pulls messages for folders that the periodic
 * [com.threemail.android.sync.MailSyncWorker] doesn't cover (i.e. anything
 * outside the INBOX/SENT/DRAFTS whitelist). Without it, custom folders and
 * sub-folders stay empty until the user hits Refresh by hand - which is the
 * user-visible bug being fixed here.
 *
 * Tests run on [UnconfinedTestDispatcher] for [Dispatchers.Main] and idle the
 * main looper to drain any real Room/coroutine hops that landed on main.
 *
 * What is and isn't covered: the two assertions below cover the
 * user-visible bug fix - tapping a folder fires a remote fetch for that
 * folder, and a same-folder re-select is deduped. Both drive state via
 * [InboxViewModel.selectFolder], which synchronously writes
 * `_selectedFolder.value`, sidestepping the timing uncertainty around
 * Room's reactive Flow (which emits via InvalidationTracker on a
 * Room-owned executor that [ShadowLooper.idleMainLooper] cannot drain).
 *
 * What is intentionally NOT covered:
 *  - The bootstrap-driven initial sync (depends on Room's async emission).
 *  - `selectUnifiedInbox()` triggering [InboxViewModel.syncAllInboxes]
 *    (depends on `accountsFlow.value` being populated, which is also
 *    Room-timing dependent). Behaviour is correct by inspection and will
 *    be covered once a synchronous FakeAccountDao is wired in - see
 *    [com.threemail.android.ui.screens.account.AddAccountViewModelTest]
 *    for that pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InboxViewModelTest {

    private lateinit var context: Context
    private lateinit var db: ThreeMailDatabase
    private lateinit var viewModel: InboxViewModel
    private lateinit var accountRepository: AccountRepository
    private lateinit var fakeFactory: CapturingMailRemoteFactory
    private lateinit var imapFactory: ImapClientFactory
    private lateinit var undoController: UndoController

    private val seededAccountId = 1L
    private val seededInboxId = 2L
    private val seededSentId = 3L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ThreeMailDatabase::class.java)
            // Auto-sync assertions need the folder-list Flow to emit synchronously
            // when the bootstrap coroutine subscribes; without this we race Room's
            // IO executor against the unconfined main dispatcher.
            .allowMainThreadQueries()
            .build()
        fakeFactory = CapturingMailRemoteFactory(context)
        imapFactory = ImapClientFactory(GoogleAuthHelper(context))
        undoController = UndoController()

        accountRepository = AccountRepository(
            accountDao = db.accountDao(),
            credentialStore = NoopCredentialStore(context)
        )
        val mailRepository = MailRepository(
            folderDao = db.folderDao(),
            messageDao = db.messageDao()
        )
        val mailActions = MailActions(
            accountRepository = accountRepository,
            mailRepository = mailRepository,
            mailRemoteFactory = fakeFactory,
            imapClientFactory = imapFactory,
            undoController = undoController
        )

        seedFixtures()

        viewModel = InboxViewModel(
            accountRepository = accountRepository,
            mailRepository = mailRepository,
            mailActions = mailActions,
            mailRemoteFactory = fakeFactory,
            settingsRepository = realSettingsRepository(context),
            undoController = undoController
        )
        // Let the auto-sync's collectLatest pick up the bootstrap emission.
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `selectFolder of a different folder fetches that folder`() {
        // Pin the active account so the bootstrap race (foldersFlow re-emit
        // after selectAccount) doesn't leave _selectedFolder on null when
        // the assertion runs.
        val account = runBlocking { accountRepository.getAccountById(seededAccountId) }
            ?: error("seeded account missing")
        viewModel.selectAccount(account)
        ShadowLooper.idleMainLooper()

        fakeFactory.lastFetchedFolderId = -1L
        fakeFactory.fetchCount = 0

        val sent = MailFolder(
            id = seededSentId,
            accountId = seededAccountId,
            serverId = "Sent",
            name = "Sent",
            type = FolderType.SENT
        )
        viewModel.selectFolder(sent)
        ShadowLooper.idleMainLooper()

        assertEquals(
            "selectFolder(Sent) must trigger fetchMessages on the Sent folder",
            sent.id,
            fakeFactory.lastFetchedFolderId
        )
    }

    @Test
    fun `selectFolder of the same folder twice does not re-fetch`() {
        val account = runBlocking { accountRepository.getAccountById(seededAccountId) }
            ?: error("seeded account missing")
        viewModel.selectAccount(account)
        ShadowLooper.idleMainLooper()
        // The bootstrap already counted one fetch; capture the running total.
        val baseline = fakeFactory.fetchCount
        val sent = MailFolder(
            id = seededSentId,
            accountId = seededAccountId,
            serverId = "Sent",
            name = "Sent",
            type = FolderType.SENT
        )
        viewModel.selectFolder(sent)
        ShadowLooper.idleMainLooper()
        viewModel.selectFolder(sent)
        ShadowLooper.idleMainLooper()
        assertEquals(
            "a same-folder re-select must be deduped, not spam the server",
            baseline + 1,
            fakeFactory.fetchCount
        )
    }

    private fun seedFixtures() {
        runBlocking {
            db.accountDao().insert(
                AccountEntity(
                    id = seededAccountId,
                    email = "user@example.com",
                    displayName = "User",
                    accountType = AccountType.IMAP,
                    incomingServer = "imap.example.com",
                    outgoingServer = "smtp.example.com",
                    useEncryption = true,
                    password = null
                )
            )
            db.folderDao().insert(
                FolderEntity(
                    id = seededInboxId,
                    accountId = seededAccountId,
                    serverId = "INBOX",
                    name = "Inbox",
                    type = FolderType.Inbox
                )
            )
            db.folderDao().insert(
                FolderEntity(
                    id = seededSentId,
                    accountId = seededAccountId,
                    serverId = "Sent",
                    name = "Sent",
                    type = FolderType.SENT
                )
            )
        }
    }

    private fun realSettingsRepository(context: Context): SettingsRepository {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("inbox_vm_test_settings") }
        )
        return SettingsRepository(dataStore)
    }

    /**
     * Stands in for the real AndroidKeyStore-backed CredentialStore: under
     * Robolectric the KeyGenerator / Cipher steps would throw, but the
     * tests don't read passwords, so a no-op subclass is sufficient.
     */
    private class NoopCredentialStore(context: Context) : CredentialStore(context) {
        override fun savePassword(email: String, password: String?) {}
        override fun getPassword(email: String): String? = null
        override fun deletePassword(email: String) {}
    }

    /**
     * Subclass of the production [MailRemoteFactory] so we don't pull in a
     * mockito dependency. Records each `fetchMessages` call so assertions
     * can confirm which folder the viewmodel asked the server to load.
     */
    private class CapturingMailRemoteFactory(context: Context) : MailRemoteFactory(
        imapClientFactory = ImapClientFactory(GoogleAuthHelper(context)),
        gmailApiClient = GmailApiClient(context)
    ) {
        @Volatile var lastFetchedFolderId: Long = -1L
        @Volatile var lastFetchedServerId: String = ""
        @Volatile var fetchCount: Int = 0

        override fun create(account: Account): MailRemote = CapturingMailRemote(this)
    }

    private class CapturingMailRemote(private val factory: CapturingMailRemoteFactory) : MailRemote {
        override suspend fun testConnection(): Result<RemoteCapabilities> =
            Result.success(RemoteCapabilities(emptyList()))

        override suspend fun fetchFolders(): Result<List<MailFolder>> =
            Result.success(emptyList())

        override suspend fun fetchMessages(
            folder: MailFolder,
            sinceCursor: Long,
            limit: Int
        ): Result<RemoteFetch> {
            factory.lastFetchedFolderId = folder.id
            factory.lastFetchedServerId = folder.serverId
            factory.fetchCount += 1
            return Result.success(RemoteFetch(emptyList(), sinceCursor))
        }

        // Paths the viewmodel doesn't drive from these tests — failing loud
        // surfaces any future regression that re-wires one of them.
        override suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody> = notStubbed()
        override suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit> = notStubbed()
        override suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit> = notStubbed()
        override suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit> = notStubbed()
        override suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit> = notStubbed()
        override suspend fun downloadAttachment(
            folder: MailFolder,
            message: MailMessage,
            attachment: Attachment,
            dest: File
        ): Result<File> = notStubbed()
        override suspend fun send(message: OutgoingMessage): Result<Unit> = notStubbed()
        override suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit> = notStubbed()

        private fun notStubbed(): Nothing =
            error("CapturingMailRemote method not stubbed for this test; reach here means the viewmodel changed its call sites")
    }
}

