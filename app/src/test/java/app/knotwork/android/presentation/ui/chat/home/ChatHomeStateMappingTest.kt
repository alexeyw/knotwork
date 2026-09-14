package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.PipelineSamplePrompt
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunNoticeCause
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ComposerState
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow
import app.knotwork.design.components.console.SpanStatus
import app.knotwork.design.screens.chat.ChatHomeConsoleState
import app.knotwork.design.screens.chat.ChatHomeMessageRow
import app.knotwork.design.screens.chat.ChatHomeVisualState
import app.knotwork.design.screens.chat.RunTerminationToneUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin unit tests for [ChatHomeScreenState.toViewState] — the
 * boundary mapper between the aggregated screen state owned by `:app` and
 * the [app.knotwork.design.screens.chat.ChatHomeViewState] consumed by the
 * stateless `ChatHomeContent` in `:catalog`.
 *
 * Each test pins one variant of the sealed visual state and asserts the
 * downstream view-state has the right [ChatHomeVisualState], the right
 * trailing tile (HITL / clarification / error), and the right composer
 * machinery.
 */
class ChatHomeStateMappingTest {

    private val title = "Yesterday's deploy"
    private val model = "Gemma 2 · 2B"

    /** Builds a [ChatHomeScreenState] around [visual] with the shared test fixtures. */
    private fun screenState(
        visual: ChatHomeUiState,
        messages: List<ChatHomeMessageRow> = emptyList(),
        composerValue: String = "",
        pendingTypedConfirm: String = "",
        console: ChatHomeConsoleState = ChatHomeConsoleState(),
        activeSamplePrompts: List<PipelineSamplePrompt> = emptyList(),
        runNotice: RunNoticeCause? = null,
        pending: ChatHomePendingState = ChatHomePendingState(),
    ): ChatHomeScreenState = ChatHomeScreenState(
        visual = visual,
        composer = ChatHomeComposerState(value = composerValue, typedConfirm = pendingTypedConfirm),
        console = console,
        thread = ChatHomeThreadState(title = title),
        model = ChatHomeModelState(name = model),
        messages = messages,
        activeSamplePrompts = activeSamplePrompts,
        runNotice = runNotice,
        pending = pending,
    )

    // ─── Stopped runs: chosen vs unchosen ─────────────────────────────────────

    @Test
    fun `an untyped failure keeps the error tile and its retry`() {
        val view = screenState(ChatHomeUiState.Error("Tool 'http.get' failed")).toViewState()

        assertEquals(ChatHomeVisualState.Error, view.visualState)
        assertEquals("Tool 'http.get' failed", view.errorMessage)
        assertNull("nothing typed happened, so there is nothing to explain", view.termination)
    }

    @Test
    fun `a typed termination is explained instead of shown as an error`() {
        val view = screenState(
            ChatHomeUiState.Error(
                message = "step-ceiling: 15/15 steps",
                reason = RunTerminationReason.StepCeiling(limit = 15, spent = 15),
            ),
        ).toViewState()

        assertEquals(ChatHomeVisualState.Error, view.visualState)
        // The diagnostic is for the run record and the console, never the tile.
        assertNull("the diagnostic must not reach the user", view.errorMessage)
        val termination = requireNotNull(view.termination)
        assertEquals(RunTerminationToneUi.Limit, termination.tone)
        assertNotNull("a ceiling states its numbers", termination.meter)
        assertNotNull("a ceiling offers a way to change the outcome", termination.actionLabel)
    }

    @Test
    fun `a typed stop does not put the composer into its error state`() {
        val view = screenState(
            ChatHomeUiState.Error(
                message = "step-ceiling: 15/15 steps",
                reason = RunTerminationReason.StepCeiling(limit = 15, spent = 15),
            ),
        ).toViewState()

        // The composer's error banner is destructive-red. Using it here put an
        // alert glyph two inches below a tile explaining that a limit had done
        // its job — one event, two tones, on one screen.
        assertEquals(ComposerState.Idle, view.composerState)
        val termination = requireNotNull(view.termination)
        // The strip is clamped to two lines; sharing one sentence with the tile
        // is what cut the copy in half at large font scales.
        assertNotEquals(termination.title, termination.banner)
    }

