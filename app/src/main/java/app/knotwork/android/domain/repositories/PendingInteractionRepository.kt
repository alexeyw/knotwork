package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.PendingDecision
import app.knotwork.android.domain.models.PendingInteraction

/**
 * Store of [PendingInteraction] records — the persistent half of the
 * two-phase HITL waiting protocol.
 *
 * A record exists exactly while a run is parked in its persistent waiting
 * phase (run status `WAITING_APPROVAL` / `WAITING_CLARIFICATION` /
 * `WAITING_CEILING`, with no live in-process gate). Writers: the node executors
 * create records when their live phase times out, and the engine creates one
 * outright when a ceiling binds — that gate has no live phase, because nothing
 * is in flight between two nodes for a park to have to preserve. The decision
 * use cases record the user's response; the record is consumed one-shot on
 * resume, by the executor that raised it or — for a ceiling, which belongs to
 * no node — by the engine itself; the maintenance worker deletes expired
 * records when failing their runs.
 *
 * **Failure contract.** [save] reports success explicitly because its caller
 * must fall back to failing the run when the park cannot be made durable — a
 * parked run without a record would be unrecoverable. All other operations
 * follow the best-effort contract of the run store: storage failures are
 * absorbed (logged, neutral result) and never propagate.
 */
interface PendingInteractionRepository {

    /**
     * Persists [interaction], replacing any previous record of the same run.
     *
     * @param interaction The record to write.
     * @return `true` when the write is durable; `false` when storage failed —
     *   the caller must not park the run on a record that does not exist.
     */
    suspend fun save(interaction: PendingInteraction): Boolean

    /**
     * Returns the pending interaction of run [runId], or `null` when the run
     * is not parked.
     *
     * @param runId Id of the run to look up.
     */
    suspend fun getForRun(runId: String): PendingInteraction?

    /**
     * Returns the most recently parked pending interaction of session
     * [sessionId], or `null` when the session has none. Backs the chat
     * reattach protocol after process death, when the in-memory request
     * snapshot is gone.
     *
     * @param sessionId Id of the chat session to look up.
     */
    suspend fun getForSession(sessionId: String): PendingInteraction?

    /**
     * Returns the parked record of the approval request [requestId], or `null`
     * when no record parks that request (never parked, already consumed, or
     * replaced by a later request of the same run).
     *
     * The lookup an approval answer takes: it names the request it was given
     * for, never the session, because one session can hold a parked request
     * and a live one at the same time.
     *
     * @param requestId Identity of the request, as minted by the gate.
     */
    suspend fun getForRequest(requestId: String): PendingInteraction?

    /**
     * Records the user's [decision] onto the pending interaction of run
     * [runId], guarded to first-writer-wins: a record that already holds a
     * decision is left untouched.
     *
     * For a ceiling pause, which asks about the run rather than about a
     * request. An approval answer goes through [recordApprovalDecision], which
     * also checks that the record still parks the request answered.
     *
     * @param runId Id of the parked run.
     * @param decision The user's decision to record.
     * @return `true` when this call recorded the decision; `false` when the
     *   record is missing or already decided (duplicate notification tap).
     */
    suspend fun recordDecision(runId: String, decision: PendingDecision): Boolean

    /**
     * Records the user's approval [decision] onto the pending interaction of
     * run [runId] — only while that record still parks request [requestId] —
     * guarded to first-writer-wins like [recordDecision].
     *
     * The request guard makes the write exact: between reading a record and
     * writing to it, a re-park of the same run can replace it with a different
     * request, and the answer given for the first must not land on the second.
     *
     * @param runId Id of the parked run.
     * @param requestId Identity of the request the decision was given for.
     * @param decision The user's decision to record.
     * @return `true` when this call recorded the decision; `false` when the
     *   record is missing, parks a different request, or is already decided
     *   (duplicate notification tap).
     */
    suspend fun recordApprovalDecision(runId: String, requestId: String, decision: PendingDecision): Boolean

    /**
     * Records the user's clarification [answer] onto the pending interaction
     * of run [runId], guarded to first-writer-wins like [recordDecision].
     *
     * @param runId Id of the parked run.
     * @param answer The user's answer to record.
     * @return `true` when this call recorded the answer; `false` when the
     *   record is missing or already answered.
     */
    suspend fun recordAnswer(runId: String, answer: String): Boolean

    /**
     * Deletes the pending interaction of run [runId]. Consumption point of
     * the one-shot protocol; idempotent when no record exists.
     *
     * @param runId Id of the run whose record to delete.
     */
    suspend fun delete(runId: String)

    /**
     * Returns every pending interaction whose persistent waiting phase began
     * at or before [cutoffEpochMillis]. Backs the maintenance expiry pass.
     *
     * @param cutoffEpochMillis Inclusive upper bound on [PendingInteraction.requestedAt].
     */
    suspend fun getRequestedAtOrBefore(cutoffEpochMillis: Long): List<PendingInteraction>

    /**
     * Returns the run ids of all pending interactions. The startup orphan
     * sweep uses this set to exempt persistently waiting runs — they are
     * parked by design, not orphaned by a process death.
     */
    suspend fun getAllRunIds(): Set<String>
}
