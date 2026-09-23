package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.R
import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.screens.chat.ChatHomeThreadRow
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import app.knotwork.design.R as KnotworkR

/**
 * Covers the drawer surface plus structural composer
 * presence in the secondary states.
 *
 * The drawer swipe-from-left gesture itself is owned by the catalog
 * `AnimatedVisibility` block — we drive the state-driven open/close
 * variant instead of simulating a swipe. The new-thread pipeline picker
 * is rendered through a `ModalBottomSheet` that the screen owns
 * (`ChatHomeScreen.kt:454`); picking a pipeline routes through
 * `createNewSessionWithPipeline(pipelineId)`.
 *
 * The "IME overlap" task bullet maps here to a structural assertion: in
 * each secondary state (HITL / Clarification / Console expanded) the
 * composer's Send affordance remains in the semantics tree. Genuine IME
 * regression coverage would require a screenshot pass and is intentionally
 * out of scope.
 */
class ChatHomeDrawerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun drawerOpenState_rendersThreadsAndStandardFooterRows() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.DrawerOpen,
            initialThreadRows = listOf(
                ChatHomeThreadRow(
                    id = "thread-active",
                    title = "Active chat",
                    subtitle = "Now",
                    selected = true,
                    active = true,
                ),
                ChatHomeThreadRow(
                    id = "thread-other",
                    title = "Older chat",
                    subtitle = "Yesterday",
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sessionsHeader = ctx.getString(KnotworkR.string.knotwork_chat_home_drawer_sessions_header)
        val newChatLabel = ctx.getString(KnotworkR.string.knotwork_chat_home_drawer_new_thread)
        val importTitle = ctx.getString(KnotworkR.string.knotwork_chat_home_drawer_import_title)
        val settingsTitle = ctx.getString(KnotworkR.string.knotwork_chat_home_drawer_settings_title)

        composeTestRule.onNodeWithText(sessionsHeader).assertIsDisplayed()
        composeTestRule.onNodeWithText(newChatLabel).assertIsDisplayed()
        composeTestRule.onNodeWithText("Active chat").assertIsDisplayed()
        composeTestRule.onNodeWithText("Older chat").assertIsDisplayed()
        composeTestRule.onNodeWithText(importTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText(settingsTitle).assertIsDisplayed()
    }

    @Test
    fun drawerThreadRow_tap_invokesSelectThreadAndCloseDrawer() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.DrawerOpen,
            initialThreadRows = listOf(
                ChatHomeThreadRow(
                    id = "thread-other",
                    title = "Older chat",
                    subtitle = "Yesterday",
                ),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        composeTestRule.onNodeWithText("Older chat").performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { viewModel.selectThread("thread-other") }
        verify(atLeast = 1) { handles.threads.closeDrawer() }
    }

    @Test
    fun newChatPill_tap_opensPipelinePickerSheet() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.DrawerOpen,
            initialAvailablePipelines = listOf(
                PipelineSummary(id = "pipe-fast", name = "Fast assistant"),
                PipelineSummary(id = "pipe-deep", name = "Deep research"),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val newChatLabel = ctx.getString(KnotworkR.string.knotwork_chat_home_drawer_new_thread)
        composeTestRule.onNodeWithText(newChatLabel).performClick()
        composeTestRule.waitForIdle()

        val sheetTitle = ctx.getString(R.string.chat_new_thread_sheet_title)
        composeTestRule.onNodeWithText(sheetTitle).assertIsDisplayed()
        composeTestRule.onNodeWithText("Fast assistant").assertIsDisplayed()
        composeTestRule.onNodeWithText("Deep research").assertIsDisplayed()
    }

    @Test
    fun pipelinePickerCreate_withPicked_invokesCreateNewSessionWithPipelineId() {
        val (viewModel, handles) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.DrawerOpen,
            initialAvailablePipelines = listOf(
                PipelineSummary(id = "pipe-deep", name = "Deep research"),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val newChatLabel = ctx.getString(KnotworkR.string.knotwork_chat_home_drawer_new_thread)
        composeTestRule.onNodeWithText(newChatLabel).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Deep research").performClick()
        composeTestRule.waitForIdle()

        val createLabel = ctx.getString(R.string.chat_new_thread_sheet_create)
        composeTestRule.onNodeWithText(createLabel).performClick()
        composeTestRule.waitForIdle()

        verify(exactly = 1) { handles.threads.createNewSessionWithPipeline("pipe-deep") }
    }

    @Test
    fun composerSendAffordance_remainsPresent_inHitlConfirmState() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.HitlConfirm(risk = Risk.Sensitive),
            // A non-empty draft keeps the trailing affordance on Send: with an
            // empty composer the voice-input feature shows the Mic button instead.
            initialComposerValue = "draft",
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
        val sendCd = ctx.getString(KnotworkR.string.knotwork_composer_send)
        composeTestRule.onNodeWithContentDescription(sendCd).assertIsDisplayed()
    }

    @Test
    fun composerSendAffordance_remainsPresent_inClarificationState() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.Clarification,
            // Non-empty draft → Send affordance (empty composer shows Mic instead).
            initialComposerValue = "draft",
            initialPendingClarification = ClarificationRequest(
                id = "clar-x",
                sessionId = "session-1",
                question = "Pick a calendar",
                options = listOf("Work"),
                timeoutMs = 5_000L,
            ),
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sendCd = ctx.getString(KnotworkR.string.knotwork_composer_send)
        composeTestRule.onNodeWithContentDescription(sendCd).assertIsDisplayed()
    }

    @Test
    fun composerSendAffordance_remainsPresent_whenConsoleExpanded() {
        val (viewModel, _) = mockChatHomeViewModel(
            initialState = ChatHomeUiState.Idle,
            // Non-empty draft → Send affordance (empty composer shows Mic instead).
            initialComposerValue = "draft",
            initialConsoleSnap = ConsoleSnap.Partial,
        )

        composeTestRule.setContent {
            MaterialTheme { ChatHomeScreen(viewModel = viewModel) }
        }

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sendCd = ctx.getString(KnotworkR.string.knotwork_composer_send)
        composeTestRule.onNodeWithContentDescription(sendCd).assertIsDisplayed()
    }
}
