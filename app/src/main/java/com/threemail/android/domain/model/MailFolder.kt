package com.threemail.android.domain.model

data class MailFolder(
    val id: Long = 0,
    val accountId: Long,
    val serverId: String,
    val name: String,
    val type: FolderType,
    val messageCount: Int = 0,
    val unreadCount: Int = 0,
    val syncVersion: Long = 0,
    /**
     * Locally tracked user preference - true iff the user starred this folder
     * via the drawer's star toggle. Not synced to the IMAP server; the value
     * comes from [com.threemail.android.data.local.entity.FolderFavoriteEntity]
     * joined in [com.threemail.android.data.repository.MailRepository.getFolders].
     */
    val isFavorite: Boolean = false
)

enum class FolderType {
    INBOX,
    SENT,
    DRAFTS,
    TRASH,
    ARCHIVE,
    SPAM,
    ALL_MAIL,
    IMPORTANT,
    STARRED,
    CUSTOM
}