    @Test
    fun `a typed stop's tile carries no explanatory sentence`() {
        // The run wrote its outcome into the conversation as it settled, so the
        // sentence is a few rows above this tile in the same list. What is left
        // is the numbers and the action — the parts a thread line cannot carry.
        val view = screenState(
            ChatHomeUiState.Error(
                message = "step-ceiling: 15/15 steps",
                reason = RunTerminationReason.StepCeiling(limit = 15, spent = 15),
                announcedInThread = true,
            ),
        ).toViewState()

        val termination = requireNotNull(view.termination)
        assertNull(view.errorMessage)
        assertNotNull(termination.meter)
        assertNotNull(termination.actionLabel)
    }

    @Test
    fun `an untyped failure the thread already announced shows no tile but keeps Retry`() {
        val view = screenState(
            ChatHomeUiState.Error("Tool 'http.get' failed", announcedInThread = true),
        ).toViewState()

        assertNull("The conversation already says this.", view.errorMessage)
        assertEquals(ComposerState.Error("Tool 'http.get' failed"), view.composerState)
    }

    @Test
    fun `an untyped failure with no run behind it still drives the composer error banner`() {
        // A blocked attachment or a model that would not load never reached a
        // run, so nothing wrote a line for it — this text is the only account
        // the user gets, and suppressing it would trade one silent failure for
        // another.
        val view = screenState(ChatHomeUiState.Error("Tool 'http.get' failed")).toViewState()

        assertEquals("Tool 'http.get' failed", view.errorMessage)
        assertEquals(ComposerState.Error("Tool 'http.get' failed"), view.composerState)
    }

    @Test
    fun `a run notice rides alongside whatever the run is doing`() {
        val view = screenState(
            ChatHomeUiState.Generating(),
            runNotice = RunNoticeCause.ApproachingCeiling(axis = RunCeilingAxis.STEPS, spent = 12, hardLimit = 15),
        ).toViewState()

        // Not a visual state of its own: the run is still generating, and the
        // notice must not displace that.
        assertEquals(ChatHomeVisualState.Generating, view.visualState)
        val notice = requireNotNull(view.runNotice)
        assertEquals(RunTerminationToneUi.Limit, notice.tone)
    }

    @Test
    fun `no notice means no strip above the composer`() {
        assertNull(screenState(ChatHomeUiState.Generating()).toViewState().runNotice)
    }

    @Test
    fun `a stalled run is explained, not shown as a failure to retry`() {
        // The queue's watchdog types this cause and persists it. It used to drop
        // it on the way to the surface, so the run that the app itself killed
        // arrived wearing the destructive tile and a Retry that would stall all
        // over again.
        val view = screenState(
            ChatHomeUiState.Error(message = "no-progress", reason = RunTerminationReason.NoProgress),
        ).toViewState()

        assertNull(view.errorMessage)
        val termination = requireNotNull(view.termination)
        assertEquals(RunTerminationToneUi.Stuck, termination.tone)
    }

    @Test
    fun `Empty maps to ChatHomeVisualState_Empty with sample prompt cards and no messages`() {
        val view = screenState(ChatHomeUiState.Empty).toViewState()
        assertEquals(ChatHomeVisualState.Empty, view.visualState)
        assertTrue(view.messages.isEmpty())
        // The empty-state body now renders rich suggestion cards
        // (mockup) instead of the legacy
        // single-line chip row.
        assertTrue(view.samplePromptCards.isNotEmpty())
        assertNull(view.errorMessage)
    }

    @Test
    fun `Empty sources its suggestion cards from the active pipeline's sample prompts`() {
        val prompts = listOf(
            PipelineSamplePrompt(title = "Look up benchmarks", toolsHint = "search_tool"),
            PipelineSamplePrompt(title = "Explain on-device inference"),
        )
        val view = screenState(ChatHomeUiState.Empty, activeSamplePrompts = prompts).toViewState()

        assertEquals(2, view.samplePromptCards.size)
        assertEquals("Look up benchmarks", view.samplePromptCards[0].title)
        assertEquals("search_tool", view.samplePromptCards[0].toolsUsed)
        // A null tools hint maps to an empty subtitle (hidden by the card).
        assertEquals("", view.samplePromptCards[1].toolsUsed)
    }

