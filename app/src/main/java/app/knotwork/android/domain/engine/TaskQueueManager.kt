package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AgentTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for managing and queueing agent tasks to ensure
 * the LLM processes one request at a time without memory issues.
 */
interface TaskQueueManager {

    /**
     * A global state flow representing the overall processing state of the agent across all tasks.
     */
    val globalState: StateFlow<AgentOrchestratorState>

    /**
     * The latest **status** of every session the queue has run, keyed by session id — what
     * the task lists show, not the stream a chat renders.
     *
     * It changes when a session is queued, picked up, reaches a new stage or tool, and
     * settles (completed, failed, stopped), and drops a session the queue has evicted.
     * Streamed text is not carried (`Thinking` / `Answering` hold an empty string, so a
     * generation changes the map once, not per token), and telemetry — console lines,
     * traces, node I/O, notices, observations — never replaces a status. A chat that needs
     * the text or the telemetry observes its own session through [observeTaskState].
     */
    val activeSessionsState: StateFlow<Map<String, AgentOrchestratorState>>

    /**
     * Enqueues a new task to be processed.
     *
     * @param task The [AgentTask] to add to the queue.
     */
    fun enqueueTask(task: AgentTask)

    /**
     * Observes the execution state for a specific session.
     *
     * @param sessionId The ID of the session.
     * @return A [Flow] of [AgentOrchestratorState] for the given session.
     */
    fun observeTaskState(sessionId: String): Flow<AgentOrchestratorState>

    /**
     * Completes the live approval request [requestId] of [sessionId] with the
     * user's decision.
     *
     * The decision settles only the request it names: when the session is live
     * on a different request (or on none), nothing is settled and `false` comes
     * back, so the caller can look for the parked record [requestId] names
     * instead. Answers reach this only through
     * `SubmitApprovalDecisionUseCase`.
     *
     * @param sessionId The session ID waiting for approval.
     * @param requestId Identity of the request the decision was given for.
     * @param isApproved True if the user approved the action.
     * @return `true` when the decision settled the live request it names.
     */
    fun resumeWithApproval(sessionId: String, requestId: String, isApproved: Boolean): Boolean

    /**
     * Cancels the run of [sessionId] — the one executing, and any of that
     * session's tasks still waiting in the queue.
     *
     * The composer's Stop button used to only detach the screen from the
     * stream: the run carried on in the queue's own scope and its answer
     * arrived anyway, so a control named `stop` did something the word does not
     * mean. Cancelling settles the run as
     * [app.knotwork.android.domain.models.PipelineRunStatus.CANCELLED], which is
     * how it reaches the conversation as a line rather than as a failure.
     *
     * Cancelling one run must not disturb another. Every other session's work —
     * running, queued, or parked on a human's answer — is untouched, which is
     * the whole reason this is per-session rather than a scope teardown.
     *
     * A no-op when the session has nothing in flight, so a Stop pressed as the
     * last token arrives cannot resurrect or corrupt a finished run.
     *
     * @param sessionId The session whose run should be stopped.
     */
    fun cancelRun(sessionId: String)

    /**
     * Returns the tool-approval request the run of [sessionId] is currently
     * suspended on, or `null` when no approval gate is active for that session.
     *
     * Counterpart of [resumeWithApproval] for the chat reattach protocol: a UI
     * re-attaching to a session whose persistent run record reads
     * `WAITING_APPROVAL` restores the confirmation card from this snapshot —
     * the per-session state flow's replay cache cannot be relied on because
     * console events emitted while the run waits overwrite the
     * [AgentOrchestratorState.WaitingForApproval] emission.
     *
     * @param sessionId The session ID whose pending approval is queried.
     * @return The pending [AgentOrchestratorState.WaitingForApproval], or `null`.
     */
    fun pendingApproval(sessionId: String): AgentOrchestratorState.WaitingForApproval?
}
