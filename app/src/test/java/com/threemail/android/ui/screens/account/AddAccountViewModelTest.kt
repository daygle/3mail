package com.threemail.android.ui.screens.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.dao.AccountDao
import com.threemail.android.data.local.entity.AccountEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * Verifies the IMAP handshake auto-upgrade: when the user picks
 * Security.NONE and the server's CAPABILITY includes STARTTLS, save()
 * transparently bumps UI state to Security.STARTTLS, sets the upgrade
 * banner, and persists the Account row with useStartTls = true.
 *
 * The real [AccountRepository] is kept so the production security ->
 * (useEncryption, useStartTls) mapper is exercised, but its two
 * collaborators are replaced with synchronous fakes:
 *
 *  - [FakeCredentialStore]: the real CredentialStore drives
 *    AndroidKeyStore AES/GCM, which Robolectric does not implement, so
 *    savePassword() would throw and abort save() before it reaches the
 *    saved terminal. We assert the persisted useStartTls column, not the
 *    password, so a no-op credential store loses no coverage.
 *  - [FakeAccountDao]: an in-memory map that captures the inserted
 *    [AccountEntity]. Crucially it does NOT suspend - a real (in-memory
 *    Room) DAO routes its suspend insert through Room's own executor
 *    dispatcher, and that cross-dispatcher hop left save()'s coroutine
 *    parked under the test scheduler (the historical source of this
 *    test's flakiness). With a fake DAO, save() has no real suspension
 *    point and runs straight to its terminal state.
 *
 * Coroutine timing: `viewModelScope.launch` runs on Dispatchers.Main. We
 * bind it to a [StandardTestDispatcher] built from `runTest`'s own
 * `testScheduler`, then drain with `advanceUntilIdle()`. A
 * StandardTestDispatcher (not Unconfined) is deliberate: an
 * UnconfinedTestDispatcher runs the launched coroutine *eagerly*, and under
 * Robolectric that eager step escapes onto the (paused) Android main looper,
 * where the test scheduler's `advanceUntilIdle()` can't reach it - so the
 * upgrade never lands before the assertions and the state read still shows
 * Security.NONE. StandardTestDispatcher instead queues every step on the
 * test scheduler, so `advanceUntilIdle()` runs the whole isSaving -> probe
 * -> upgrade -> addAccount -> isSaved chain to completion deterministically
 * before `uiState.value` is read. This is the canonical viewModelScope test
 * setup (setMain a StandardTestDispatcher sharing runTest's scheduler).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddAccountViewModelTest {

    private lateinit var accountDao: FakeAccountDao
    private lateinit var accountRepository: AccountRepository
    private lateinit var fakeFactory: FakeMailRemoteFactory

    @Before
    fun setUp() {
        // Dispatchers.setMain is deferred into the test body so it can bind to
        // runTest's own testScheduler; none of the wiring below touches Main.
        val context = ApplicationProvider.getApplicationContext<Context>()
        accountDao = FakeAccountDao()
        // Real AccountRepository so the production security -> useStartTls
        // mapper is exercised, backed by synchronous fakes so save() never
        // hops off the test dispatcher (Room's suspend insert did, which is
        // what made this test flaky).
        accountRepository = AccountRepository(
            accountDao = accountDao,
            credentialStore = FakeCredentialStore(context)
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
        Dispatchers.resetMain()
    }

    @Test
    fun `auto-upgrades Security NONE to STARTTLS when server advertises STARTTLS`() = runTest {
        // Bind Main to a StandardTestDispatcher sharing this runTest's scheduler
        // so advanceUntilIdle() drives the viewModelScope coroutine save() launches.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
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

        // Tap Save. save() launches on viewModelScope (Main == the test
        // dispatcher). advanceUntilIdle() drains the isSaving -> probe ->
        // upgrade -> addAccount -> isSaved chain to completion.
        viewModel.save()
        advanceUntilIdle()

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
        val savedRow = accountDao.saved("user@example.com")
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
     * No-op [CredentialStore] for Robolectric: the real implementation drives
     * AndroidKeyStore-backed AES/GCM, whose KeyGenerator/Cipher operations are
     * unsupported under Robolectric and throw. Overriding the writes to no-ops
     * (and reads to null) keeps AccountRepository.addAccount from aborting on
     * the credential path while leaving the persisted-column assertions intact.
     */
    private class FakeCredentialStore(context: Context) : CredentialStore(context) {
        override fun savePassword(email: String, password: String?) {}
        override fun getPassword(email: String): String? = null
        override fun deletePassword(email: String) {}
    }

    /**
     * In-memory [AccountDao] that captures inserted rows in a map keyed by
     * email. Unlike a real (even in-memory Room) DAO, none of these suspend
     * functions actually suspends: they complete on the caller's dispatcher,
     * so save()'s coroutine never hops onto Room's executor dispatcher and
     * runs straight to its terminal state under the test scheduler.
     *
     * Only [insert] and the non-suspend [saved] accessor are exercised by the
     * test; the rest are satisfied minimally.
     */
    private class FakeAccountDao : AccountDao {
        private val rows = mutableMapOf<String, AccountEntity>()

        /** Non-suspend accessor for assertions in the (non-coroutine) test body. */
        fun saved(email: String): AccountEntity? = rows[email]

        override suspend fun insert(account: AccountEntity): Long {
            rows[account.email] = account
            return rows.size.toLong()
        }

        override suspend fun getByEmail(email: String): AccountEntity? = rows[email]

        override fun getAll(): Flow<List<AccountEntity>> = flowOf(rows.values.toList())
        override suspend fun getAllOnce(): List<AccountEntity> = rows.values.toList()
        override suspend fun getById(id: Long): AccountEntity? = rows.values.firstOrNull { it.id == id }
        override suspend fun setPushEnabled(id: Long, enabled: Boolean) {}
        override suspend fun update(account: AccountEntity) { rows[account.email] = account }
        override suspend fun delete(account: AccountEntity) { rows.remove(account.email) }
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
