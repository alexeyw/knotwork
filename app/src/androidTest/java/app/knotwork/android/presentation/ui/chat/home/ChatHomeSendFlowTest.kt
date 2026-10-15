package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Covers the user-prompted send flow on the chat home
 * surface: composer value propagation, the Send icon tap firing
 * `sendMessage()`, and the `Idle → Generating → Idle` UI transitions that
 * follow.
 *
 * Renders [ChatHomeScreen] directly with a relaxed mock VM produced by
 * [mockChatHomeViewModel]; mutating [ChatHomeMockHandles.stateFlow]
 * between phases avoids re-stubbing each test.
 *
 * Verifies the UI side only — the orchestrator wiring that actually emits
 * `Generating` and the assistant reply lives in `ChatHomeViewModelTest`
 * under the unit-test source set.
 */
class ChatHomeSendFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun composerSendIcon_tap_invokesSendMessage() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.Idle,
            initialComposerValue = "Hello there",
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sendCd = ctx.getString(KnotworkR.string.knotwork_composer_send)
        composeTestRule.onNodeWithContentDescription(sendCd).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { viewModel.sendMessage() }
        // Composer value is hoisted via the VM — Send does not clear it on the
        // UI side; the VM clears it after persistence. We sanity-check the
        // flow is still readable after a click so the test fails loudly if a
        // future refactor moves clearing into the screen.
        assertEquals("Composer value should still be readable post-Send", "Hello there", handles.composerValue)
    }

    @Test
    fun stateFlipsToGenerating_rendersGeneratingLoader() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.Idle,
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val generatingLabel = ctx.getString(KnotworkR.string.knotwork_chat_home_generating_label)

        // Step 1 — Idle: the loader bubble should not be present.
        composeTestRule.onNodeWithText(generatingLabel).assertDoesNotExist()

        // Step 2 — flip to Generating; the loader bubble appears in the body
        // and the composer's trailing action morphs to the stop affordance.
        handles.setVisual(ChatHomeUiState.Generating())
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(generatingLabel).assertIsDisplayed()
        val stopCd = ctx.getString(KnotworkR.string.knotwork_composer_stop)
        composeTestRule.onNodeWithContentDescription(stopCd).assertIsDisplayed()
    }

    @Test
    fun stateFlipsGeneratingThenIdle_composerReturnsToSend() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.Generating(),
            initialComposerValue = "queued next turn",
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sendCd = ctx.getString(KnotworkR.string.knotwork_composer_send)
        val stopCd = ctx.getString(KnotworkR.string.knotwork_composer_stop)

        // Generating phase exposes the stop icon, not the send icon.
        composeTestRule.onNodeWithContentDescription(stopCd).assertIsDisplayed()

        // Flip back to Idle — the send icon should return.
        handles.setVisual(ChatHomeUiState.Idle)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription(sendCd).assertIsDisplayed()
    }

    @Test
    fun stopIcon_tap_invokesStopGeneration() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.Generating(),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val stopCd = ctx.getString(KnotworkR.string.knotwork_composer_stop)
        composeTestRule.onNodeWithContentDescription(stopCd).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { viewModel.stopGeneration() }
    }

    @Test
    fun errorState_rendersErrorTileWithRetryAction() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.Error(message = "Test failure"),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val retryLabel = ctx.getString(KnotworkR.string.knotwork_chat_home_error_retry)
        composeTestRule.onNodeWithText(retryLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText(retryLabel).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { viewModel.retryAfterError() }
    }
}
