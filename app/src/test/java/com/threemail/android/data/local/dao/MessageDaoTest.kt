package com.threemail.android.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.ThreeMailDatabase
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.MessageEntity
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Locks in the "show every email in the folder, not just the latest 50"
 * contract for [MessageDao.observeByFolder] and
 * [MessageDao.observeUnifiedInbox]. Before the LIMIT removal, both queries
 * silently capped the reactive feed at the most recent 50 rows; this
 * presented as "today's email only" once a busy inbox crossed 50
 * arrivals, and it equally hid any older rows in favorited non-INBOX
 * folders where the user expected to scroll back further.
 *
 * The chosen row count ([ROWS_PAST_LEGACY_CAP] = 200) is well above the
 * historical LIMIT 50 cap and above any conceivable "safety" cap a future
 * contributor might re-introduce - the regression fails for any LIMIT in
 * the range 1..199. Tests use a real Room in-memory database under
 * Robolectric, mirroring the
 * [com.threemail.android.data.repository.MailActionsTest] pattern: same
 * dependency setup, same `allowMainThreadQueries` so `.first()` on the
 * Flow can read the synchronous on-collect emission without a coroutine
 * harness.
 */
@RunWith(RobolectricTestRunner::class)
class MessageDaoTest {

    /**
     * Number of rows inserted in each regression test. Deliberately larger
     * than the historical `LIMIT 50` cap so a regression that re-adds
     * `LIMIT :limit` with any value strictly below this constant fails
     * the assertion. Picked large enough to lock the regression in across
     * plausible safety margins (50, 75, 100, 150) yet small enough that
     * the in-memory insert is sub-millisecond per row.
     */
    private companion object {
        const val ROWS_PAST_LEGACY_CAP = 200
    }

    private lateinit var context: Context
    private lateinit var db: ThreeMailDatabase