    @Test
    fun `Empty falls back to the generic fixture cards when the pipeline declares no prompts`() {
        val view = screenState(ChatHomeUiState.Empty, activeSamplePrompts = emptyList())
            .toViewState(ChatHomeFixtures.forTesting())

        assertEquals(ChatHomeFixtures.forTesting().suggestionCards, view.samplePromptCards)
    }

    @Test
    fun `Generating with preparingModel pairs a busy composer with the loading-model status line`() {
        val fixtures = ChatHomeFixtures.forTesting()
        val view = screenState(ChatHomeUiState.Generating(preparingModel = true)).toViewState(fixtures)

        assertEquals(ChatHomeVisualState.Generating, view.visualState)
        assertTrue(view.composerState is ComposerState.Generating)
        assertEquals(fixtures.statusPreparingModel, view.agentStatusLine)
        assertNull(view.errorMessage)
    }

    @Test
    fun `Generating with preparingModel labels the loader bubble as loading the model`() {
        val fixtures = ChatHomeFixtures.forTesting()
        val view = screenState(ChatHomeUiState.Generating(preparingModel = true)).toViewState(fixtures)

        assertEquals(fixtures.loaderPreparingModel, view.loaderLabel)
    }

    @Test
    fun `Generating while waiting in the queue says it is waiting in both the strip and the bubble`() {
        val fixtures = ChatHomeFixtures.forTesting()
        val view = screenState(ChatHomeUiState.Generating(waitingInQueue = true)).toViewState(fixtures)

        assertEquals(ChatHomeVisualState.Generating, view.visualState)
        assertTrue("Stop must stay available for a queued run", view.composerState is ComposerState.Generating)
        assertEquals(fixtures.statusWaitingInQueue, view.agentStatusLine)
        assertEquals(fixtures.loaderWaitingInQueue, view.loaderLabel)
    }

    @Test
    fun `Generating proper keeps the default loader label`() {
        assertNull(screenState(ChatHomeUiState.Generating()).toViewState().loaderLabel)
    }

    @Test
    fun `Idle maps to ChatHomeVisualState_Idle and threads supplied messages`() {
        val supplied = baselineMessages(model)
        val view = screenState(ChatHomeUiState.Idle, messages = supplied).toViewState()
        assertEquals(ChatHomeVisualState.Idle, view.visualState)
        assertEquals(supplied, view.messages)
        assertEquals(ComposerState.Idle, view.composerState)
    }

    @Test
    fun `Idle with no supplied messages renders an empty list`() {
        val view = screenState(ChatHomeUiState.Idle).toViewState()
        assertEquals(ChatHomeVisualState.Idle, view.visualState)
        assertTrue(view.messages.isEmpty())
    }

    @Test
    fun `Generating pairs the visual with ComposerState_Generating`() {
        val view = screenState(ChatHomeUiState.Generating()).toViewState()
        assertEquals(ChatHomeVisualState.Generating, view.visualState)
        assertTrue(view.composerState is ComposerState.Generating)
    }

    @Test
    fun `HitlConfirm appends a Sensitive Confirmation row to the baseline`() {
        val view = screenState(ChatHomeUiState.HitlConfirm(Risk.Sensitive)).toViewState()
        assertEquals(ChatHomeVisualState.HitlConfirm, view.visualState)
        val tail = view.messages.last().content
        assertTrue(tail is ChatContent.Confirmation)
        val confirmation = tail as ChatContent.Confirmation
        assertEquals(Risk.Sensitive, confirmation.model.risk)
        assertEquals("calendar.create_event", confirmation.model.toolName)
    }

    @Test
    fun `HitlConfirm with Destructive risk surfaces a destructive tool`() {
        val view = screenState(ChatHomeUiState.HitlConfirm(Risk.Destructive)).toViewState()
        val tail = view.messages.last().content as ChatContent.Confirmation
        assertEquals(Risk.Destructive, tail.model.risk)
        assertEquals("fs.delete_file", tail.model.toolName)
    }

