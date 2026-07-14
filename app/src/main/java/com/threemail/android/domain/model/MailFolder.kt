package com.threemail.android.domain.model

data class MailFolder(
    val id: Long = 0,
    val accountId: Long,
    val serverId: String,
    val name: String,
    val type: FolderType,
    val messageCount: Int = 0,
    val unreadCount: Int = 0,
    val syncVersion: Long = 0
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
