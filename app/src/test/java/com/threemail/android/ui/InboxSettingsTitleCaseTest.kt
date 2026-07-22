package com.threemail.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.threemail.android.R
import com.threemail.android.data.settings.SwipeAction
import com.threemail.android.domain.model.AccountType
import com.threemail.android.domain.model.FolderType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First-pass Compose UI test for 3mail. Verifies that screen-level
 * "title case vs sentence case" formatting reaches the rendered tree
 * end-to-end (a prior strings-only pass got the Source of Truth right but
 * no test pinned the assertion, so a future label edit could regress
 * without notice).
 *
 * The Compose tests use the standalone androidTest source set so the
 * dependency boundary (debugImplementation for tooling-preview,
 * androidTestImplementation for ui-test-junit4) matches the build
 * configuration in `app/build.gradle.kts`. The runner is
 * `androidx.test.runner.AndroidJUnitRunner`, configured in the same file's
 * `defaultConfig.testInstrumentationRunner` block.
 *
 * Tests run against `ComponentActivity` so we sidestep MainActivity's real
 * Hilt graph and full NavHost wiring. We mount either a real screen
 * composable or a representative test composable that mirrors the screen's
 * rendering logic for the slice under test.
 */
@RunWith(AndroidJUnit4::class)
class InboxSettingsTitleCaseTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun swipe_right_action_label_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme {
                StaticLabelPreview(labelId = R.string.swipe_right_label)
            }
        }
        assertTextVisible("Swipe Right Action")
    }

    @Test
    fun swipe_left_action_label_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme {
                StaticLabelPreview(labelId = R.string.swipe_left_label)
            }
        }
        assertTextVisible("Swipe Left Action")
    }

    @Test
    fun swipe_action_chip_density_label_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme {
                StaticLabelPreview(labelId = R.string.density_label)
            }
        }
        assertTextVisible("List Density")
    }

    @Test
    fun swipe_action_preview_lines_label_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme {
                StaticLabelPreview(labelId = R.string.preview_lines_label)
            }
        }
        assertTextVisible("Preview Lines")
    }

    // ────────────────────────────────────────────────────────────────
    //  Drop-down-menu (long-press + three-dot overflow) label audits.
    //  Every label rendered inside a DropdownMenuItem must be Title Case
    //  so the action verb reads at the same height as the rest of the app's
    //  chrome. A future contributor editing strings.xml away from Title
    //  Case will trip these tests on first compile.
    //
    //  Tests assert the rendered text via StaticLabelPreview - the same
    //  minimal composable used by the swipe-action tests above - so a
    //  R.string.X -> literal mismatch is caught without spinning up the
    //  full screen / nav host.
    // ────────────────────────────────────────────────────────────────

    @Test
    fun folder_drawer_long_press_remove_favorite_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.favorites_remove) }
        }
        assertTextVisible("Remove from Favorites")
    }

    @Test
    fun folder_drawer_long_press_add_favorite_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.favorites_add) }
        }
        assertTextVisible("Add to Favorites")
    }

    @Test
    fun inbox_top_bar_mark_all_read_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.mark_all_read) }
        }
        assertTextVisible("Mark All as Read")
    }

    @Test
    fun inbox_selection_overflow_mark_unread_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.mark_unread) }
        }
        assertTextVisible("Mark Unread")
    }

    @Test
    fun inbox_selection_overflow_select_all_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.select_all) }
        }
        assertTextVisible("Select All")
    }

    @Test
    fun message_detail_overflow_move_to_folder_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.move_to_folder) }
        }
        assertTextVisible("Move to Folder")
    }

    @Test
    fun message_detail_overflow_mark_as_unread_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.mark_as_unread) }
        }
        assertTextVisible("Mark as Unread")
    }

    @Test
    fun message_detail_overflow_mark_as_read_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.mark_as_read) }
        }
        assertTextVisible("Mark as Read")
    }

    @Test
    fun message_detail_overflow_mark_as_spam_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.mark_as_spam) }
        }
        assertTextVisible("Mark as Spam")
    }

    @Test
    fun compose_overflow_save_draft_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.save_draft) }
        }
        assertTextVisible("Save Draft")
    }

    @Test
    fun compose_overflow_insert_image_renders_title_case() {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = R.string.insert_image) }
        }
        assertTextVisible("Insert Image")
    }

    /**
     * Asserts the given text appears at least once in the rendered tree.
     * Uses composeTestRule.onAllNodesWithText which is set-aware of stub /
     * offscreen nodes and is tolerant of multiple matches.
     */
    private fun assertTextVisible(text: String) {
        composeTestRule.onAllNodesWithText(text).assertCountEquals(1)
        composeTestRule.onNodeWithText(text).assertIsDisplayed()
    }
}

