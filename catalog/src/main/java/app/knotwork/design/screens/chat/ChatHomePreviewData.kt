@file:Suppress(
    // File hosts ChatHomePreview + snapshotTag(); the data-oriented name reads better.
    "MatchingDeclarationName",
)

package app.knotwork.design.screens.chat

import app.knotwork.design.components.chat.ChatContent
import app.knotwork.design.components.chat.ChatMetadata
import app.knotwork.design.components.chat.ChatRole
import app.knotwork.design.components.chat.ClarificationCardModel
import app.knotwork.design.components.chat.ComposerState
import app.knotwork.design.components.chat.HitlConfirmationModel
import app.knotwork.design.components.chat.InterruptedRunCardModel
import app.knotwork.design.components.chat.RunCeilingPauseCardModel
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleLevel
import app.knotwork.design.components.console.ConsoleLine
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleSource
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.SpanStatus

/**
 * Deterministic fixtures backing the 9-state `ChatHomeScreen` preview and
 * Roborazzi snapshot matrix.
 *
 * All timestamps are pre-formatted, all model / tool names are fictional but
 * representative of the agent's real surface. The factory functions are
 * `internal` so they can be exercised from the snapshot suite and from any
 * Android Studio preview inside `:catalog`, but `:app` code never reaches
 * them (it owns its own production stub).
 */
internal object ChatHomePreview {

    /** Pre-formatted thread title surfaced by every state. */
    const val THREAD_TITLE: String = "Yesterday's deploy"

    /** Display name of the (mock) currently-active local model. */
    const val MODEL_NAME: String = "Gemma 2 · 2B"

    /** Sample baseline conversation history used by Idle / Generating / HITL / Clarification / Error. */
    fun baselineMessages(): List<ChatHomeMessageRow> = listOf(
        ChatHomeMessageRow(
            id = "u1",
            role = ChatRole.User,
            content = ChatContent.Text("Summarise the three PRs that landed yesterday."),
            metadata = ChatMetadata(timestamp = "09:14"),
        ),
        ChatHomeMessageRow(
            id = "a1",
            role = ChatRole.Assistant,
            content = ChatContent.Markdown(
                source = "Pipeline editor refactor (UI overhaul), context-window meter " +
                    "for the chat header, and a memory-summary regression fix.",
            ),
            metadata = ChatMetadata(
                timestamp = "09:14",
                model = MODEL_NAME,
                tokens = 64,
            ),
        ),
        ChatHomeMessageRow(
            id = "u2",
            role = ChatRole.User,
            content = ChatContent.Text("Add a 30-minute meeting tomorrow at 10:00 to discuss the rollout."),
            metadata = ChatMetadata(timestamp = "09:15"),
        ),
    )

    /**
     * The `SYSTEM` line a run leaves in the conversation when it stops without
     * an answer. Byte-identical to `run_termination_body_step_ceiling`, which is
     * what `RunOutcomeAnnouncer` resolves for the same event.
     */
    private val stoppedByCeilingLine = ChatHomeMessageRow(
        id = "sys1",
        role = ChatRole.System,
        content = ChatContent.Text(
            "This run used all the steps it was allowed. Raise the step limit, or split the work " +
                "into smaller runs.",
        ),
        metadata = ChatMetadata(timestamp = "09:15"),
    )

    /** Sample thread rows surfaced in the drawer overlay. */
    fun threadRows(activeId: String = "t1"): List<ChatHomeThreadRow> = listOf(
        ChatHomeThreadRow(
            id = "t1",
            title = "Yesterday's deploy",
            subtitle = "Today · 14 messages",
            selected = activeId == "t1",
        ),
        ChatHomeThreadRow(
            id = "t2",
            title = "Plan the weekend trip",
            subtitle = "Yesterday · 22 messages",
            selected = activeId == "t2",
        ),
        ChatHomeThreadRow(
            id = "t3",
            title = "Memory cleanup",
            subtitle = "2 days ago · 4 messages",
            selected = activeId == "t3",
        ),
    )

    /** Sample log lines surfaced inside the console overlay. */
    fun consoleLines(): List<ConsoleLine> = listOf(
        ConsoleLine(
            timestamp = "09:14:00.012",
            source = ConsoleSource.RUNTIME,
            level = ConsoleLevel.Info,
            text = "pipeline=default loaded (3 nodes)",
        ),
        ConsoleLine(
            timestamp = "09:14:00.118",
            source = ConsoleSource.NODE,
            level = ConsoleLevel.Trace,
            text = "INPUT → LITE_RT prompt rendered (412 tokens)",
        ),
        ConsoleLine(
            timestamp = "09:14:02.341",
            source = ConsoleSource.TOOL,
            level = ConsoleLevel.Warn,
            text = "calendar.create_event awaiting user approval (Sensitive)",
        ),
    )

