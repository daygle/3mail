package com.threemail.android.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import com.threemail.android.domain.model.Account
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import com.threemail.android.domain.model.MailFolder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
 * test runs through Robolectric without needing an emulator. The
 * `h1200dp` qualifier widens the screen height past Robolectric's
 * phone-port default (around h470dp). The default is barely enough
 * for the composer's full vertical chrome plus 4 favorite-row entries
 * plus the tree, so the lower tree rows clip out of the viewport and
 * never enter the semantics tree. Bumping the height lets Compose UI
 * Test observe every row it cares about.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "h1200dp")
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

    /**
     * The drag handle (the only reorder affordance) should ONLY be reachable
     * while the user is in edit mode. Initially the section shows an "Edit"
     * chip and NO drag handles - the user reads [icon name] with no move
     * affordance at all. Tapping Edit reveals one drag handle per favorite
     * at the row's right edge; tapping Done hides them again.
     *
     * This also locks in the decision to drop the previous explicit Move
     * up / Move down IconButtons - if a future contributor re-adds them,
     * the move-button counts jump from 0 to N and this test fails. The
     * earlier complaint that "the move is duplicated" is the reason the
     * design is single-affordance; collapsing to drag keeps a familiar
     * Android-style reorder gesture.
     */
    @Test
    fun editing_favorites_exposes_drag_handle_right_and_hides_it_otherwise() {
        val account = Account(
            id = 1L,
            email = "user@example.com",
            displayName = "User",
            accountType = AccountType.IMAP
        )
        val folders = listOf(
            MailFolder(1L, 1L, "INBOX", "Inbox", FolderType.Inbox, isFavorite = true),
            MailFolder(2L, 1L, "Sent", "Sent", FolderType.SENT, isFavorite = true),
            MailFolder(3L, 1L, "Archive", "Archive", FolderType.ARCHIVE, isFavorite = true)
        )
        val reorderCalls = mutableListOf<List<String>>()

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
                    onReorderFavorite = { _, ids -> reorderCalls.add(ids) },
                    onManageAccounts = {},
                    onSettings = {},
                    onSync = {}
                )
            }
        }

        // Initially: Edit chip visible, no drag handles (move affordance
        // hidden in non-edit mode), and no Move up / Move down buttons
        // ever (single source of truth).
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Move up").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Move down").assertCountEquals(0)
        assertEquals(0, reorderCalls.size)

        // Enter edit mode: one drag handle per favorite (3 -> 3 handles).
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder").assertCountEquals(3)
        // Even in edit mode, no explicit up / down buttons - drag is the
        // single reorder affordance.
        composeTestRule.onAllNodesWithContentDescription("Move up").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Move down").assertCountEquals(0)

        // Exit edit mode: drag handles vanish, Edit chip returns.
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder").assertCountEquals(0)
        assertEquals(0, reorderCalls.size)
    }
}
