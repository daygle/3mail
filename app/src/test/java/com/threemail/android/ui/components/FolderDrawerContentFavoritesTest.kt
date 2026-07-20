package com.threemail.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Locks in the behaviour that a folder marked as a favorite appears in
 * BOTH the top "FAVORITES" shortcut section AND the canonical folder tree
 * (so starring does not remove it from the hierarchical list the user
 * expects to find). The previous draw split favorites out of the tree,
 * which made star'd folders vanish from their natural position - this
 * test would have caught that regression at the time.
 *
 * Uses [createAndroidComposeRule] under [AndroidJUnit4] (the same runner
 * [com.threemail.android.ui.InboxSettingsTitleCaseTest] uses) so the
 * test runs through Robolectric without needing an emulator.
 */
@RunWith(AndroidJUnit4::class)
class FolderDrawerContentFavoritesTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun favorited_folder_appears_in_both_favorites_section_and_tree() {
        val account = Account(
            id = 1L,
            email = "user@example.com",
            displayName = "User",
            accountType = AccountType.IMAP
        )
        val folders = listOf(
            MailFolder(
                id = 1L,
                accountId = 1L,
                serverId = "INBOX",
                name = "Inbox",
                type = FolderType.Inbox,
                isFavorite = true
            ),
            MailFolder(
                id = 2L,
                accountId = 1L,
                serverId = "Sent",
                name = "Sent",
                type = FolderType.SENT,
                isFavorite = false
            ),
            MailFolder(
                id = 3L,
                accountId = 1L,
                serverId = "Drafts",
                name = "Drafts",
                type = FolderType.DRAFTS,
                isFavorite = false
            ),
            MailFolder(
                id = 4L,
                accountId = 1L,
                serverId = "Trash",
                name = "Trash",
                type = FolderType.TRASH,
                isFavorite = false
            )
        )

        composeTestRule.setContent {
            MaterialTheme {
                FolderDrawerContent(
                    account = account,
                    accounts = listOf(account),
                    folders = folders,
                    selectedFolder = null,
                    onFolderClick = {},
                    onSelectAccount = {},
                    onToggleFavorite = {},
                    onReorderFavorite = { _, _ -> },
                    onManageAccounts = {},
                    onSettings = {},
                    onCalendar = {},
                    onSync = {}
                )
            }
        }

        // Inbox is the favorite - it should render twice (once in the
        // pinned shortcut "FAVORITES" row, once in the forest of folder
        // rows beneath). Each non-favorited folder renders exactly once.
        // If the draw ever filters favorites out of the tree again,
        // Inbox's count would fall to 1 and the assertion would fail.
        composeTestRule.onAllNodesWithText("Inbox").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Sent").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Drafts").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Trash").assertCountEquals(1)
    }
}
