package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.design.components.chips.Risk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Covers the full HITL approval flow through
 * [ChatHomeScreen]. The complementary [HitlIntegrationTest] exercises the
 * catalog card in isolation; this suite additionally verifies that the
 * Allow / Reject taps dispatch into the real ChatHomeViewModel hooks
 * (`approveTool` / `rejectTool`) and that the destructive typed-confirm
 * gate routes through the screen-level state plumbing.
 */
class ChatHomeHitlScreenFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sensitive_allowOnce_invokesApproveTool() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.HitlConfirm(risk = Risk.Sensitive),
            initialPendingTool = HitlPending(
                toolName = "calendar.create_event",
                arguments = "{}",
                risk = ToolRisk.SENSITIVE,
                requestId = "request-1",
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val allowLabel = ctx.getString(KnotworkR.string.knotwork_hitl_action_allow_once)
        composeTestRule.onNodeWithText(allowLabel).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.hitl.approveTool() }
    }

    @Test
    fun sensitive_reject_invokesRejectTool() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.HitlConfirm(risk = Risk.Sensitive),
            initialPendingTool = HitlPending(
                toolName = "calendar.create_event",
                arguments = "{}",
                risk = ToolRisk.SENSITIVE,
                requestId = "request-1",
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rejectLabel = ctx.getString(KnotworkR.string.knotwork_hitl_action_reject)
        composeTestRule.onNodeWithText(rejectLabel).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.hitl.rejectTool() }
    }

    @Test
    fun destructive_allowButton_disabledUntilTypedConfirmMatches() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.HitlConfirm(risk = Risk.Destructive),
            initialPendingTool = HitlPending(
                toolName = "fs.delete_file",
                arguments = "{\"path\":\"/tmp/x\"}",
                risk = ToolRisk.DESTRUCTIVE,
                requestId = "request-1",
            ),
            initialPendingTypedConfirm = "",
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val allowLabel = ctx.getString(KnotworkR.string.knotwork_hitl_action_allow_once)
        // FlowRow inside the card duplicates Reject + Allow when nested in
        // the screen-level scaffold's overflow + the card — use the first
        // matching node.
        composeTestRule.onAllNodesWithText(allowLabel)[0].assertIsNotEnabled()

        // Flip the typed-confirm flow to the magic word; the gate opens.
        handles.setTypedConfirm("yes")
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText(allowLabel)[0].assertIsEnabled()
    }

    @Test
    fun destructive_allowAfterTypedConfirm_invokesApproveTool() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.HitlConfirm(risk = Risk.Destructive),
            initialPendingTool = HitlPending(
                toolName = "fs.delete_file",
                arguments = "{\"path\":\"/tmp/x\"}",
                risk = ToolRisk.DESTRUCTIVE,
                requestId = "request-1",
            ),
            initialPendingTypedConfirm = "yes",
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val allowLabel = ctx.getString(KnotworkR.string.knotwork_hitl_action_allow_once)
        composeTestRule.onAllNodesWithText(allowLabel)[0].performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.hitl.approveTool() }
    }

    @Test
    fun hitlState_rendersPendingToolName() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.HitlConfirm(risk = Risk.Sensitive),
            initialPendingTool = HitlPending(
                toolName = "calendar.create_event",
                arguments = "{}",
                risk = ToolRisk.SENSITIVE,
                requestId = "request-1",
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        // The live HITL row surfaces the actual tool name passed via
        // `pendingTool` — verifying that proves the VM → card wiring works
        // end-to-end and the screen isn't silently using the fixture row.
        // Exactly once: a pending call carries no description of its own, so
        // `liveHitlRow` (ChatHomeStateMapping.kt) leaves `summary` empty and the
        // card omits that line. It used to repeat the tool name there, which
        // printed the id twice and read as a rendering fault.
        composeTestRule
            .onAllNodesWithText("calendar.create_event")
            .assertCountEquals(1)
    }
}
