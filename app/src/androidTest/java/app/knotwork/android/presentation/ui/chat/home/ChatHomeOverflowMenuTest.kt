package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.R
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Verifies the TopAppBar overflow menu wiring. The
 * menu is owned by [ChatHomeScreen] (not the catalog), so a fast Compose
 * test is enough — no Hilt graph required. The test pre-mocks the VM with
 * deterministic StateFlows via [mockChatHomeViewModel], taps the `⋮`
 * icon to open the menu, taps `Delete chat`, confirms the destructive
 * `AlertDialog`, and verifies `threads.deleteCurrentSession()` was called
 * exactly once (asserted on the delegate via `handles`, see
 * [ChatHomeMockHandles]).
 *
 * The second test pins the menu's roster. `Clear console` used to sit here as
 * well as on the console pane's own header — two doors onto one
 * `requestConsoleClear`, the overflow copy reachable even when the console had
 * never been opened. The contextual one survived; this asserts the duplicate
 * stays gone.
 */
class ChatHomeOverflowMenuTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun deleteFromOverflow_removesSession() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val overflowCd = ctx.getString(KnotworkR.string.knotwork_chat_home_action_overflow)
        val deleteItem = ctx.getString(R.string.chat_overflow_delete)
        val confirmLabel = ctx.getString(R.string.chat_delete_dialog_confirm)
        val (viewModel, handles) = mockChatHomeViewModel()

        composeTestRule.setContent {
            MaterialTheme {
                ChatHomeScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithContentDescription(overflowCd).performClick()
        composeTestRule.onNodeWithText(deleteItem).assertIsDisplayed()
        composeTestRule.onNodeWithText(deleteItem).performClick()
        composeTestRule.onNodeWithText(confirmLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(confirmLabel).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.threads.deleteCurrentSession() }
    }

    @Test
    fun overflow_offersNoClearConsoleDuplicate() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val overflowCd = ctx.getString(KnotworkR.string.knotwork_chat_home_action_overflow)
        val (viewModel, _) = mockChatHomeViewModel()

        composeTestRule.setContent {
            MaterialTheme {
                ChatHomeScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithContentDescription(overflowCd).performClick()

        // The surviving rows.
        composeTestRule.onNodeWithText(ctx.getString(R.string.chat_overflow_export)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.chat_overflow_save_to_file)).assertIsDisplayed()
        composeTestRule.onNodeWithText(ctx.getString(R.string.chat_overflow_delete)).assertIsDisplayed()
        // Clearing the console is reachable only from the console itself.
        composeTestRule.onNodeWithText("Clear console").assertDoesNotExist()
    }
}