    private val accountIdOne = 1L
    private val accountIdTwo = 2L
    private val inboxOneId = 10L
    private val inboxTwoId = 20L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // `allowMainThreadQueries` so the assertions below can read the
        // post-emission list directly via `Flow.first()` without setting up
        // an extra `runBlocking`/test dispatcher dance.
        db = Room.inMemoryDatabaseBuilder(context, ThreeMailDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedFixtures()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    /**
     * Regression for the favorite-folder bug. Insert [ROWS_PAST_LEGACY_CAP]
     * rows into a single folder and assert the reactive feed carries the
     * full set. If a future contributor re-adds `LIMIT :limit` with any
     * value below [ROWS_PAST_LEGACY_CAP], the assertion fails and the
     * regression is caught at PR time.
     */
    @Test
    fun `observeByFolder returns every stored row past the legacy LIMIT cap`() = runBlocking {
        val rows = (1L..ROWS_PAST_LEGACY_CAP.toLong()).map { i ->
            MessageEntity(
                id = i,
                accountId = accountIdOne,
                folderId = inboxOneId,
                messageId = "msg-$i",
                subject = "Subject $i",
                fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                // Strictly descending `date` so the date-DESC ordering is
                // deducible, not just the row-insert order.
                date = 1_700_000_000_000L - i,
                syncedAt = 1_700_000_000_000L
            )
        }
        db.messageDao().insertAll(rows)

        val emitted = db.messageDao().observeByFolder(inboxOneId).first()
        assertEquals(
            "observeByFolder silently dropped rows behind a hidden LIMIT (the 'favorite folder only shows today's emails' regression); emitted=${emitted.size}, expected=$ROWS_PAST_LEGACY_CAP",
            ROWS_PAST_LEGACY_CAP,
            emitted.size
        )
        // Sanity: the date-DESC ordering is also held - if someone changes
        // the SELECT to ORDER BY date ASC by accident, this catches it.
        val dates = emitted.map { it.date }
        assertEquals(
            "observeByFolder must emit newest-first (ORDER BY date DESC)",
            dates.sortedDescending(),
            dates
        )
    }

    /**
     * Regression for the same bug on the unified-inbox path. Two accounts
     * each have an INBOX with half of [ROWS_PAST_LEGACY_CAP] messages;
     * the JOIN-aggregated view must carry the full set, not the legacy
     * cap. Id ranges are disjoint across the two accounts so the
     * REPLACE-on-conflict INSERT strategy can't accidentally clobber
     * sibling rows.
     */
    @Test
    fun `observeUnifiedInbox returns every stored inbox row past the legacy LIMIT cap`() = runBlocking {
        val rowsPerInbox = ROWS_PAST_LEGACY_CAP / 2
        val totalExpected = ROWS_PAST_LEGACY_CAP
        val rows = buildList {
            (1L..rowsPerInbox.toLong()).forEach { i ->
                add(
                    MessageEntity(
                        id = i,
                        accountId = accountIdOne,
                        folderId = inboxOneId,
                        messageId = "msg-acc1-$i",
                        subject = "Acc1 Subject $i",
                        fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                        date = 1_700_000_000_000L - i,
                        syncedAt = 1_700_000_000_000L
                    )
                )
            }
            (1L..rowsPerInbox.toLong()).forEach { i ->
                add(
                    MessageEntity(
                        // Distinct range so cross-account REPLACE collisions
                        // can't overwrite an account-1 row.
                        id = 10_000L + i,
                        accountId = accountIdTwo,
                        folderId = inboxTwoId,
                        messageId = "msg-acc2-$i",
                        subject = "Acc2 Subject $i",
                        fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                        date = 1_700_000_000_000L - (10_000L + i),
                        syncedAt = 1_700_000_000_000L
                    )
                )
            }
        }
        db.messageDao().insertAll(rows)

        val emitted = db.messageDao().observeUnifiedInbox().first()
        assertEquals(
            "observeUnifiedInbox silently dropped rows behind a hidden LIMIT; emitted=${emitted.size}, expected=$totalExpected",
            totalExpected,
            emitted.size
        )
        // The seed contains ONLY two INBOX folders and no other folder
        // types, so any emitted row with a different folderId is
        // structurally a JOIN regression (WHERE filtered wrong). This
        // belt-and-braces check catches a migration that widens the
        // unified-inbox scope without bumping the size assertion.
        assertTrue(
            "observeUnifiedInbox must only return rows from INBOX folders",
            emitted.all { it.folderId == inboxOneId || it.folderId == inboxTwoId }
        )
        // Date-DESC ordering should also hold across the account join.
        val dates = emitted.map { it.date }
        assertEquals(
            "observeUnifiedInbox must emit newest-first across accounts (ORDER BY m.date DESC)",
            dates.sortedDescending(),
            dates
        )
    }

    /**
     * The UI's single-folder feed is served by [MessageDao.observeByFolderWithFlags]
     * (via MailRepository.observeFolder), NOT the plain [MessageDao.observeByFolder]
     * exercised above. This test locks that production query: every row in the
     * folder must survive the LEFT JOIN onto message_flags, whether or not a
     * flag row exists for it.
     */
    @Test
    fun `observeByFolderWithFlags returns every folder row through the flags join`() = runBlocking {
        val rows = (1L..ROWS_PAST_LEGACY_CAP.toLong()).map { i ->
            MessageEntity(
                id = i,
                accountId = accountIdOne,
                folderId = inboxOneId,
                messageId = "msg-$i",
                subject = "Subject $i",
                fromJson = "[]", toJson = "[]", ccJson = "[]", bccJson = "[]",
                date = 1_700_000_000_000L - i,
                syncedAt = 1_700_000_000_000L
            )
        }
        db.messageDao().insertAll(rows)
        // A flag row for exactly one message, so the test covers both the
        // matched (isEncrypted set) and unmatched (isEncrypted null) join arms.
        db.messageFlagDao().insertOrIgnore(
            com.threemail.android.data.local.entity.MessageFlagEntity(
                accountId = accountIdOne,
                messageId = "msg-1",
                isEncrypted = true
            )
        )

        val emitted = db.messageDao().observeByFolderWithFlags(inboxOneId).first()
        assertEquals(
            "observeByFolderWithFlags dropped rows across the message_flags LEFT JOIN; emitted=${emitted.size}, expected=$ROWS_PAST_LEGACY_CAP",
            ROWS_PAST_LEGACY_CAP,
            emitted.size
        )
    }

    private fun seedFixtures() = runBlocking {
        db.accountDao().insert(
            AccountEntity(
                id = accountIdOne,
                email = "user1@example.com",
                displayName = "User One",
                accountType = AccountType.IMAP,
                incomingServer = "imap.example.com",
                outgoingServer = "smtp.example.com",
                useEncryption = true,
                password = null
            )
        )
        db.accountDao().insert(
            AccountEntity(
                id = accountIdTwo,
                email = "user2@example.com",
                displayName = "User Two",
                accountType = AccountType.IMAP,
                incomingServer = "imap.example.com",
                outgoingServer = "smtp.example.com",
                useEncryption = true,
                password = null
            )
        )
        db.folderDao().insert(
            FolderEntity(
                id = inboxOneId,
                accountId = accountIdOne,
                serverId = "INBOX",
                name = "Inbox",
                type = FolderType.Inbox
            )
        )
        db.folderDao().insert(
            FolderEntity(
                id = inboxTwoId,
                accountId = accountIdTwo,
                serverId = "INBOX",
                name = "Inbox",
                type = FolderType.Inbox
            )
        )
    }
}