    /** Sample variable rows surfaced inside the console Vars tab. */
    fun consoleVars(): List<app.knotwork.design.components.console.ConsoleVarRow> = listOf(
        app.knotwork.design.components.console.ConsoleVarRow(
            node = "lite_rt#1",
            key = "temperature",
            valueJson = "0.7",
        ),
        app.knotwork.design.components.console.ConsoleVarRow(
            node = "lite_rt#1",
            key = "topP",
            valueJson = "0.9",
        ),
    )

    /** Sample trace spans surfaced inside the console Traces tab. */
    fun consoleTraces(): List<ConsoleTraceSpan> = listOf(
        ConsoleTraceSpan(
            name = "lite_rt#1.generate",
            durationMs = 1840L,
            startedAt = "09:14:00.118",
            status = SpanStatus.Ok,
        ),
        ConsoleTraceSpan(
            name = "calendar.create_event",
            durationMs = 86L,
            startedAt = "09:14:02.341",
            status = SpanStatus.Ok,
        ),
    )

    /** Sample prompt chips shown in the empty state. */
    fun samplePrompts(): List<String> = listOf(
        "Summarise the last meeting notes",
        "Plan my afternoon",
        "Search recent emails about \"deploy\"",
    )

    /** Empty state — no messages, no in-flight request. */
    fun empty(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Empty,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        samplePrompts = samplePrompts(),
    )

    /**
     * Empty state as the app shows it: the rich suggestion cards (six, the most a
     * pipeline can carry, two of them with two-line titles) and the status strip the
     * app always shows above the composer on a new chat.
     */
    fun emptyWithCards(): ChatHomeViewState = empty().copy(
        samplePrompts = emptyList(),
        samplePromptCards = listOf(
            ChatHomeSamplePromptCard("c1", "Summarise the last meeting notes", "memory · summary"),
            ChatHomeSamplePromptCard("c2", "Plan my afternoon around the two calls I still have", "calendar"),
            ChatHomeSamplePromptCard("c3", "Search recent emails about the deploy", "search_tool"),
            ChatHomeSamplePromptCard("c4", "Draft a reply to the landlord about the broken heater", "write_file"),
            ChatHomeSamplePromptCard("c5", "What is on my list for tomorrow?", "memory"),
            ChatHomeSamplePromptCard("c6", "Translate this paragraph into Spanish", "on-device"),
        ),
        agentStatusLine = "idle · on-device",
    )

    /** Idle state — populated conversation, composer ready. */
    fun idle(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Idle,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages(),
    )

    /**
     * Chat with the console entry strip visible, in one of its status states.
     *
     * No other fixture sets `agentStatusLine`, so before this one the strip —
     * the control an external tester could not find at all — had **zero**
     * snapshot coverage while the chat screen looked thoroughly covered.
     *
     * @param status the live status line, verbatim from `strings_chat.xml`.
     * @param consoleOpen when `true`, the console sheet is up and the strip
     *        renders as its header instead of above the composer.
     */
    fun consoleStrip(status: String, consoleOpen: Boolean = false): ChatHomeViewState = ChatHomeViewState(
        visualState = if (consoleOpen) ChatHomeVisualState.ConsoleExpanded else ChatHomeVisualState.Idle,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages(),
        agentStatusLine = status,
        console = if (consoleOpen) consoleExpanded().console else ChatHomeConsoleState(),
    )

    /** Every status line the strip has to render, keyed by the state that produces it. */
    object StripStatus {
        const val IDLE: String = "[NODE]  idle · ready"
        const val GENERATING: String = "[NODE]  generating"

        /**
         * The line the app actually shows while answering: backend and a
         * growing token count. Every other fixture here is short enough to fit,
         * so the strip's overflow behaviour was in no baseline at all — and the
         * first two attempts at it (trailing, then middle ellipsis) each ate
         * something worth keeping before anyone saw it on a device.
         */
        const val GENERATING_LONG: String = "[NODE]  generating (GPU) · 128 tok"
        const val PREPARING: String = "[NODE]  loading model · please wait"
        const val WAITING_IN_QUEUE: String = "[NODE]  waiting behind another run · queued"
        const val HITL: String = "[TOOL]  awaiting approval"
        const val CLARIFICATION: String = "[NODE]  waiting on clarification"
        const val ERROR: String = "[NODE]  error · see message"
    }

    /** Generating state — the assistant is producing tokens. */
    fun generating(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Generating,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages(),
        composerState = ComposerState.Generating,
    )