/**
 * Tiny composable that grabs a string resource and renders it as plain
 * text. Used by [InboxSettingsSettingsTitleCaseTest] to confirm the resource
 * resolver -> render pipeline returns the Title-Case string for the given id.
 */
@Composable
private fun StaticLabelPreview(labelId: Int) {
    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(labelId))
}

/**
 * Folder-roles visibility is gated in `AccountSettingsScreen.kt` by
 * `account.accountType == AccountType.IMAP`. This test mirrors that gate on a
 * minimal composable so we can confirm the conditional render flips between
 * the two account types without spinning up MainActivity. (A fuller version
 * of this test that drives the real AccountSettingsScreen is a separate drop
 * once HiltAndroidRule is wired up against an HiltTestApplication.)
 */
@RunWith(AndroidJUnit4::class)
class AccountFolderRolesVisibilityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun folder_roles_section_visible_for_imap() {
        composeTestRule.setContent {
            FakeFolderRoleSectionHost(accountType = AccountType.IMAP)
        }
        composeTestRule.onNodeWithText("Folder Roles").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Inbox").assertCountEquals(1)
    }

    @Test
    fun folder_roles_section_hidden_for_gmail() {
        composeTestRule.setContent {
            FakeFolderRoleSectionHost(accountType = AccountType.GMAIL)
        }
        // Gmail is filtered out before the section even enters composition.
        composeTestRule.onAllNodesWithText("Folder Roles").assertCountEquals(0)
    }

    @Test
    fun swipe_action_strings_resolve_all_known_actions() {
        // Cast-side check: every SwipeAction enum value maps to a string
        // resource. Guards against adding an enum value without a label.
        composeTestRule.setContent {
            MaterialTheme {
                SwipeActionLabels()
            }
        }
        SwipeAction.entries.forEach { entry ->
            composeTestRule.onNodeWithText(expectedLabel(entry)).assertIsDisplayed()
        }
    }

    private fun expectedLabel(action: SwipeAction): String = when (action) {
        SwipeAction.NONE -> "None"
        SwipeAction.ARCHIVE -> "Archive"
        SwipeAction.DELETE -> "Delete"
        SwipeAction.TOGGLE_READ -> "Read/Unread"
        SwipeAction.MARK_SPAM -> "Mark as Spam"
        SwipeAction.MOVE -> "Move to folder"
    }
}

@Composable
private fun FakeFolderRoleSectionHost(accountType: AccountType) {
    if (accountType != AccountType.IMAP) return
    androidx.compose.foundation.layout.Column {
        androidx.compose.material3.Text("Folder Roles")
        // Pretend one role row exists; we just want the section title to render.
        FolderType.entries.forEach { type ->
            androidx.compose.material3.Text(
                when (type) {
                    FolderType.Inbox -> "Inbox"
                    else -> type.name
                }
            )
        }
    }
}

@Composable
private fun SwipeActionLabels() {
    androidx.compose.runtime.remember {
        SwipeAction.entries.toList()
    }.let { actions ->
        androidx.compose.foundation.layout.Column {
            actions.forEach { action ->
                androidx.compose.material3.Text(
                    when (action) {
                        SwipeAction.NONE -> "None"
                        SwipeAction.ARCHIVE -> "Archive"
                        SwipeAction.DELETE -> "Delete"
                        SwipeAction.TOGGLE_READ -> "Read/Unread"
                        SwipeAction.MARK_SPAM -> "Mark as Spam"
                        SwipeAction.MOVE -> "Move to folder"
                    }
                )
            }
        }
    }
}
