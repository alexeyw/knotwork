package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteractionKind
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import javax.inject.Inject

/**
 * Single entry point for the user's approve / deny decision on a HITL tool
 * gate, regardless of which waiting phase the run is in and which surface
 * the decision comes from (in-chat card, notification action — including
 * after process death).
 *
 * **A decision is addressed to a request, never to a session.** Every surface
 * that shows a request carries the identity the gate minted for it, and the
 * decision settles that request or nothing. The session is not an address: a
 * parked request and a live one coexist in one session whenever a second run
 * starts there (parking frees the queue worker), and "whatever this session is
 * waiting on" would then let the answer given for one request authorise
 * another, typed confirmation and all.
 *
 * Routing, in order:
 *  1. **Live phase** — the session's in-process gate is waiting on this very
 *     request: complete it via [TaskQueueManager.resumeWithApproval].
 *  2. **Persistent phase** — a pending-interaction record parks this request:
 *     record the decision onto it (one-shot, first-writer-wins, and only while
 *     the record still parks this request) and resume the run from its
 *     checkpoint via [ParkedRunResumer]. The resumed TOOL node consumes the
 *     decision under its TOCTOU argument guard. A denial resumes too — the run
 *     continues through the standard "Execution denied by user" observation
 *     path rather than failing.
 *
 * Anything else — a request already answered, stopped, expired, or replaced —
 * is [PendingSubmissionOutcome.NothingPending], whatever else the session is
 * waiting on.
 */
class SubmitApprovalDecisionUseCase @Inject constructor(
    private val taskQueueManager: TaskQueueManager,
    private val pendingInteractionRepository: PendingInteractionRepository,
    private val parkedRunResumer: ParkedRunResumer,
) {

    /**
     * Submits the user's decision on approval request [requestId] of [sessionId].
     *
     * @param sessionId Id of the session the request belongs to.
     * @param requestId Identity of the request the decision was given for —
     *   the one the answering surface displayed.
     * @param isApproved `true` to approve the staged tool call, `false` to deny it.
     * @return The typed outcome for UI mapping.
     */
    suspend operator fun invoke(sessionId: String, requestId: String, isApproved: Boolean): PendingSubmissionOutcome {
        if (taskQueueManager.resumeWithApproval(sessionId, requestId, isApproved)) {
            return PendingSubmissionOutcome.LiveResumed
        }

        val pending = pendingInteractionRepository.getForRequest(requestId)
            ?.takeIf { it.kind == PendingInteractionKind.APPROVAL }
            ?: return PendingSubmissionOutcome.NothingPending

        val decision = if (isApproved) PendingDecision.APPROVED else PendingDecision.DENIED
        return parkedRunResumer.submit(pending) { parkedRunId ->
            pendingInteractionRepository.recordApprovalDecision(parkedRunId, requestId, decision)
        }
    }
}
