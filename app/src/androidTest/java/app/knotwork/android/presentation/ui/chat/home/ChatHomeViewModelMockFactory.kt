@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")
// File hosts both the `mockChatHomeViewModel` factory function (primary
// export) and its sibling `ChatHomeMockHandles` class. Naming after the
// factory is preferred since tests reach for it by name.

package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow
import app.knotwork.design.screens.chat.ChatHomeConsoleState
import app.knotwork.design.screens.chat.ChatHomeThreadRow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Mutable handle on the single [MutableStateFlow] of [ChatHomeScreenState]
 * that backs the mocked [ChatHomeViewModel], plus the per-feature delegate
 * mocks the screen dispatches through. Tests mutate the flow directly
 * (via [state] `.update { it.copy(...) }` or the convenience setters) to
 * drive the screen through state transitions, then call
 * `composeTestRule.waitForIdle()` to recompose.
 *
 * Exposing the mutable handle (rather than just the readable mock) keeps
 * the tests free of MockK re-stubbing between phases of a single scenario
 * (e.g. send → Generating → Idle).
 *
 * The delegate mocks are exposed so a test can `verify`/`every` **directly**
 * on the delegate (`handles.hitl.approveTool()`) instead of chaining through
 * the getter (`viewModel.hitl.approveTool()`). The chained form makes MockK
 * apply the verification count to the intermediate `getHitl()` too, which the
 * screen reads many times per composition — so an `exactly = 1` chain fails
 * with "N matching calls found, but needs exactly 1". Verifying on the
 * captured delegate sidesteps that entirely.
 */
internal class ChatHomeMockHandles(
    /** The single source-of-truth flow the mocked ViewModel returns from `state`. */
    val state: MutableStateFlow<ChatHomeScreenState>,
    /** The pipeline-binding delegate the screen reads as `viewModel.pipelineBinding`. */
    val pipelineBinding: ChatHomePipelineBindingDelegate,
    /** The threads/drawer delegate the screen reads as `viewModel.threads`. */
    val threads: ChatHomeThreadsDelegate,
    /** The reattach delegate the screen reads as `viewModel.reattach`. */
    val reattach: ChatHomeReattachDelegate,
    /** The HITL delegate the screen reads as `viewModel.hitl`. */
    val hitl: ChatHomeHitlDelegate,
    /** The console delegate the screen reads as `viewModel.console`. */
    val console: ChatHomeConsoleDelegate,
    /** The voice-input delegate the screen reads as `viewModel.voice`. */
    val voice: ChatHomeVoiceDelegate,
    /** The attachment delegate the screen reads as `viewModel.attachments`. */
    val attachments: ChatHomeAttachmentDelegate,
    /** The export/import/memory transfer delegate the screen reads as `viewModel.transfer`. */
    val transfer: ChatHomeTransferDelegate,
) {
    /** Replaces the sealed visual state, leaving every other slice untouched. */
    fun setVisual(visual: ChatHomeUiState) {
        state.update { it.copy(visual = visual) }
    }

    /** Replaces the pending clarification snapshot. */
    fun setPendingClarification(request: ClarificationRequest?) {
        state.update { it.copy(pending = it.pending.copy(clarification = request)) }
    }

    /** Replaces the typed-confirm input next to the Destructive HITL row. */
    fun setTypedConfirm(value: String) {
        state.update { it.copy(composer = it.composer.copy(typedConfirm = value)) }
    }

    /** Current composer draft — convenience accessor for assertions. */
    val composerValue: String
        get() = state.value.composer.value
}

/**
 * Builds a relaxed [ChatHomeViewModel] mock whose consolidated
 * [ChatHomeViewModel.state] flow is seeded from the supplied initial
 * values, plus a sibling [ChatHomeMockHandles] bundle that lets the test
 * mutate any slice without re-stubbing.
 *
 * Defaults match what [ChatHomeOverflowMenuTest] used before this helper
 * was extracted, so existing assertions continue to pass.
 *
 * Tests pass per-test overrides (`initialState = ChatHomeUiState.Generating`,
 * `initialThreadRows = listOf(...)`) for the values they actually care
 * about; everything else stays on the boring defaults.
 */
