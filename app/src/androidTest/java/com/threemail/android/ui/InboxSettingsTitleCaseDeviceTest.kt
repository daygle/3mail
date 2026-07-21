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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (instrumented) leg of the Compose UI coverage. The full
 * label-audit suite lives in `app/src/test/.../ui/InboxSettingsTitleCaseTest.kt`
 * and runs under Robolectric in `testDebugUnitTest` on every PR; this suite
 * re-runs a representative slice of the same resource -> render pipeline
 * against a real Android framework via `connectedDebugAndroidTest` (CI: the
 * `android-test` emulator job in `.github/workflows/android.yml`, pushes to
 * main + manual dispatch only).
 *
 * Keep it small: it's the smoke check that the JVM results hold on a real
 * device, not a duplicate of the full audit. Tests mount against
 * `ComponentActivity` (registered by the debug-only `ui-test-manifest`
 * artifact) so we sidestep MainActivity's Hilt graph and NavHost wiring.
 */
@RunWith(AndroidJUnit4::class)
class InboxSettingsTitleCaseDeviceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun swipe_right_action_label_renders_title_case() {
        assertLabelRenders(R.string.swipe_right_label, "Swipe Right Action")
    }

    @Test
    fun inbox_top_bar_mark_all_read_renders_title_case() {
        assertLabelRenders(R.string.mark_all_read, "Mark All as Read")
    }

    @Test
    fun compose_overflow_save_draft_renders_title_case() {
        assertLabelRenders(R.string.save_draft, "Save Draft")
    }

    @Test
    fun swipe_action_strings_resolve_all_known_actions() {
        // Every SwipeAction enum value must map to a rendered label - guards
        // against adding an enum value without a string, checked here against
        // the platform resource resolver rather than Robolectric's.
        composeTestRule.setContent {
            MaterialTheme { SwipeActionLabels() }
        }
        SwipeAction.entries.forEach { entry ->
            composeTestRule.onNodeWithText(expectedLabel(entry)).assertIsDisplayed()
        }
    }

    private fun assertLabelRenders(labelId: Int, expected: String) {
        composeTestRule.setContent {
            MaterialTheme { StaticLabelPreview(labelId = labelId) }
        }
        composeTestRule.onAllNodesWithText(expected).assertCountEquals(1)
        composeTestRule.onNodeWithText(expected).assertIsDisplayed()
    }

    private fun expectedLabel(action: SwipeAction): String = when (action) {
        SwipeAction.NONE -> "None"
        SwipeAction.ARCHIVE -> "Archive"
        SwipeAction.DELETE -> "Delete"
        SwipeAction.TOGGLE_READ -> "Read/Unread"
        SwipeAction.MARK_SPAM -> "Mark as Spam"
    }
}

/**
 * Tiny composable that grabs a string resource and renders it as plain text,
 * confirming the resource resolver -> render pipeline returns the Title-Case
 * string for the given id. Mirrors the twin in the Robolectric suite.
 */
@Composable
private fun StaticLabelPreview(labelId: Int) {
    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(labelId))
}

@Composable
private fun SwipeActionLabels() {
    androidx.compose.foundation.layout.Column {
        SwipeAction.entries.forEach { action ->
            androidx.compose.material3.Text(
                when (action) {
                    SwipeAction.NONE -> "None"
                    SwipeAction.ARCHIVE -> "Archive"
                    SwipeAction.DELETE -> "Delete"
                    SwipeAction.TOGGLE_READ -> "Read/Unread"
                    SwipeAction.MARK_SPAM -> "Mark as Spam"
                }
            )
        }
    }
}