    @Test
    fun `HitlConfirm threads pendingTypedConfirm through to the view state`() {
        val view = screenState(ChatHomeUiState.HitlConfirm(Risk.Destructive), pendingTypedConfirm = "ye")
            .toViewState()
        assertEquals("ye", view.pendingTypedConfirm)
    }

    @Test
    fun `a live pending confirmation leaves the card's summary blank`() {
        val row = liveHitlRow(
            modelName = model,
            pending = HitlPending(
                toolName = "append_file",
                arguments = """{"path":"inbox/captures.md"}""",
                risk = ToolRisk.SENSITIVE,
            ),
        )

        val confirmation = row.content as ChatContent.Confirmation
        assertEquals("append_file", confirmation.model.toolName)
        // Asserted as empty rather than as "different from the tool name": it is
        // emptiness that makes the card drop the line instead of printing the id
        // twice, and a summary merely different from the id would not do that.
        assertEquals("", confirmation.model.summary)
    }

    @Test
    fun `a live pending confirmation carries the decoded arguments and the real risk`() {
        val row = liveHitlRow(
            modelName = model,
            pending = HitlPending(
                toolName = "fs.delete_file",
                arguments = """{"path":"old-notes.md"}""",
                risk = ToolRisk.DESTRUCTIVE,
            ),
        )

        val confirmation = row.content as ChatContent.Confirmation
        assertEquals(Risk.Destructive, confirmation.model.risk)
        assertEquals(setOf("path"), confirmation.model.arguments.keys)
    }

    @Test
    fun `Clarification appends a clarification row with quick replies`() {
        val view = screenState(ChatHomeUiState.Clarification).toViewState()
        assertEquals(ChatHomeVisualState.Clarification, view.visualState)
        val tail = view.messages.last().content
        assertTrue(tail is ChatContent.Clarification)
        val clarification = tail as ChatContent.Clarification
        assertEquals(listOf("Work", "Personal", "Family"), clarification.model.quickReplies)
    }

    @Test
    fun `Error carries the message into both the inline tile and the composer banner`() {
        val state = ChatHomeUiState.Error(message = "Network unreachable")
        val view = screenState(state).toViewState()
        assertEquals(ChatHomeVisualState.Error, view.visualState)
        assertEquals("Network unreachable", view.errorMessage)
        val banner = view.composerState
        assertTrue(banner is ComposerState.Error)
        assertEquals("Network unreachable", (banner as ComposerState.Error).message)
    }

    @Test
    fun `DrawerOpen populates the threads list and hides any error message`() {
        val view = screenState(ChatHomeUiState.DrawerOpen).toViewState()
        assertEquals(ChatHomeVisualState.DrawerOpen, view.visualState)
        assertTrue(view.threads.isNotEmpty())
        assertNull(view.errorMessage)
    }

    @Test
    fun `consoleSnap threads through and forwards supplied console data regardless of state`() {
        val logs = listOf(
            ConsoleLine(
                timestamp = "09:14:00.000",
                source = ConsoleSource.NODE,
                level = ConsoleLevel.Trace,
                text = "▶ LITE_RT",
            ),
        )
        val vars = listOf(ConsoleVarRow(node = "LITE_RT#a", key = "input", valueJson = "\"x\""))
        val traces = listOf(
            ConsoleTraceSpan(name = "LITE_RT", durationMs = 10L, startedAt = "09:14:00.000", status = SpanStatus.Ok),
        )

        // Console pane open while the chat state is Generating — overlay
        // and underlying state are orthogonal post-refactor.
        val view = screenState(
            ChatHomeUiState.Generating(),
            console = ChatHomeConsoleState(
                snap = ConsoleSnap.Full,
                tab = ConsoleTab.Traces,
                logs = logs,
                vars = vars,
                traces = traces,
            ),
        ).toViewState()

        assertEquals(ChatHomeVisualState.Generating, view.visualState)
        assertEquals(ConsoleSnap.Full, view.console.snap)
        assertEquals(ConsoleTab.Traces, view.console.tab)
        assertEquals(logs, view.console.logs)
        assertEquals(vars, view.console.vars)
        assertEquals(traces, view.console.traces)
    }

