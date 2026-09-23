package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ClarificationRequest
import app.knotwork.android.domain.models.HardCeilingBreach
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.models.diagnostic
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.usecases.PendingSubmissionOutcome
import app.knotwork.android.domain.usecases.SubmitApprovalDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitCeilingDecisionUseCase
import app.knotwork.android.domain.usecases.SubmitClarificationAnswerUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Human-in-the-loop / clarification delegate of [ChatHomeViewModel].
 *
 * Owns the approval gate, the clarification reply and the run-ceiling pause: it
 * writes the `pending.tool` / `pending.clarification` / `pending.ceiling`
 * snapshots, the destructive typed-confirm input, and (because they all flip the
 * surface) the `visual` axis.
 * On a parked-run resume it re-attaches the live collector through the
 * [attachToLiveRun] seam (the ViewModel owns the collector), and surfaces
 * resume failures through [emitResumeFeedback] (the reattach delegate owns the
 * shared `resumeFeedbackEvents` channel).
 *
 * The capture handlers [handleWaitingForApproval] / [handleAwaitingClarification]
 * / [restoreCeilingPause] are public because the ViewModel's orchestrator router
 * and the reattach delegate's suspension-card restore both drive them.
 *
 * Shares the ViewModel's [scope] and single [state] reducer (see
 * `docs/architecture.md` §1.2).
 *
 * @property scope The ViewModel's [androidx.lifecycle.viewModelScope].
 * @property state The ViewModel's single source-of-truth state flow.
 * @property chatRepository Persists the SYSTEM rows recording a denial / undelivered reply.
 * @property submitApprovalDecisionUseCase Routes an approve/deny to the live gate or parked record.
 * @property submitClarificationAnswerUseCase Routes a clarification reply likewise.
 * @property submitCeilingDecisionUseCase Routes a continue/stop decision on a run paused at a ceiling.
 * @property attachToLiveRun Seam into the ViewModel's live-run collector (a parked resume re-attaches).
 * @property emitResumeFeedback Seam into the shared resume-feedback channel (owned by the reattach delegate).
 */
class ChatHomeHitlDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatHomeScreenState>,
    private val chatRepository: ChatRepository,
    private val submitApprovalDecisionUseCase: SubmitApprovalDecisionUseCase,
    private val submitClarificationAnswerUseCase: SubmitClarificationAnswerUseCase,
    private val submitCeilingDecisionUseCase: SubmitCeilingDecisionUseCase,
    private val attachToLiveRun: suspend (String, PipelineRunStatus) -> Unit,
    private val emitResumeFeedback: (ResumeFeedbackEvent) -> Unit,
) {

    /** Updates the typed-confirm input shown next to the Destructive HITL confirmation row. */
    fun onTypedConfirmChange(value: String) {
        state.update { it.copy(composer = it.composer.copy(typedConfirm = value)) }
    }

    /**
     * Approves the request the card shows. For a destructive tool the approval
     * is gated on the typed-confirm matching the canonical magic word (`"yes"`,
     * trimmed, case-insensitive) — the catalog `HitlConfirmationCard` already
     * disables the Allow CTA in that case, but the gate is mirrored defensively
     * so a programmatic caller cannot bypass it. No-op when no tool is pending.
     *
     * The answer names the card's request, so the risk checked here is the risk
     * of the request that can be settled: when the run has since moved on to a
     * different request, this answer settles nothing rather than that one.
     */
    fun approveTool() {
        val pending = state.value.pending.tool ?: return
        if (pending.risk == ToolRisk.DESTRUCTIVE && !isTypedConfirmValid()) return
        val sessionId = state.value.thread.currentSessionId
        if (sessionId.isBlank()) return
        state.update {
            it.copy(
                pending = it.pending.copy(tool = null),
                composer = it.composer.copy(typedConfirm = ""),
                visual = ChatHomeUiState.Generating(),
            )
        }
        scope.launch { submitApprovalDecision(sessionId, pending.requestId, isApproved = true) }
    }

    /**
     * Rejects the tool the orchestrator is paused on. Persists a SYSTEM chat row
     * recording the denial so the user can see in-thread what happened. No-op
     * when no tool is pending.
     */
    fun rejectTool() {
        val pending = state.value.pending.tool ?: return
        val sessionId = state.value.thread.currentSessionId
        if (sessionId.isBlank()) return
        // Resuming the pipeline restarts orchestrator emission — keep the
        // surface in `Generating` until the next state (or a terminal
        // Completed / Error) settles it, otherwise the chat appears idle
        // while the agent is actively producing the denial follow-up.
        state.update {
            it.copy(
                pending = it.pending.copy(tool = null),
                composer = it.composer.copy(typedConfirm = ""),
                visual = ChatHomeUiState.Generating(),
            )
        }
        scope.launch {
            submitApprovalDecision(sessionId, pending.requestId, isApproved = false)
            chatRepository.saveMessage(
                ChatMessage(
                    sessionId = sessionId,
                    role = Role.SYSTEM,
                    content = SYSTEM_MESSAGE_TOOL_DENIED.format(pending.toolName),
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Submits the user's reply to the active clarification request. The pipeline
     * resumes immediately; the agent then publishes its next state through the
     * live collector. When the repository reports the reply was NOT consumed
     * (the request already resolved by timeout or an earlier answer), a SYSTEM
     * chat row records that the agent proceeded without it — silently dropping
     * the choice would misrepresent what the pipeline consumed.
     *
     * @param answer the user's reply text (option label or free-form).
     */
    fun submitClarificationReply(answer: String) {
        val pending = state.value.pending.clarification ?: return
        val sessionId = state.value.thread.currentSessionId
        // Allow an empty reply through — the orchestrator already accepts
        // `""` as the timeout fallback for free-form requests, so an
        // intentional blank submit is a legitimate "skip" affordance.
        val trimmed = answer.trim()
        state.update {
            it.copy(
                pending = it.pending.copy(clarification = null),
                visual = ChatHomeUiState.Generating(),
            )
        }
        scope.launch {
            routePendingOutcome(submitClarificationAnswerUseCase(sessionId, pending.id, trimmed), sessionId) {
                // Neither a live deferred nor a parked record consumed the reply
                // — record in-thread that the agent proceeded without it, exactly
                // like the legacy undelivered path.
                state.update { it.copy(visual = it.restingVisual()) }
                if (sessionId.isNotBlank()) {
                    chatRepository.saveMessage(
                        ChatMessage(
                            sessionId = sessionId,
                            role = Role.SYSTEM,
                            content = SYSTEM_MESSAGE_CLARIFICATION_REPLY_NOT_DELIVERED,
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Grants the paused run one more portion of the limit it reached and
     * resumes it from its checkpoint. No-op when no pause is showing.
     *
     * The card is dropped and the surface flips to `Generating` before the
     * submission returns, because the answer really does restart the run — and
     * a card left on screen through a resume invites a second tap that would
     * either buy a second portion or be refused as a duplicate, depending on
     * timing.
     */
    fun continuePastCeiling() {
        val pending = state.value.pending.ceiling ?: return
        val sessionId = state.value.thread.currentSessionId
        if (sessionId.isBlank()) return
        state.update {
            it.copy(pending = it.pending.copy(ceiling = null), visual = ChatHomeUiState.Generating())
        }
        scope.launch {
            submitCeilingDecision(sessionId, pending.runId, shouldContinue = true) {
                state.update { it.copy(visual = it.restingVisual()) }
            }
        }
    }

    /**
     * Stops the paused run at the limit it reached. No-op when no pause is
     * showing.
     *
     * The surface flips to the same typed-termination tile a run gets when it
     * reaches a limit any other way — the reason is built from the pause's own
     * breach, so the tile states the numbers the card just showed and offers
     * **Adjust limits**. This has to be done here: the engine coroutine ended
     * when the run parked, so no terminal orchestrator state is coming, and
     * without it stopping would leave the chat looking as though nothing had
     * happened.
     *
     * `announcedInThread` is set because settling the run writes its own
     * outcome line into the conversation. The tile then explains without
     * repeating it — the same arrangement every settled run already uses.
     *
     * A record that cannot name its breach falls back to the resting visual
     * rather than inventing a tile: the run is still stopped by the submission
     * below, and its outcome line is the account the user gets.
     */
    fun stopAtCeiling() {
        val pending = state.value.pending.ceiling ?: return
        val sessionId = state.value.thread.currentSessionId
        if (sessionId.isBlank()) return
        val reason = pending.breach.asTerminationReason()
        state.update { current ->
            val cleared = current.copy(pending = current.pending.copy(ceiling = null))
            cleared.copy(
                visual = if (reason != null) {
                    ChatHomeUiState.Error(
                        message = reason.diagnostic(),
                        reason = reason,
                        announcedInThread = true,
                    )
                } else {
                    cleared.restingVisual()
                },
            )
        }
        scope.launch {
            // `NothingPending` is what a *successful* stop reports — once the
            // run is settled there is nothing left pending — so the tile raised
            // above must stay. Resetting here (as the continue path does, where
            // the same outcome means the answer did not land) would wipe the
            // only account the surface gives of what just happened.
            submitCeilingDecision(sessionId, pending.runId, shouldContinue = false) {}
        }
    }

    /**
     * Captures a run paused at one of its ceilings, live from the orchestrator,
     * and flips the UI to the pause state.
     *
     * @param pause The emission carrying the axis and both numbers. It carries
     *   no run id and does not need to — see [CeilingPausePending.runId].
     */
    fun handleCeilingPause(pause: AgentOrchestratorState.WaitingForCeilingRaise) {
        restoreCeilingPause(
            CeilingPausePending(
                runId = null,
                breach = HardCeilingBreach(axis = pause.axis, limit = pause.limit, spent = pause.spent),
                timestamp = chatRowTimestamp(System.currentTimeMillis()),
            ),
        )
    }

    /**
     * Installs a ceiling-pause snapshot and flips the surface to the pause
     * state.
     *
     * Shared by the live path above and the reattach delegate's restore, so a
     * pause looks the same whether it was just raised or is being picked up
     * hours later from its durable record.
     *
     * @param pending The pause to surface.
     */
    fun restoreCeilingPause(pending: CeilingPausePending) {
        state.update {
            it.copy(
                pending = it.pending.copy(ceiling = pending),
                visual = ChatHomeUiState.CeilingPause,
            )
        }
    }

    /** Captures the orchestrator's pending approval and flips the UI to the HITL state. */
    fun handleWaitingForApproval(approval: AgentOrchestratorState.WaitingForApproval) {
        state.update {
            it.copy(
                pending = it.pending.copy(
                    tool = HitlPending(
                        toolName = approval.toolName,
                        arguments = approval.arguments,
                        risk = approval.risk,
                        requestId = approval.requestId,
                    ),
                ),
                composer = it.composer.copy(typedConfirm = ""),
                visual = ChatHomeUiState.HitlConfirm(approval.risk.toCatalogRisk()),
            )
        }
    }

    /**
     * Captures the orchestrator's pending clarification and flips the UI to the
     * Clarification state. No UI-side timeout runs against the card: the
     * repository owns the live waiting window, and on elapse the run parks
     * persistently so the card stays answerable through the parked-run path.
     *
     * @param request the pending clarification to surface.
     */
    fun handleAwaitingClarification(request: ClarificationRequest) {
        state.update {
            it.copy(
                pending = it.pending.copy(clarification = request),
                visual = ChatHomeUiState.Clarification,
            )
        }
    }

    /**
     * Routes the user's continue / stop decision through
     * [SubmitCeilingDecisionUseCase] and folds the outcome onto the shared
     * resume plumbing.
     *
     * `NothingPending` means opposite things on the two paths, which is why the
     * caller supplies its handling. On a continue it means the answer did not
     * land (already settled, or a racing duplicate lost) and the optimistic
     * `Generating` must be undone; on a stop it is the *success* report — there
     * is nothing left pending once the run is settled.
     *
     * Neither path writes an in-thread note, unlike a clarification reply:
     * settling the run already writes its own outcome line, and a racing
     * duplicate's loser has nothing to add that the winner's effect does not
     * already say.
     *
     * @param sessionId The session whose run is being answered.
     * @param runId The parked run's id, or `null` to answer the session's record.
     * @param shouldContinue `true` to grant a portion, `false` to stop the run.
     * @param onNothingPending What to do when the submission reports nothing
     *   pending — see above.
     */
    private suspend fun submitCeilingDecision(
        sessionId: String,
        runId: String?,
        shouldContinue: Boolean,
        onNothingPending: suspend () -> Unit,
    ) {
        val outcome = submitCeilingDecisionUseCase(
            sessionId = sessionId,
            shouldContinue = shouldContinue,
            runId = runId,
        )
        routePendingOutcome(outcome, sessionId, onNothingPending)
    }

    /**
     * Routes the user's approve / deny decision on request [requestId] through
     * [SubmitApprovalDecisionUseCase] (live gate first, then the parked record)
     * and folds the outcome onto the resume plumbing via [routePendingOutcome].
     *
     * @param sessionId The session the card belongs to.
     * @param requestId Identity of the request the card showed.
     * @param isApproved `true` to approve, `false` to deny.
     */
    private suspend fun submitApprovalDecision(sessionId: String, requestId: String, isApproved: Boolean) {
        routePendingOutcome(submitApprovalDecisionUseCase(sessionId, requestId, isApproved), sessionId) {
            state.update { it.copy(visual = it.restingVisual()) }
        }
    }

    /**
     * Maps a [PendingSubmissionOutcome] from a parked approval / clarification
     * submission onto the shared resume plumbing: a resumed parked run attaches
     * exactly like a resumed interrupted run; `GraphChanged` / `Expired` surface
     * through [emitResumeFeedback] and settle the visual back to its resting
     * state so the chat is not stuck on `Generating`. The only caller-specific
     * behaviour is [onNothingPending] — the approval path just settles the
     * visual, while the clarification path also records an undelivered-reply
     * SYSTEM row.
     *
     * @param outcome The submission outcome to route.
     * @param sessionId The session whose run is being resumed.
     * @param onNothingPending Caller-specific handling when neither a live
     *   deferred nor a parked record consumed the submission.
     */
    private suspend fun routePendingOutcome(
        outcome: PendingSubmissionOutcome,
        sessionId: String,
        onNothingPending: suspend () -> Unit,
    ) {
        when (outcome) {
            PendingSubmissionOutcome.LiveResumed -> Unit
            PendingSubmissionOutcome.Resumed -> attachToLiveRun(sessionId, PipelineRunStatus.QUEUED)
            PendingSubmissionOutcome.GraphChanged -> {
                emitResumeFeedback(ResumeFeedbackEvent.GraphChanged)
                state.update { it.copy(visual = it.restingVisual()) }
            }
            PendingSubmissionOutcome.Expired -> {
                emitResumeFeedback(ResumeFeedbackEvent.Expired)
                state.update { it.copy(visual = it.restingVisual()) }
            }
            PendingSubmissionOutcome.NothingPending -> onNothingPending()
        }
    }

    /** Whether the current typed-confirm input satisfies the destructive HITL gate. */
    private fun isTypedConfirmValid(): Boolean =
        state.value.composer.typedConfirm.trim().equals(DESTRUCTIVE_TYPED_CONFIRM_WORD, ignoreCase = true)

    companion object {
        /**
         * Magic word the user must type to confirm a destructive tool call.
         * Mirrors the catalog `HitlConfirmationState.DESTRUCTIVE_CONFIRM_WORD`
         * but duplicated here so the gate stays free of `:catalog` imports.
         */
        const val DESTRUCTIVE_TYPED_CONFIRM_WORD: String = "yes"

        /** Template of the SYSTEM chat row persisted when the user rejects a tool call. `%s` is the tool name. */
        const val SYSTEM_MESSAGE_TOOL_DENIED: String = "Tool '%s' denied by user."

        /**
         * SYSTEM chat row persisted when the user's clarification reply was not
         * consumed by the pipeline (the request had already resolved — typically
         * the repository's timeout fired while a reattach-restored card was still
         * on screen).
         */
        const val SYSTEM_MESSAGE_CLARIFICATION_REPLY_NOT_DELIVERED: String =
            "Reply was not delivered — the clarification had already been resolved with a default answer."
    }
}
