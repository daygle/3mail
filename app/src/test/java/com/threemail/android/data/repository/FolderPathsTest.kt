package com.threemail.android.data.repository

import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure IMAP-path math that drives folder rename/move.
 * These are the string operations the folder-management flows rely on, so
 * getting them wrong silently corrupts a user's folder tree - worth locking
 * down independently of the DB and server layers.
 */
class FolderPathsTest {

    private fun folder(serverId: String, name: String) =
        MailFolder(accountId = 1L, serverId = serverId, name = name, type = FolderType.CUSTOM)

    @Test
    fun `separator is inferred from a nested folder`() {
        val folders = listOf(
            folder("INBOX", "Inbox"),
            folder("INBOX.Work", "Work")
        )
        assertEquals('.', FolderPaths.separatorOf(folders))
    }

    @Test
    fun `separator falls back to dot for a flat folder set`() {
        val folders = listOf(folder("Work", "Work"), folder("Personal", "Personal"))
        assertEquals('.', FolderPaths.separatorOf(folders))
    }

    @Test
    fun `leaf and parent split a nested path`() {
        assertEquals("Acme", FolderPaths.leafOf("Work/Clients/Acme", '/'))
        assertEquals("Work/Clients", FolderPaths.parentOf("Work/Clients/Acme", '/'))
        assertNull(FolderPaths.parentOf("Work", '/'))
    }

    @Test
    fun `renamed keeps the parent and swaps the leaf`() {
        assertEquals("Work/Clients/Globex", FolderPaths.renamed("Work/Clients/Acme", "Globex", '/'))
        // Root folder: no parent to keep.
        assertEquals("Archive2024", FolderPaths.renamed("Archive", "Archive2024", '/'))
    }

    @Test
    fun `reparented moves the leaf under a new parent or to root`() {
        assertEquals("Personal/Acme", FolderPaths.reparented("Work/Acme", "Personal", '/'))
        assertEquals("Acme", FolderPaths.reparented("Work/Acme", null, '/'))
    }

    @Test
    fun `rewriteDescendant only swaps the ancestor prefix`() {
        assertEquals(
            "Personal/Acme/Invoices",
            FolderPaths.rewriteDescendant("Work/Acme/Invoices", "Work/Acme", "Personal/Acme")
        )
    }

    @Test
    fun `descendantsOf returns strict descendants only`() {
        val folders = listOf(
            folder("Work", "Work"),
            folder("Work/Acme", "Acme"),
            folder("Work/Acme/Invoices", "Invoices"),
            folder("Workshop", "Workshop") // shares a prefix but is NOT a descendant
        )
        val descendants = FolderPaths.descendantsOf("Work", folders, '/').map { it.serverId }
        assertEquals(listOf("Work/Acme", "Work/Acme/Invoices"), descendants)
    }

    @Test
    fun `isSelfOrDescendant flags illegal move targets`() {
        assertTrue(FolderPaths.isSelfOrDescendant("Work", "Work", '/'))
        assertTrue(FolderPaths.isSelfOrDescendant("Work", "Work/Acme", '/'))
        assertFalse(FolderPaths.isSelfOrDescendant("Work", "Personal", '/'))
        // Prefix-sharing sibling must not be treated as a descendant.
        assertFalse(FolderPaths.isSelfOrDescendant("Work", "Workshop", '/'))
    }
}