    @Test
    fun `console snap null means the overlay is closed`() {
        val view = screenState(ChatHomeUiState.Idle).toViewState()
        assertNull(view.console.snap)
        assertTrue(view.console.logs.isEmpty())
        assertTrue(view.console.vars.isEmpty())
        assertTrue(view.console.traces.isEmpty())
        assertEquals(ConsoleTab.Logs, view.console.tab)
    }

    @Test
    fun `composerValue is threaded through every state`() {
        val states = listOf(
            ChatHomeUiState.Empty,
            ChatHomeUiState.Idle,
            ChatHomeUiState.Generating(),
            ChatHomeUiState.HitlConfirm(Risk.Readonly),
            ChatHomeUiState.Clarification,
            ChatHomeUiState.Error("boom"),
            ChatHomeUiState.DrawerOpen,
        )
        states.forEach { state ->
            val view = screenState(state, composerValue = "draft").toViewState()
            assertEquals("composer for ${state::class.simpleName}", "draft", view.composerValue)
        }
    }

    @Test
    fun `debugStateForId maps top-level state ids back to concrete states`() {
        val ids = listOf(
            DebugStateIds.EMPTY,
            DebugStateIds.IDLE,
            DebugStateIds.GENERATING,
            DebugStateIds.HITL_READONLY,
            DebugStateIds.HITL_SENSITIVE,
            DebugStateIds.HITL_DESTRUCTIVE,
            DebugStateIds.CLARIFICATION,
            DebugStateIds.ERROR,
            DebugStateIds.DRAWER_OPEN,
        )
        ids.forEach { id ->
            assertNotNull("missing mapping for $id", debugStateForId(id))
        }
        // Console picker ids no longer round-trip through `debugStateForId`
        // — the console is an independent overlay and is opened via
        // `debugConsoleSnapForId`.
        assertNull(debugStateForId(DebugStateIds.CONSOLE_PARTIAL))
        assertNull(debugStateForId(DebugStateIds.CONSOLE_FULL))
        assertNull(debugStateForId("not_a_real_id"))
    }

    @Test
    fun `debugConsoleSnapForId maps console picker ids to snaps`() {
        assertEquals(ConsoleSnap.Partial, debugConsoleSnapForId(DebugStateIds.CONSOLE_PARTIAL))
        assertEquals(ConsoleSnap.Full, debugConsoleSnapForId(DebugStateIds.CONSOLE_FULL))
        assertNull(debugConsoleSnapForId(DebugStateIds.EMPTY))
    }

    // ─── The ceiling pause ────────────────────────────────────────────────────

    @Test
    fun `a ceiling pause renders the pause card with the run's own numbers`() {
        val view = screenState(
            ChatHomeUiState.CeilingPause,
            pending = ChatHomePendingState(
                ceiling = CeilingPausePending(
                    runId = "run-1",
                    breach = HardCeilingBreach(RunCeilingAxis.STEPS, limit = 15, spent = 15),
                    timestamp = "09:16",
                ),
            ),
        ).toViewState()

        assertEquals(ChatHomeVisualState.CeilingPause, view.visualState)
        val card = view.messages.last().content as ChatContent.RunCeilingPause
        // The stub resolver renders "res:<id>(args)", so the numbers being
        // present at all is what this pins: the card states the limit that
        // bound, which for an extended run is not the configured setting.
        assertTrue("Expected the numbers in: ${card.model.meter}", card.model.meter.contains("15"))
        assertTrue(card.model.continueLabel.contains("15"))
        assertNotEquals(card.model.continueLabel, card.model.stopLabel)
        // The pause is not a termination tile: nothing about it may read as a
        // stop, or the composer banner and the tile would contradict the card.
        assertNull(view.termination)
    }

    @Test
    fun `a ceiling pause with no pending snapshot renders no card at all`() {
        // Unlike the interrupted state, there is no debug-picker fallback row.
        // A pause card without a snapshot behind it would offer Continue and
        // Stop buttons wired to a run that does not exist.
        val view = screenState(ChatHomeUiState.CeilingPause).toViewState()

        assertEquals(ChatHomeVisualState.CeilingPause, view.visualState)
        assertTrue(view.messages.none { it.content is ChatContent.RunCeilingPause })
    }
}