    /**
     * Generating state while the run waits behind another run in the queue: the
     * status line and the loader bubble both say it is waiting, not generating.
     */
    fun waitingInQueue(): ChatHomeViewState = generating().copy(
        agentStatusLine = StripStatus.WAITING_IN_QUEUE,
        loaderLabel = "Waiting…",
    )

    /**
     * HITL Confirm state. Default risk is `Sensitive` (most common path); the
     * matrix covers all 3 risk variants so the snapshot baseline catches
     * palette / glyph regressions across every level defined in
     * `domain/models/ToolRisk.kt`.
     */
    fun hitlConfirm(risk: Risk = Risk.Sensitive): ChatHomeViewState {
        val toolName = when (risk) {
            Risk.Readonly -> "calendar.read_events"
            Risk.Sensitive -> "calendar.create_event"
            Risk.Destructive -> "calendar.delete_event"
        }
        val summary = when (risk) {
            Risk.Readonly -> "List the next three events on your work calendar."
            Risk.Sensitive ->
                "Add a 30-minute meeting \"Rollout sync\" to your work calendar tomorrow at 10:00."
            Risk.Destructive ->
                "Permanently delete the meeting \"Old sync\" from your work calendar."
        }
        return ChatHomeViewState(
            visualState = ChatHomeVisualState.HitlConfirm,
            threadTitle = THREAD_TITLE,
            modelName = MODEL_NAME,
            messages = baselineMessages() + ChatHomeMessageRow(
                id = "a-hitl",
                role = ChatRole.Assistant,
                content = ChatContent.Confirmation(
                    model = HitlConfirmationModel(
                        risk = risk,
                        toolName = toolName,
                        summary = summary,
                        arguments = mapOf(
                            "calendar" to "\"work\"",
                        ),
                        timestamp = "09:16",
                    ),
                ),
                metadata = ChatMetadata(timestamp = "09:16", model = MODEL_NAME),
            ),
        )
    }

    /** Clarification state — assistant asks the user a structured question. */
    fun clarification(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Clarification,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages() + ChatHomeMessageRow(
            id = "a-clar",
            role = ChatRole.Assistant,
            content = ChatContent.Clarification(
                model = ClarificationCardModel(
                    question = "Which calendar should I add the meeting to?",
                    quickReplies = listOf("Work", "Personal", "Family"),
                ),
            ),
            metadata = ChatMetadata(timestamp = "09:16", model = MODEL_NAME),
        ),
    )

    /** Interrupted state — the previous run died with its process; status card with Resume / Discard. */
    fun interrupted(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Interrupted,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages() + ChatHomeMessageRow(
            id = "a-interrupted",
            role = ChatRole.Assistant,
            content = ChatContent.RunInterrupted(
                model = InterruptedRunCardModel(nodeLabel = "Summarise"),
            ),
            metadata = ChatMetadata(timestamp = "09:16", model = MODEL_NAME),
        ),
    )

    /**
     * A run paused at one of its own ceilings, waiting to be told whether it
     * may carry on. Numbers match the run-ceiling copy the app resolves, so the
     * baseline photographs the real arithmetic rather than a placeholder.
     */
    fun ceilingPause(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.CeilingPause,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages() + ChatHomeMessageRow(
            id = "a-ceiling-pause",
            role = ChatRole.Assistant,
            content = ChatContent.RunCeilingPause(
                model = RunCeilingPauseCardModel(
                    title = "Paused at a safety limit",
                    body = "This run has used every step it was allowed. You can let it carry on " +
                        "for another 15 steps, or stop it here.",
                    meter = "Used 15 of 15 steps",
                    continueLabel = "Continue (+15)",
                    stopLabel = "Stop the run",
                ),
            ),
            metadata = ChatMetadata(timestamp = "09:16", model = MODEL_NAME),
        ),
    )

    /** Error state — model failed, inline error tile + retry. */
    fun error(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Error,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages(),
        errorMessage = "Inference timed out after 30 s. Tap retry to try again.",
        composerState = ComposerState.Error(message = "Network unreachable"),
    )

    /**
     * A run a ceiling stopped. Warning-toned, shield-marked, no Retry — the
     * limit will still be there on a second attempt, so the only useful action
     * is to change it.
     */
    fun stoppedByCeiling(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Error,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        // The run writes its outcome into the conversation as it settles, so the
        // sentence is a message and the tile below it carries only the numbers
        // and the action. A fixture without this line would picture a state the
        // app no longer produces — and the tile would look like it had lost its
        // explanation rather than handed it over.
        messages = baselineMessages() + stoppedByCeilingLine,
        termination = ChatTerminationUi(
            tone = RunTerminationToneUi.Limit,
            toneLabel = "Safety limit",
            title = "Stopped by a safety limit",
            banner = "Stopped by a safety limit — used 15 of 15 steps.",
            meter = "Used 15 of 15 steps",
            actionLabel = "Adjust limits",
        ),
    )

