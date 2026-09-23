package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.ceilingBreach
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.AgentOrchestratorUseCase
import app.knotwork.android.domain.usecases.ResumeOutcome
import app.knotwork.android.domain.usecases.ResumePipelineRunUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Reattach / background-run / interrupted-run delegate of [ChatHomeViewModel].
 *
 * Re-binds the UI to whatever a session's persistent run record says when the
 * session opens (cold start or thread switch): replaying the console trace,
 * attaching the live collector for an active run, or surfacing the
 * interrupted-run status card (Resume / Discard). It also keeps watching for a
 * run that *starts* while the session is already open, and restores the HITL /
 * clarification card from the authoritative pending snapshot when a reattached
 * run is suspended.
 *
 * The live collector itself is owned by the ViewModel (it is the shared agent-
 * execution core); this delegate drives it through the [attachToLiveRun] seam,
 * replays the console through [replayTrace], and restores suspension cards
 * through the [restoreApproval] / [restoreClarification] seams into the HITL
 * delegate. It owns the `pending.interrupted` snapshot and the shared
 * [resumeFeedbackEvents] channel (the HITL delegate emits into it via
 * [emitResumeFeedback]).
 *
 * Shares the ViewModel's [scope] and single [state] reducer (see
 * `docs/architecture.md` §1.2).
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property state The ViewModel's single source-of-truth state flow.
 * @property pipelineRunRepository Resolves the session's run records.
 * @property pipelineRepository Resolves the interrupted run's node label.
 * @property settingsRepository Source of the resume window.
 * @property agentOrchestratorUseCase Source of the live pending-approval snapshot on restore.
 * @property clarificationRepository Source of the live pending-clarification snapshot on restore.
 * @property pendingInteractionRepository Source of the durable parked interaction on restore.
 * @property resumePipelineRunUseCase Resumes an interrupted run.
 * @property attachToLiveRun Seam into the ViewModel's live-run collector.
 * @property replayTrace Seam into the console delegate's persisted-trace replay.
 * @property restoreApproval Seam into the HITL delegate's approval-card capture.
 * @property restoreClarification Seam into the HITL delegate's clarification-card capture.
 * @property restoreCeilingPause Seam into the HITL delegate's ceiling-pause capture.
 */
class ChatHomeReattachDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val pipelineRunRepository: PipelineRunRepository,
    private val pipelineRepository: PipelineRepository,
    private val settingsRepository: SettingsRepository,
    private val agentOrchestratorUseCase: AgentOrchestratorUseCase,
    private val clarificationRepository: ClarificationRepository,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val resumePipelineRunUseCase: ResumePipelineRunUseCase,
    private val attachToLiveRun: suspend (String, PipelineRunStatus) -> Unit,
    private val replayTrace: suspend (PipelineRun) -> Unit,
    private val restoreApproval: (AgentOrchestratorState.WaitingForApproval) -> Unit,
    private val restoreClarification: (ClarificationRequest) -> Unit,
    private val restoreCeilingPause: (CeilingPausePending) -> Unit,
) {

    private val _resumeFeedbackEvents: MutableSharedFlow<ResumeFeedbackEvent> =
        MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * One-shot signal raised when a Resume tap on the interrupted-run card (or a
     * parked HITL/clarification resume) could not start the run. Each variant
     * maps to its own snackbar copy on the screen; a successful resume emits
     * nothing — the surface flips to `Generating` and the live state flow takes
     * over.
     */
    val resumeFeedbackEvents: SharedFlow<ResumeFeedbackEvent> = _resumeFeedbackEvents.asSharedFlow()

    /** Serializes reattach lookups so a rapid thread switch cannot interleave run branches. */
    private var reattachJob: Job? = null

    /** Emits a resume-feedback event. Exposed so the HITL delegate can share this channel. */
    fun emitResumeFeedback(event: ResumeFeedbackEvent) {
        _resumeFeedbackEvents.tryEmit(event)
    }

    /** Cancels any in-flight reattach lookup (new run / thread switch / stop). */
    fun cancel() {
        reattachJob?.cancel()
    }

    /**
     * Chat reattach protocol — resolves the session's run record once and
     * re-binds the UI to whatever it says when the session is opened. The single
     * lookup feeds both the console trace replay and the reattach branching.
     * Branches: active run → attach the live collector; latest run INTERRUPTED →
     * surface the status card; anything else → no live attach.
     *
     * Ordering matters at the console seam: the trace replay must install its
     * baseline before the live collector subscribes. The branch decision is made
     * against the persistent status — WAITING_* runs of a dead process are
     * settled to INTERRUPTED by the startup orphan sweep, so an active status
     * here always denotes a run alive in this process.
     *
     * @param sessionId The session being (re)opened.
     */
    fun reattachToRun(sessionId: String) {
        reattachJob?.cancel()
        reattachJob = scope.launch {
            val activeRun = pipelineRunRepository.getActiveRunForSession(sessionId)
            val baselineRun = activeRun ?: pipelineRunRepository.getLatestRunForSession(sessionId)
            // A rapid thread switch may have changed the active session while
            // the lookup suspended — applying the stale branch would bleed
            // the previous thread's run state into the new one.
            if (state.value.thread.currentSessionId != sessionId) return@launch
            if (baselineRun != null) replayTrace(baselineRun)
            when {
                activeRun != null -> attachToLiveRun(sessionId, activeRun.status)
                baselineRun?.status == PipelineRunStatus.INTERRUPTED -> presentInterruptedRun(baselineRun)
                else -> Unit
            }
            watchForBackgroundRuns(sessionId, alreadyAttachedRunId = activeRun?.id)
        }
    }

    /**
     * Keeps watching the session's persistent run records after the one-shot
     * reattach so a run that *starts* while the session is already open still
     * attaches the UI to the live stream. Attach conditions: a non-terminal run
     * the collector has not attached yet **and** a resting/cold visual surface
     * (the guard keeps the watcher away from interactive sends, whose own
     * collector already owns the stream). Rides [reattachJob].
     */
    private suspend fun watchForBackgroundRuns(sessionId: String, alreadyAttachedRunId: String?) {
        var attachedRunId = alreadyAttachedRunId
        pipelineRunRepository.observeRunsForSession(sessionId)
            .mapNotNull { runs -> runs.firstOrNull { !it.status.isTerminal } }
            .distinctUntilChangedBy { it.id }
            .collect { run ->
                if (run.id == attachedRunId) return@collect
                if (state.value.thread.currentSessionId != sessionId) return@collect
                if (!state.value.visual.isRestingOrCold()) return@collect
                attachedRunId = run.id
                attachToLiveRun(sessionId, run.status)
            }
    }

    /**
     * Restores the trailing suspension card from the authoritative pending
     * snapshot when the persistent run [status] is a WAITING_* one. The live
     * flow cannot be relied on for this: console events emitted while the run
     * waits overwrite the `WaitingForApproval` / `AwaitingClarification`
     * emission. A pending snapshot that is already gone is a benign no-op.
     * Called by the ViewModel's live-run collector after it subscribes.
     *
     * @param sessionId The reattached session.
     * @param status The persistent run status driving the restore.
     */
    suspend fun restoreSuspensionCard(sessionId: String, status: PipelineRunStatus) {
        when (status) {
            PipelineRunStatus.WAITING_APPROVAL -> {
                val live = agentOrchestratorUseCase.pendingApprovalFor(sessionId)
                if (live != null) {
                    restoreApproval(live)
                } else {
                    // Persistent phase (or a different process parked the run):
                    // rebuild the card from the durable record; the decision
                    // then routes through the parked-run submission path.
                    pendingInteractionRepository.getForSession(sessionId)
                        ?.takeIf { it.kind == PendingInteractionKind.APPROVAL }
                        ?.let { parked ->
                            restoreApproval(
                                AgentOrchestratorState.WaitingForApproval(
                                    toolName = parked.toolName.orEmpty(),
                                    arguments = parked.toolArgs.orEmpty(),
                                    risk = parked.risk ?: ToolRisk.SENSITIVE,
                                    // The card answers the record it shows, not
                                    // whatever the session parks next. Records
                                    // from before request ids were back-filled
                                    // with their run id.
                                    requestId = parked.requestId ?: parked.runId,
                                ),
                            )
                        }
                }
            }
            PipelineRunStatus.WAITING_CLARIFICATION -> {
                val live = clarificationRepository.pendingRequests.first()
                    .lastOrNull { it.sessionId == sessionId }
                if (live != null) {
                    restoreClarification(live)
                } else {
                    // Persistent phase: re-render the persisted question. The
                    // synthetic request id (run id) can never match a live
                    // deferred, so the answer falls through to the parked path.
                    pendingInteractionRepository.getForSession(sessionId)
                        ?.takeIf { it.kind == PendingInteractionKind.CLARIFICATION }
                        ?.let { parked ->
                            restoreClarification(
                                ClarificationRequest(
                                    id = parked.runId,
                                    sessionId = parked.sessionId,
                                    question = parked.question.orEmpty(),
                                    options = parked.options,
                                    timeoutMs = 0L,
                                ),
                            )
                        }
                }
            }
            PipelineRunStatus.WAITING_CEILING -> {
                // No live snapshot to prefer, unlike the two gates above: a
                // ceiling pause has no in-process waiting phase at all. It is
                // durable from the moment it is raised, so the record IS the
                // authority — in this process and in any other.
                pendingInteractionRepository.getForSession(sessionId)
                    ?.let { parked -> parked.ceilingBreach()?.let { parked to it } }
                    ?.let { (parked, breach) ->
                        restoreCeilingPause(
                            CeilingPausePending(
                                runId = parked.runId,
                                breach = breach,
                                timestamp = chatRowTimestamp(parked.requestedAt),
                            ),
                        )
                    }
            }
            else -> Unit
        }
    }

    /**
     * Interrupted branch of the reattach protocol: installs the interrupted-run
     * snapshot into the pending slice and flips the surface to
     * [ChatHomeUiState.Interrupted] so the mapping appends the status card. The
     * pending snapshot is installed unconditionally; only the immediate visual
     * flip is guarded to resting/cold states.
     */
    private suspend fun presentInterruptedRun(run: PipelineRun) {
        val nodeLabel = resolveNodeLabel(run)
        // The card only offers Resume while the checkpoint is inside the resume
        // window and the record carries everything resume needs (the original
        // prompt — absent on legacy rows). The use case re-validates on tap.
        val maxAgeMillis = settingsRepository.resumeMaxAgeHours.first() * MILLIS_PER_HOUR
        val interruptedAt = run.finishedAt ?: run.startedAt
        val resumable = run.userPrompt != null &&
            System.currentTimeMillis() - interruptedAt <= maxAgeMillis
        val pending = InterruptedRunPending(
            runId = run.id,
            nodeLabel = nodeLabel,
            timestamp = chatRowTimestamp(run.finishedAt ?: run.startedAt),
            resumable = resumable,
        )
        state.update { current ->
            val withPending = current.copy(pending = current.pending.copy(interrupted = pending))
            if (current.visual.isRestingOrCold()) {
                withPending.copy(visual = ChatHomeUiState.Interrupted)
            } else {
                withPending
            }
        }
    }

    /**
     * Resolves the display label of the node [PipelineRun.currentNodeId] points
     * at by loading the run's pipeline graph. Falls back to
     * [INTERRUPTED_UNKNOWN_NODE_LABEL] when the run never reached a node, the
     * pipeline was deleted, or the node id no longer exists in the graph.
     */
    private suspend fun resolveNodeLabel(run: PipelineRun): String {
        val nodeId = run.currentNodeId ?: return INTERRUPTED_UNKNOWN_NODE_LABEL
        val graph = run.pipelineId?.let { pipelineRepository.getPipelineById(it) }
            ?: return INTERRUPTED_UNKNOWN_NODE_LABEL
        val node = graph.nodes.firstOrNull { it.id == nodeId } ?: return INTERRUPTED_UNKNOWN_NODE_LABEL
        return node.label.ifBlank { node.type.name }
    }

    /**
     * Discards the interrupted run surfaced by the status card: settles the
     * persistent record as FAILED and drops the card. No-op when no interrupted
     * run is pending.
     */
    fun discardInterruptedRun() {
        val pending = state.value.pending.interrupted ?: return
        state.update { current ->
            val cleared = current.copy(pending = current.pending.copy(interrupted = null))
            cleared.copy(visual = cleared.restingVisual())
        }
        scope.launch {
            pipelineRunRepository.discardInterruptedRun(pending.runId)
        }
    }

    /**
     * Resume CTA of the interrupted-run card. Delegates to
     * [ResumePipelineRunUseCase]: on success the card is dropped, the surface
     * flips to `Generating` and the live collector attaches. On failure the card
     * stays up and the typed reason is surfaced through [resumeFeedbackEvents];
     * an expired checkpoint additionally demotes the card to discard-only.
     */
    fun resumeInterruptedRun() {
        val pending = state.value.pending.interrupted ?: return
        val sessionId = state.value.thread.currentSessionId
        scope.launch {
            when (resumePipelineRunUseCase(pending.runId)) {
                ResumeOutcome.Resumed -> {
                    state.update { current ->
                        current.copy(pending = current.pending.copy(interrupted = null))
                            .copy(visual = ChatHomeUiState.Generating())
                    }
                    attachToLiveRun(sessionId, PipelineRunStatus.QUEUED)
                }
                ResumeOutcome.GraphChanged -> _resumeFeedbackEvents.tryEmit(ResumeFeedbackEvent.GraphChanged)
                ResumeOutcome.Expired -> {
                    state.update { current ->
                        val demoted = current.pending.interrupted?.copy(resumable = false)
                        current.copy(pending = current.pending.copy(interrupted = demoted))
                    }
                    _resumeFeedbackEvents.tryEmit(ResumeFeedbackEvent.Expired)
                }
                ResumeOutcome.NotResumable -> _resumeFeedbackEvents.tryEmit(ResumeFeedbackEvent.NotResumable)
            }
        }
    }

    companion object {
        /**
         * Fallback node label rendered on the interrupted-run card when the run
         * stopped before reaching any node, the pipeline was deleted, or the
         * recorded node id no longer exists in the (since-edited) graph.
         */
        const val INTERRUPTED_UNKNOWN_NODE_LABEL: String = "unknown step"

        /** Milliseconds in one hour, for the resume-window pre-check on the interrupted card. */
        private const val MILLIS_PER_HOUR: Long = 3_600_000L
    }
}
