package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.design.components.chips.Risk

/**
 * Sealed UI state for the redesigned chat home. Drives the 9-state visual
 * matrix.
 *
 * The dark-theme variant is cross-cutting (driven by `isSystemInDarkTheme`
 * via `KnotworkTheme`) and is therefore not modelled as a separate state.
 */
sealed interface ChatHomeUiState {

    /**
     * Cold-start sentinel emitted before the chat repository finishes
     * delivering the first message snapshot. Distinct from [Empty]: while
     * `Loading`, the surface shows a centered progress indicator (no empty
     * placeholder copy) so users don't see the "no messages yet" hero flash
     * for a frame on every app launch.
     */
    data object Loading : ChatHomeUiState

    /** No messages in the active thread; empty state with sample-prompt chips. */
    data object Empty : ChatHomeUiState

    /** History present, no in-flight request. Default after first send. */
    data object Idle : ChatHomeUiState

    /**
     * The surface is busy: the composer morphs to stop.
     *
     * @property preparingModel `true` during the transient phase where the user
     *   sent a message while no LLM model was loaded and the active model is
     *   being loaded before the send proceeds automatically. The composer then
     *   shows an honest "loading model" status rather than "generating";
     *   `false` is the normal case where the assistant is producing tokens.
     *   Modelled as a flag rather than a separate state so every "busy" guard
     *   (`is Generating`) covers both phases without a second sealed arm.
     * @property waitingInQueue `true` while the run is accepted but waiting behind
     *   another run in the serial queue (a trigger, a share, another chat). Nothing is
     *   being generated yet and the wait lasts as long as the run ahead, so the surface
     *   says it is waiting instead of claiming to generate. Same flag shape as
     *   [preparingModel], for the same reason; Stop still cancels the queued run.
     */
    data class Generating(val preparingModel: Boolean = false, val waitingInQueue: Boolean = false) :
        ChatHomeUiState

    /**
     * A tool call awaits user approval. The bubble at the tail of the
     * conversation hosts the HITL confirmation card whose visuals depend
     * on the [risk] tier.
     *
     * @property risk risk tier driving the card border + buttons.
     */
    data class HitlConfirm(val risk: Risk) : ChatHomeUiState

    /** The assistant needs more info; trailing clarification card. */
    data object Clarification : ChatHomeUiState

    /**
     * The session's most recent pipeline run was interrupted by a process
     * death before reaching a terminal state. The tail of the conversation
     * hosts the interrupted-run status card with Resume / Discard actions;
     * the card payload lives in `ChatHomePendingState.interrupted`.
     */
    data object Interrupted : ChatHomeUiState

    /**
     * A run has spent one of the limits the user set for it and is waiting to
     * be told whether it may carry on. The tail of the conversation hosts the
     * pause card with Continue / Stop actions; the payload lives in
     * `ChatHomePendingState.ceiling`.
     *
     * Distinct from [Interrupted] because the run is not over: its checkpoint
     * is intact and the engine is waiting on an answer, so offering Discard
     * here would settle a run the user may simply want to let finish.
     */
    data object CeilingPause : ChatHomeUiState

    /**
     * The run stopped without producing an answer, and the tail of the
     * conversation explains why.
     *
     * Two shapes behind one state, told apart by [reason]:
     *
     *  - **an ordinary failure** (`reason == null`) — a node or the engine
     *     broke. [message] is the user-visible description, and the surface
     *     keeps the destructive-toned tile with its Retry action, because a
     *     transient fault may genuinely not recur.
     *  - **a typed termination** — the app itself decided to end the run: a
     *     ceiling, the stuck-detector, the silence watchdog, an expired approval window. Here
     *     [message] is only the diagnostic that lands in the run record; every
     *     word the user reads is resolved from [reason] through
     *     `RunTerminationCopyMapper`, so the same event is worded identically
     *     in the chat, the notification and the trigger journal.
     *
     * @property message The failure description for an ordinary failure, or the
     *   terse diagnostic form for a typed termination. Never the user-facing
     *   sentence in the second case — see [reason].
     * @property reason The typed cause, or `null` for an ordinary failure.
     * @property announcedInThread Whether a settled run already wrote this
     *   outcome into the conversation as a `SYSTEM` message. True only for the
     *   orchestrator's terminal state; the other producers of this state are
     *   local failures with no run behind them — a blocked attachment, a model
     *   that would not load, a collection that threw — and those have nothing in
     *   the thread, so their text is the only account the user gets.
     */
    data class Error(
        val message: String,
        val reason: RunTerminationReason? = null,
        val announcedInThread: Boolean = false,
    ) : ChatHomeUiState

    /** Alternate nav drawer over the chat surface. */
    data object DrawerOpen : ChatHomeUiState
}
