package com.threemail.android.ui.screens.account

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.ThreeMailDatabase
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
import com.threemail.android.data.security.CredentialStore
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.Attachment
import com.threemail.android.domain.model.MailFolder
import com.threemail.android.domain.model.MailMessage
import com.threemail.android.domain.model.Security
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Verifies the IMAP handshake auto-upgrade: when the user picks
 * Security.NONE and the server's CAPABILITY includes STARTTLS, save()
 * transparently bumps UI state to Security.STARTTLS, sets the upgrade
 * banner, and persists the Account row with useStartTls = true.
 *
 * Robolectric gives us a working AndroidKeyStore (so CredentialStore
 * can encrypt the password it stores on save) and an in-memory SQLite
 * (so the saved Account round-trips back through the AccountEntity
 * schema for the assertion at the end of the test).
 *
 * Coroutine timing: `viewModelScope.launch` runs on Dispatchers.Main.
 * Setting it to `UnconfinedTestDispatcher` in @Before makes every
 * launched coroutine start synchronously on the test thread - the
 * state mutation that `save()` drives (isSaving -> probe -> upgrade ->
 * addAccount -> isSaved) lands in `_uiState.value` before save() returns.
 * `ShadowLooper.idle()` is a belt-and-braces drain in case any
 * dispatcher still routes through the main looper (e.g. via
 * Robolectric's bridging). After both, `viewModel.uiState.value` is
 * the terminal state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddAccountViewModelTest {

    private lateinit var database: ThreeMailDatabase
    private lateinit var accountRepository: AccountRepository
    private lateinit var fakeFactory: FakeMailRemoteFactory

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Run Room's suspend DAO calls on the calling thread. Without this the
        // query/transaction work hops onto Room's background executor, which is
        // NOT driven by the test dispatcher (save() launches on viewModelScope,
        // not runTest's scheduler), so the insert races the assertions and the
        // `isSaved` / persisted-row checks fire before the write completes.
        // A direct executor makes each suspend DAO call finish synchronously
        // under UnconfinedTestDispatcher, making the terminal state observable.
        val directExecutor = java.util.concurrent.Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, ThreeMailDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        // Real CredentialStore so the security->useStartTls mapper on
        // AccountRepository sees the real AndroidKeyStore-backed
        // password storage path. We never read the password back in this
        // test - the goal is to verify the on-disk useStartTls column -
        // but going through the real class is the cleanest way to
        // exercise the production path.
        accountRepository = AccountRepository(
            accountDao = database.accountDao(),
            credentialStore = CredentialStore(context)
        )
        // The factory's only job is to surface a FakeMailRemote on
        // every `create(account)` call. We give it real dependencies
        // because MailRemoteFactory's constructor requires them, but the
        // override below bypasses them entirely.
        fakeFactory = FakeMailRemoteFactory(
            imapClientFactory = ImapClientFactory(GoogleAuthHelper(context)),
            gmailApiClient = GmailApiClient(context),
            capabilities = listOf("IMAP4rev1", "STARTTLS")
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `auto-upgrades Security NONE to STARTTLS when server advertises STARTTLS`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = AddAccountViewModel(
            context = context,
            accountRepository = accountRepository,
            googleAuthHelper = GoogleAuthHelper(context),
            mailRemoteFactory = fakeFactory
        )

        // User picks cleartext.
        viewModel.updateEmail("user@example.com")
        viewModel.updatePassword("hunter2")
        viewModel.updateServer("imap.example.com")
        viewModel.updateSecurity(Security.NONE)
        assertEquals(Security.NONE, viewModel.uiState.value.security)
        assertNull(viewModel.uiState.value.upgradeBanner)

        // Tap Save. The launched coroutine completes synchronously under
        // UnconfinedTestDispatcher, so the next line sees the terminal
        // state already; ShadowLooper.idle() is the safety net.
        viewModel.save()
        ShadowLooper.idleMainLooper()

        // UI state mutations driven by the auto-upgrade.
        val state = viewModel.uiState.value
        assertEquals("STARTTLS chip should be selected after auto-upgrade", Security.STARTTLS, state.security)
        assertEquals(
            "banner should explain the upgrade",
            "Server supports STARTTLS — upgraded automatically.",
            state.upgradeBanner
        )
        assertTrue("save() should reach the saved terminal", state.isSaved)

        // The persisted Account row reflects the upgraded security via
        // AccountRepository.toEntity's mapper (security == STARTTLS ->
        // useStartTls = true).
        val savedRow = database.accountDao().getByEmail("user@example.com")
        assertNotNull("account row should be persisted", savedRow)
        assertTrue(
            "useStartTls should be true on the saved AccountEntity",
            savedRow!!.useStartTls
        )
        // useEncryption must be false - the mapper enforces "exactly one
        // of the two is true", and STARTTLS wins.
        assertEquals(false, savedRow.useEncryption)
    }

    /**
     * Test-only MailRemoteFactory that bypasses its real dependencies
     * (no IMAP connect, no Gmail REST API) and returns a FakeMailRemote
     * advertising a fixed CAPABILITY list.
     */
    private class FakeMailRemoteFactory(
        imapClientFactory: ImapClientFactory,
        gmailApiClient: GmailApiClient,
        private val capabilities: List<String>
    ) : MailRemoteFactory(imapClientFactory, gmailApiClient) {
        override fun create(account: Account): MailRemote = FakeMailRemote(capabilities)
    }

    /**
     * Test-only MailRemote. Only [testConnection] is wired; every other
     * method is unreachable from the auto-upgrade path and throws so any
     * future regression in the test's mock wiring fails loud rather than
     * silently passing on a stubbed return.
     */
    private class FakeMailRemote(
        private val capabilities: List<String>
    ) : MailRemote {
        override suspend fun testConnection(): Result<RemoteCapabilities> =
            Result.success(RemoteCapabilities(capabilities))

        override suspend fun fetchFolders(): Result<List<MailFolder>> = notStubbed()
        override suspend fun fetchMessages(
            folder: MailFolder,
            sinceCursor: Long,
            limit: Int
        ): Result<RemoteFetch> = notStubbed()
        override suspend fun fetchBody(folder: MailFolder, message: MailMessage): Result<MessageBody> = notStubbed()
        override suspend fun setSeen(folder: MailFolder, message: MailMessage, seen: Boolean): Result<Unit> = notStubbed()
        override suspend fun setFlagged(folder: MailFolder, message: MailMessage, flagged: Boolean): Result<Unit> = notStubbed()
        override suspend fun delete(folder: MailFolder, message: MailMessage, trash: MailFolder?): Result<Unit> = notStubbed()
        override suspend fun move(from: MailFolder, message: MailMessage, to: MailFolder): Result<Unit> = notStubbed()
        override suspend fun downloadAttachment(
            folder: MailFolder,
            message: MailMessage,
            attachment: Attachment,
            dest: java.io.File
        ): Result<java.io.File> = notStubbed()
        override suspend fun send(message: OutgoingMessage): Result<Unit> = notStubbed()
        override suspend fun appendDraft(draftsFolder: MailFolder, message: OutgoingMessage): Result<Unit> = notStubbed()

        private fun notStubbed(): Nothing =
            error("FakeMailRemote method not stubbed; reach here means the test's mock wiring changed")
    }
}