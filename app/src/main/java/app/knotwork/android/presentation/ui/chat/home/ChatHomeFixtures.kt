package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.design.screens.chat.ChatHomeSamplePromptCard
import app.knotwork.design.screens.chat.ChatHomeThreadRow

/**
 * Bundle of resolved user-facing strings consumed by [toViewState] while
 * the orchestrator integration is pending. Holds every stub fixture +
 * agent-status line that would otherwise be hardcoded inside the mapping
 * function.
 *
 * Production usage: build via [rememberChatHomeFixtures] from
 * [ChatHomeScreen] so every string flows from `res/values/strings_chat.xml`.
 * Tests / previews can use [forTesting] to avoid pulling in a Resources
 * instance.
 *
 * @property statusIdle agent-status pill text rendered while the surface
 * is in `ChatHomeUiState.Empty` or `.Idle`.
 * @property statusGenerating pill text while the assistant is streaming.
 * @property statusPreparingModel pill text while the active model is loading
 * before an auto-triggered send (`Generating(preparingModel = true)`).
 * @property statusWaitingInQueue pill text while the run waits behind another run
 * (`Generating(waitingInQueue = true)`).
 * @property loaderPreparingModel label of the loader bubble while the model loads.
 * @property loaderWaitingInQueue label of the loader bubble while the run is queued.
 * @property statusHitl pill text while a HITL approval is pending.
 * @property statusClarification pill text while the assistant is waiting on
 * a clarification reply.
 * @property statusError pill text rendered in `ChatHomeUiState.Error`.
 * @property sessionRows stub thread rows surfaced inside the drawer
 * overlay (`ChatHomeUiState.DrawerOpen`).
 * @property suggestionCards generic, pipeline-agnostic fallback suggestion
 * cards rendered in the empty surface body when the active pipeline declares
 * no starter prompts of its own.
 */
data class ChatHomeFixtures(
    val statusIdle: String,
    val statusGenerating: String,
    val statusPreparingModel: String,
    val statusWaitingInQueue: String,
    val loaderPreparingModel: String,
    val loaderWaitingInQueue: String,
    val statusHitl: String,
    val statusClarification: String,
    val statusError: String,
    val sessionRows: List<ChatHomeThreadRow>,
    val suggestionCards: List<ChatHomeSamplePromptCard>,
) {
    companion object {
        /**
         * Test-only fixture used by unit tests that exercise [toViewState]
         * without paying for a `@Composable` Resources lookup. The
         * placeholder strings here are never user-visible.
         */
        fun forTesting(): ChatHomeFixtures = ChatHomeFixtures(
            statusIdle = "idle",
            statusGenerating = "generating",
            statusPreparingModel = "preparing_model",
            statusWaitingInQueue = "waiting_in_queue",
            loaderPreparingModel = "loader_preparing_model",
            loaderWaitingInQueue = "loader_waiting_in_queue",
            statusHitl = "hitl",
            statusClarification = "clarification",
            statusError = "error",
            sessionRows = listOf(
                ChatHomeThreadRow(id = "stub1", title = "stub 1", subtitle = "—", active = true),
                ChatHomeThreadRow(id = "stub2", title = "stub 2", subtitle = "—"),
            ),
            suggestionCards = listOf(
                ChatHomeSamplePromptCard(id = "stub-card1", title = "stub card 1", toolsUsed = "tool_a"),
                ChatHomeSamplePromptCard(id = "stub-card2", title = "stub card 2", toolsUsed = "tool_b"),
            ),
        )
    }
}

/**
 * Resolves [ChatHomeFixtures] from `res/values/strings_chat.xml`. Called
 * once from [ChatHomeScreen] and passed into [toViewState]; consumers
 * that recompose with the same locale receive the same instance.
 */
@Composable
fun rememberChatHomeFixtures(): ChatHomeFixtures = ChatHomeFixtures(
    statusIdle = stringResource(R.string.chat_home_status_idle),
    statusGenerating = stringResource(R.string.chat_home_status_generating),
    statusPreparingModel = stringResource(R.string.chat_home_status_preparing_model),
    statusWaitingInQueue = stringResource(R.string.chat_home_status_waiting_in_queue),
    loaderPreparingModel = stringResource(R.string.chat_home_loader_preparing_model),
    loaderWaitingInQueue = stringResource(R.string.chat_home_loader_waiting_in_queue),
    statusHitl = stringResource(R.string.chat_home_status_hitl),
    statusClarification = stringResource(R.string.chat_home_status_clarification),
    statusError = stringResource(R.string.chat_home_status_error),
    sessionRows = listOf(
        ChatHomeThreadRow(
            id = "t1",
            title = stringResource(R.string.chat_home_stub_session_1_title),
            subtitle = stringResource(R.string.chat_home_stub_session_1_subtitle),
            active = true,
        ),
        ChatHomeThreadRow(
            id = "t2",
            title = stringResource(R.string.chat_home_stub_session_2_title),
            subtitle = stringResource(R.string.chat_home_stub_session_2_subtitle),
        ),
        ChatHomeThreadRow(
            id = "t3",
            title = stringResource(R.string.chat_home_stub_session_3_title),
            subtitle = stringResource(R.string.chat_home_stub_session_3_subtitle),
        ),
        ChatHomeThreadRow(
            id = "t4",
            title = stringResource(R.string.chat_home_stub_session_4_title),
            subtitle = stringResource(R.string.chat_home_stub_session_4_subtitle),
        ),
        ChatHomeThreadRow(
            id = "t5",
            title = stringResource(R.string.chat_home_stub_session_5_title),
            subtitle = stringResource(R.string.chat_home_stub_session_5_subtitle),
        ),
    ),
    suggestionCards = listOf(
        ChatHomeSamplePromptCard(
            id = "card1",
            title = stringResource(R.string.chat_home_suggestion_card_1_title),
            toolsUsed = stringResource(R.string.chat_home_suggestion_card_1_tools),
        ),
        ChatHomeSamplePromptCard(
            id = "card2",
            title = stringResource(R.string.chat_home_suggestion_card_2_title),
            toolsUsed = stringResource(R.string.chat_home_suggestion_card_2_tools),
        ),
        ChatHomeSamplePromptCard(
            id = "card3",
            title = stringResource(R.string.chat_home_suggestion_card_3_title),
            toolsUsed = stringResource(R.string.chat_home_suggestion_card_3_tools),
        ),
    ),
)
