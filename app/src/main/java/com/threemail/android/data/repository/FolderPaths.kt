package com.threemail.android.data.repository

import com.threemail.android.domain.model.MailFolder

/**
 * Pure helpers for manipulating IMAP folder paths (`serverId`s), used by the
 * rename / move / delete folder-management flows. IMAP addresses a folder by
 * its full hierarchy path (e.g. `Work/Clients/Acme`) with a server-chosen
 * separator; renaming or reparenting a folder changes that path for the folder
 * AND every descendant, so these functions centralise the string surgery in
 * one testable place rather than scattering `substring` math across the
 * repository, remote, and view-model layers.
 *
 * All functions are separator-parameterised; callers resolve the separator
 * once via [separatorOf] (the same heuristic the drawer's tree builder uses)
 * and thread it through.
 */
object FolderPaths {

    /**
     * Detect the hierarchy separator by comparing a folder's full `serverId`
     * with its leaf `name`: for a nested folder the character just before the
     * leaf is the separator. Falls back to probing common IMAP separators, then
     * to `.` when nothing can be inferred (a flat, single-level folder set).
     */
    fun separatorOf(folders: List<MailFolder>): Char {
        for (folder in folders) {
            if (folder.serverId != folder.name && folder.serverId.endsWith(folder.name)) {
                return folder.serverId[folder.serverId.length - folder.name.length - 1]
            }
        }
        for (sep in listOf('.', '/', '\\', '-', '_')) {
            if (folders.any { it.serverId.contains(sep) }) return sep
        }
        return '.'
    }

    /** The folder's own name: the last path component of [serverId]. */
    fun leafOf(serverId: String, separator: Char): String =
        serverId.substringAfterLast(separator)

    /**
     * The parent folder's `serverId`, or null when [serverId] is already at the
     * root of the hierarchy. A leading separator (empty first component) is
     * treated as root rather than a real parent.
     */
    fun parentOf(serverId: String, separator: Char): String? {
        val idx = serverId.lastIndexOf(separator)
        return if (idx > 0) serverId.substring(0, idx) else null
    }

    /**
     * The `serverId` produced by renaming [serverId]'s leaf to [newLeaf],
     * keeping its parent. Root folders become just [newLeaf].
     */
    fun renamed(serverId: String, newLeaf: String, separator: Char): String {
        val parent = parentOf(serverId, separator)
        return if (parent != null) "$parent$separator$newLeaf" else newLeaf
    }

    /**
     * The `serverId` produced by moving [serverId] (leaf unchanged) under
     * [newParentServerId], or to the root when that is null.
     */
    fun reparented(serverId: String, newParentServerId: String?, separator: Char): String {
        val leaf = leafOf(serverId, separator)
        return if (newParentServerId != null) "$newParentServerId$separator$leaf" else leaf
    }

    /**
     * Rewrite a descendant's `serverId` when its ancestor's path moves from
     * [oldAncestor] to [newAncestor]. Only the ancestor prefix changes; the
     * descendant's own tail is preserved verbatim.
     */
    fun rewriteDescendant(descendant: String, oldAncestor: String, newAncestor: String): String =
        newAncestor + descendant.substring(oldAncestor.length)

    /**
     * Every folder in [folders] strictly beneath [ancestorServerId] (i.e. whose
     * `serverId` begins with `ancestor + separator`). Excludes the ancestor
     * itself.
     */
    fun descendantsOf(
        ancestorServerId: String,
        folders: List<MailFolder>,
        separator: Char
    ): List<MailFolder> =
        folders.filter { it.serverId.startsWith("$ancestorServerId$separator") }

    /**
     * True when [candidateParentServerId] is [folderServerId] itself or one of
     * its descendants — the two illegal move destinations (a folder can't be
     * moved inside its own subtree).
     */
    fun isSelfOrDescendant(
        folderServerId: String,
        candidateParentServerId: String,
        separator: Char
    ): Boolean =
        candidateParentServerId == folderServerId ||
            candidateParentServerId.startsWith("$folderServerId$separator")
}