internal fun mockChatHomeViewModel(
    initialState: ChatHomeUiState = ChatHomeUiState.Idle,
    initialThreadTitle: String = "Test chat",
    initialModelName: String = "Test model",
    initialComposerValue: String = "",
    initialPendingTypedConfirm: String = "",
    initialPendingTool: HitlPending? = null,
    initialPendingClarification: ClarificationRequest? = null,
    initialConsoleLines: List<ConsoleLine> = emptyList(),
    initialConsoleVars: List<ConsoleVarRow> = emptyList(),
    initialConsoleTraces: List<ConsoleTraceSpan> = emptyList(),
    initialConsoleTab: ConsoleTab = ConsoleTab.Logs,
    initialConsoleSnap: ConsoleSnap? = null,
    initialConsoleSearchQuery: String? = null,
    initialConsoleFilter: ConsoleFilter = ConsoleFilter.allOn,
    initialConsoleClearConfirm: Boolean = false,
    initialThreadRows: List<ChatHomeThreadRow> = listOf(
        ChatHomeThreadRow(
            id = "active-id",
            title = "Test chat",
            subtitle = "Now",
            selected = true,
            active = true,
        ),
    ),
    initialAvailablePipelines: List<PipelineSummary> = emptyList(),
    initialInstalledModels: List<LocalModel> = emptyList(),
    initialPipelineName: String? = "default",
): Pair<ChatHomeViewModel, ChatHomeMockHandles> {
    val stateFlow = MutableStateFlow(
        ChatHomeScreenState(
            visual = initialState,
            composer = ChatHomeComposerState(
                value = initialComposerValue,
                typedConfirm = initialPendingTypedConfirm,
            ),
            console = ChatHomeConsoleState(
                snap = initialConsoleSnap,
                tab = initialConsoleTab,
                logs = initialConsoleLines,
                vars = initialConsoleVars,
                traces = initialConsoleTraces,
                filter = initialConsoleFilter,
                searchQuery = initialConsoleSearchQuery,
            ),
            consoleClearConfirmRequested = initialConsoleClearConfirm,
            pending = ChatHomePendingState(
                tool = initialPendingTool,
                clarification = initialPendingClarification,
            ),
            thread = ChatHomeThreadState(
                title = initialThreadTitle,
                rows = initialThreadRows,
                currentSessionId = "active-id",
            ),
            model = ChatHomeModelState(
                name = initialModelName,
                installed = initialInstalledModels,
            ),
            pipelineName = initialPipelineName,
            availablePipelines = initialAvailablePipelines,
        ),
    )

    val vm = mockk<ChatHomeViewModel>(relaxed = true)
    every { vm.state } returns stateFlow
    val pipelineBindingDelegate = mockk<ChatHomePipelineBindingDelegate>(relaxed = true)
    every { pipelineBindingDelegate.pipelineFallbackEvents } returns MutableSharedFlow()
    every { pipelineBindingDelegate.currentPipelineId() } returns null
    every { vm.pipelineBinding } returns pipelineBindingDelegate
    val threadsDelegate = mockk<ChatHomeThreadsDelegate>(relaxed = true)
    every { vm.threads } returns threadsDelegate
    val reattachDelegate = mockk<ChatHomeReattachDelegate>(relaxed = true)
    every { reattachDelegate.resumeFeedbackEvents } returns MutableSharedFlow()
    every { vm.reattach } returns reattachDelegate
    val hitlDelegate = mockk<ChatHomeHitlDelegate>(relaxed = true)
    every { vm.hitl } returns hitlDelegate
    // Delegates (variant B); the screen calls `vm.<delegate>.*`. Stub delegate
    // mocks whose collected SharedFlows never complete (see the note below).
    val consoleDelegate = mockk<ChatHomeConsoleDelegate>(relaxed = true)
    every { consoleDelegate.consoleSnackbarEvents } returns MutableSharedFlow()
    every { vm.console } returns consoleDelegate
    val voiceDelegate = mockk<ChatHomeVoiceDelegate>(relaxed = true)
    every { voiceDelegate.voiceErrorEvents } returns MutableSharedFlow()
    every { vm.voice } returns voiceDelegate
    val attachmentDelegate = mockk<ChatHomeAttachmentDelegate>(relaxed = true)
    every { attachmentDelegate.attachmentErrorEvents } returns MutableSharedFlow()
    every { attachmentDelegate.attachmentReplacedEvents } returns MutableSharedFlow()
    every { vm.attachments } returns attachmentDelegate
    val transferDelegate = mockk<ChatHomeTransferDelegate>(relaxed = true)
    every { transferDelegate.exportEvents } returns MutableSharedFlow()
    every { transferDelegate.importErrorEvents } returns MutableSharedFlow()
    every { transferDelegate.memorySaveEvents } returns MutableSharedFlow()
    every { vm.transfer } returns transferDelegate

    return vm to ChatHomeMockHandles(
        state = stateFlow,
        pipelineBinding = pipelineBindingDelegate,
        threads = threadsDelegate,
        reattach = reattachDelegate,
        hitl = hitlDelegate,
        console = consoleDelegate,
        voice = voiceDelegate,
        attachments = attachmentDelegate,
        transfer = transferDelegate,
    )
}