    /**
     * A run ended for a housekeeping reason: nothing about the pipeline is
     * wrong, so the tone drops to neutral and running it again is genuinely
     * worth offering.
     */
    fun stoppedHousekeeping(): ChatHomeViewState = stoppedByCeiling().copy(
        termination = ChatTerminationUi(
            tone = RunTerminationToneUi.Info,
            toneLabel = "Run ended",
            title = "The pipeline changed while this run was paused",
            banner = "The pipeline changed while this run was paused.",
            actionLabel = "Run it again",
        ),
    )

    /** A run still going, warned once that it is approaching a limit. */
    fun approachingCeiling(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.Generating,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages(),
        runNotice = ChatRunNoticeUi(
            tone = RunTerminationToneUi.Limit,
            text = "Nearing the step limit: 12 of 15 steps used.",
        ),
    )

    /**
     * The same slot, the graph stuck-detector's cause.
     *
     * Drawn now, before its producer exists, on purpose: this surface is frozen
     * by the baselines beside it, and a second consumer arriving afterwards
     * with nowhere to render is exactly what that would have caused.
     */
    fun looksStuck(): ChatHomeViewState = approachingCeiling().copy(
        runNotice = ChatRunNoticeUi(
            tone = RunTerminationToneUi.Stuck,
            text = "This run looks stuck — the same step keeps repeating. It will stop if nothing changes.",
        ),
    )

    /**
     * DrawerOpen state — alt-nav drawer overlayed.
     *
     * The drawer's three sub-states are parameters rather than three named
     * factories: each differs from the base by exactly one field, and the call
     * site reads better naming the field than naming a fixture that hides it.
     *
     * @param openThreadMenuId row whose overflow menu (Rename · Archive ·
     *   Delete) is open, or `null` for none.
     * @param revealedThreadId row whose single Archive swipe action is
     *   revealed, or `null` for none.
     * @param archivedCount archived-chat count; the drawer's archive footer
     *   entry appears only when it is positive.
     */
    fun drawerOpen(
        openThreadMenuId: String? = null,
        revealedThreadId: String? = null,
        archivedCount: Int = 0,
    ): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.DrawerOpen,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages(),
        threads = threadRows(activeId = "t1"),
        openThreadMenuId = openThreadMenuId,
        revealedThreadId = revealedThreadId,
        archivedCount = archivedCount,
    )

    /** Idle state on an archived thread — read-only, composer replaced by the restore bar. */
    fun archivedReadOnly(): ChatHomeViewState = idle().copy(archivedReadOnly = true)

    /** ConsoleExpanded state — console sheet overlayed at the Partial snap. */
    fun consoleExpanded(): ChatHomeViewState = ChatHomeViewState(
        visualState = ChatHomeVisualState.ConsoleExpanded,
        threadTitle = THREAD_TITLE,
        modelName = MODEL_NAME,
        messages = baselineMessages(),
        console = ChatHomeConsoleState(
            snap = ConsoleSnap.Partial,
            tab = ConsoleTab.Logs,
            logs = consoleLines(),
            vars = consoleVars(),
            traces = consoleTraces(),
            filter = ConsoleFilter.allOn,
        ),
    )

    /** All canonical states in the spec's documented order. */
    fun allStates(): List<ChatHomeViewState> = listOf(
        empty(),
        idle(),
        generating(),
        hitlConfirm(),
        clarification(),
        interrupted(),
        ceilingPause(),
        error(),
        drawerOpen(),
        consoleExpanded(),
    )
}

/**
 * Tag attached to each snapshot file name. Keeps the snapshot order in
 * lock-step with [ChatHomePreview.allStates] without depending on the enum
 * being persisted across renames.
 */
internal fun ChatHomeVisualState.snapshotTag(): String = when (this) {
    ChatHomeVisualState.Loading -> "loading"
    ChatHomeVisualState.Empty -> "empty"
    ChatHomeVisualState.Idle -> "idle"
    ChatHomeVisualState.Generating -> "generating"
    ChatHomeVisualState.HitlConfirm -> "hitl_confirm"
    ChatHomeVisualState.Clarification -> "clarification"
    ChatHomeVisualState.Interrupted -> "interrupted"
    ChatHomeVisualState.CeilingPause -> "ceiling_pause"
    ChatHomeVisualState.Error -> "error"
    ChatHomeVisualState.DrawerOpen -> "drawer_open"
    ChatHomeVisualState.ConsoleExpanded -> "console_expanded"
}
