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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
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

    /**
     * The move up / move down affordances should ONLY be reachable
     * while the user is in edit mode. Initially the section shows an
     * "Edit" chip with no move buttons; tapping it swaps to three
     * IconButtons per row and the "Done" chip; tapping Done reverts.
     */
    @Test
    fun editing_favorites_exposes_move_up_and_move_down_buttons() {
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
                    onCalendar = {},
                    onSync = {}
                )
            }
        }

        // Initially: Edit chip visible, no move arrows rendered.
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Move up").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Move down").assertCountEquals(0)
        assertEquals(0, reorderCalls.size)

        // Enter edit mode: one Move up and one Move down button per row
        // (3 favorites -> 6 buttons total), and the chip flips to "Done".
        composeTestRule.onNodeWithText("Edit").performClick()
        composeTestRule.onNodeWithText("Done").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Move up").assertCountEquals(3)
        composeTestRule.onAllNodesWithContentDescription("Move down").assertCountEquals(3)

        // Disabled-at-ends: the first row's Move-up (already at index 0)
        // and the last row's Move-down (already at lastIndex) must be
        // disabled. This is the invariant that breaks if canMoveUp /
        // canMoveDown arithmetic regresses.
        composeTestRule.onAllNodesWithContentDescription("Move up").onFirst().assertIsNotEnabled()
        composeTestRule.onAllNodesWithContentDescription("Move down").onLast().assertIsNotEnabled()

        // Tap Move-down on the first favorite (Inbox). The handler looks
        // up the live index, swaps positions 0 and 1, and dispatches
        // onReorderFavorite with the new serverId order (the
        // FolderDrawer's onReorderFavorite contract is server IDs, not
        // display names, matching how every screen upstream persists the
        // new ordering).
        composeTestRule.onAllNodesWithContentDescription("Move down")[0].performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, reorderCalls.size)
        assertEquals(listOf("Sent", "INBOX", "Archive"), reorderCalls.first())

        // Exit edit mode: move arrows disappear, the Edit chip returns.
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.onNodeWithText("Edit").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Move up").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription("Move down").assertCountEquals(0)
    }
}
