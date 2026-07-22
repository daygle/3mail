package com.threemail.android.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.threemail.android.data.local.ThreeMailDatabase
import com.threemail.android.data.local.entity.AccountEntity
import com.threemail.android.data.local.entity.FolderEntity
import com.threemail.android.data.local.entity.FolderFavoriteEntity
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip test for [FolderDao.reorderFavorites] against an in-memory
 * ThreeMailDatabase (fresh v12 schema - the migration is covered by
 * [com.threemail.android.data.local.migrations.MigrationsTest]).
 *
 * The user's request is: "insert three favorites, reorder to positions
 * [2,0,1], and confirm `getFavoritesByAccount` returns them in the new
 * order." We model `[2,0,1]` as the new list ordering - original tuples
 * were [A,B,C] at positions [0,1,2], the drag takes C to the top so the
 * post-reorder sequence is [C,A,B] mapping to slots [0,1,2]. Position
 * numbers in the rowids themselves move with the slot.
 */
@RunWith(RobolectricTestRunner::class)
class FolderDaoTest {

    private lateinit var database: ThreeMailDatabase
    private lateinit var folderDao: FolderDao
    private lateinit var accountDao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ThreeMailDatabase::class.java)
            // Drag-reorder test only ever asserts post-conditions, so it
            // doesn't need the dispatcher plumbing - allow main-thread
            // queries keeps runBlocking the simple form.
            .allowMainThreadQueries()
            .build()
        folderDao = database.folderDao()
        accountDao = database.accountDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reorderFavorites_reassigns_positions_in_the_supplied_order() = runBlocking {
        val accountId = accountDao.insert(
            AccountEntity(
                email = "user@example.com",
                displayName = "User",
                accountType = AccountType.IMAP
            )
        )
        // Three folders to drive the reorder - any three that the DAO
        // can find via getByServerId are sufficient; type doesn't matter
        // for the favourites path.
        folderDao.insert(serverId = "A", accountId = accountId, name = "Archives A", type = FolderType.ARCHIVE)
        folderDao.insert(serverId = "B", accountId = accountId, name = "Bills B", type = FolderType.Inbox)
        folderDao.insert(serverId = "C", accountId = accountId, name = "Colleagues C", type = FolderType.Inbox)

        // Star all three; insert in order so rowid==1,2,3 (FIFO).
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "A", position = 0))
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "B", position = 1))
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "C", position = 2))

        // Sanity: pre-reorder query returns them in insertion order.
        val before = folderDao.getFavoritesByAccountOnce(accountId)
        assertEquals(listOf("A", "B", "C"), before.map { it.serverId })

        // Reorder to [C, A, B] - C drags to top.
        folderDao.reorderFavorites(accountId, listOf("C", "A", "B"))

        // Post-condition: getFavoritesByAccount reads back in the new
        // order, with positions 0/1/2 reassigned to the new slots.
        val after = folderDao.getFavoritesByAccountOnce(accountId)
        assertEquals(3, after.size)
        assertEquals("C", after[0].serverId); assertEquals(0, after[0].position)
        assertEquals("A", after[1].serverId); assertEquals(1, after[1].position)
        assertEquals("B", after[2].serverId); assertEquals(2, after[2].position)
    }

    @Test
    fun getFavoritesByAccount_orders_by_position_then_rowid_for_ties() = runBlocking {
        val accountId = accountDao.insert(
            AccountEntity(
                email = "user@example.com",
                displayName = "User",
                accountType = AccountType.IMAP
            )
        )
        folderDao.insert(serverId = "A", accountId = accountId, name = "X", type = FolderType.Inbox)
        folderDao.insert(serverId = "B", accountId = accountId, name = "Y", type = FolderType.Inbox)
        folderDao.insert(serverId = "C", accountId = accountId, name = "Z", type = FolderType.Inbox)

        // Reverse insert order: C first, then B, then A. With rows that
        // all land at position=0 (inserting via the path that does not
        // compute max+1) the rowid tie-breaker should sort C, B, A.
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "C", position = 0))
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "B", position = 0))
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "A", position = 0))

        val ordered = folderDao.getFavoritesByAccountOnce(accountId)
        // Tie-breaker is rowid ASC: C(1) then B(2) then A(3).
        assertEquals(listOf("C", "B", "A"), ordered.map { it.serverId })
        assertEquals(listOf(0, 0, 0), ordered.map { it.position })
    }

    @Test
    fun relocateFolder_rewrites_folder_descendants_and_favourite() = runBlocking {
        val accountId = accountDao.insert(
            AccountEntity(
                email = "user@example.com",
                displayName = "User",
                accountType = AccountType.IMAP
            )
        )
        // A parent with one descendant, plus a favourite on the descendant so
        // we can prove starred state follows a rename.
        folderDao.insert(serverId = "Work", accountId = accountId, name = "Work", type = FolderType.CUSTOM)
        folderDao.insert(serverId = "Work.Acme", accountId = accountId, name = "Acme", type = FolderType.CUSTOM)
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "Work.Acme", position = 0))

        // Rename "Work" -> "Clients": the folder itself takes the new leaf name,
        // the descendant shifts its path prefix but keeps its name.
        folderDao.relocateFolder(
            accountId = accountId,
            oldServerId = "Work",
            newServerId = "Clients",
            newName = "Clients",
            descendantRewrites = listOf("Work.Acme" to "Clients.Acme")
        )

        val folders = folderDao.getByAccountOnce(accountId).associateBy { it.serverId }
        assertEquals(setOf("Clients", "Clients.Acme"), folders.keys)
        assertEquals("Clients", folders.getValue("Clients").name)
        // Descendant kept its display name, only the path moved.
        assertEquals("Acme", folders.getValue("Clients.Acme").name)

        // Favourite followed the descendant to its new serverId.
        val favourites = folderDao.getFavoritesByAccountOnce(accountId)
        assertEquals(listOf("Clients.Acme"), favourites.map { it.serverId })
    }

    @Test
    fun deleteFolderTree_removes_folders_and_their_favourites() = runBlocking {
        val accountId = accountDao.insert(
            AccountEntity(
                email = "user@example.com",
                displayName = "User",
                accountType = AccountType.IMAP
            )
        )
        folderDao.insert(serverId = "Work", accountId = accountId, name = "Work", type = FolderType.CUSTOM)
        folderDao.insert(serverId = "Work.Acme", accountId = accountId, name = "Acme", type = FolderType.CUSTOM)
        folderDao.insert(serverId = "Keep", accountId = accountId, name = "Keep", type = FolderType.CUSTOM)
        folderDao.addFavorite(FolderFavoriteEntity(accountId, "Work.Acme", position = 0))

        folderDao.deleteFolderTree(accountId, listOf("Work", "Work.Acme"))

        // Only the untouched folder survives; its sibling tree and the
        // favourite that pointed into it are gone.
        assertEquals(listOf("Keep"), folderDao.getByAccountOnce(accountId).map { it.serverId })
        assertEquals(emptyList<String>(), folderDao.getFavoritesByAccountOnce(accountId).map { it.serverId })
    }

    private suspend fun FolderDao.insert(
        serverId: String,
        accountId: Long,
        name: String,
        type: FolderType
    ) = insert(
        FolderEntity(
            accountId = accountId,
            serverId = serverId,
            name = name,
            type = type
        )
    )
}
