package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.HitlConfirmationModel
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.screens.chat.ChatHomeCallbacks
import app.knotwork.design.screens.chat.ChatHomeContent
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import app.knotwork.design.screens.chat.ChatHomeViewState
import app.knotwork.design.screens.chat.ChatHomeVisualState
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests covering the chat-home HITL confirmation card
 * rendered inline in the assistant message stream.
 *
 * Each test wires [ChatHomeContent] directly with a synthetic view state
 * holding one trailing [ChatContent.Confirmation] row and a callbacks
 * bundle that records the events the user produces.
 */
class HitlIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sensitive_render_andApproveRejectClicks_dispatchCallbacks() {
        var approveCount = 0
        var rejectCount = 0

        composeTestRule.setContent {
            ChatHomeContent(
                state = viewStateWithHitl(risk = Risk.Sensitive, toolName = "calendar.create_event"),
                callbacks = ChatHomeCallbacks(
                    onHitlAllowOnce = { approveCount++ },
                    onHitlReject = { rejectCount++ },
                ),
            )
        }

        composeTestRule.onNodeWithText("calendar.create_event").assertIsDisplayed()
        composeTestRule.onNodeWithText(ALLOW_LABEL).performClick()
        composeTestRule.onNodeWithText(REJECT_LABEL).performClick()

        assert(approveCount == 1) { "Approve callback should fire once, got $approveCount" }
        assert(rejectCount == 1) { "Reject callback should fire once, got $rejectCount" }
    }

    @Test
    fun destructive_allowButton_disabledUntilTypedConfirmMatches() {
        composeTestRule.setContent {
            ChatHomeContent(
                state = viewStateWithHitl(
                    risk = Risk.Destructive,
                    toolName = "fs.delete_file",
                    pendingTypedConfirm = "",
                ),
                callbacks = ChatHomeCallbacks(),
            )
        }
        composeTestRule.onNodeWithText(ALLOW_LABEL).assertIsNotEnabled()
    }

    @Test
    fun destructive_allowButton_enabledOnceTypedConfirmMatches() {
        composeTestRule.setContent {
            ChatHomeContent(
                state = viewStateWithHitl(
                    risk = Risk.Destructive,
                    toolName = "fs.delete_file",
                    pendingTypedConfirm = "yes",
                ),
                callbacks = ChatHomeCallbacks(),
            )
        }
        composeTestRule.onNodeWithText(ALLOW_LABEL).assertIsEnabled()
    }

    @Test
    fun readonly_render_showsToolNameWithoutTypedConfirmRow() {
        composeTestRule.setContent {
            ChatHomeContent(
                state = viewStateWithHitl(risk = Risk.Readonly, toolName = "calendar.list_events"),
                callbacks = ChatHomeCallbacks(),
            )
        }
        composeTestRule.onNodeWithText("calendar.list_events").assertIsDisplayed()
        composeTestRule.onNodeWithText(ALLOW_LABEL).assertIsEnabled()
    }

    private fun viewStateWithHitl(risk: Risk, toolName: String, pendingTypedConfirm: String = ""): ChatHomeViewState {
        // The card surfaces both `toolName` (header) and `summary` (body).
        // Use a deliberately distinct summary so `onNodeWithText(toolName)`
        // resolves to exactly one node (the header). The production row carries
        // an empty summary instead, and that the tool name then appears once is
        // covered by ChatHomeHitlScreenFlowTest.hitlState_rendersPendingToolName.
        val row = ChatHomeMessageRow(
            id = "a-hitl",
            role = ChatRole.Assistant,
            content = ChatContent.Confirmation(
                model = HitlConfirmationModel(
                    risk = risk,
                    toolName = toolName,
                    summary = "Run $toolName with the given arguments.",
                    arguments = mapOf("path" to "\"/tmp/x\""),
                    timestamp = "09:16",
                ),
            ),
            metadata = ChatMetadata(timestamp = "09:16", model = "TestModel"),
        )
        return ChatHomeViewState(
            visualState = ChatHomeVisualState.HitlConfirm,
            threadTitle = "Test thread",
            modelName = "TestModel",
            messages = listOf(row),
            pendingTypedConfirm = pendingTypedConfirm,
        )
    }

    private companion object {
        // Mirrors `knotwork_hitl_action_allow_once` / `knotwork_hitl_action_reject` in
        // the catalog string resources. The labels are stable enough to hard-code in
        // the instrumented test; if they drift the matcher catches it as a regression.
        const val ALLOW_LABEL: String = "Allow once"
        const val REJECT_LABEL: String = "Reject"
    }
}
