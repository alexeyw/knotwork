package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.knotwork.android.data.local.models.PipelineRunEntity
import app.knotwork.android.data.local.models.RunChatIdentityProjection
import app.knotwork.android.data.local.models.RunSpendProjection
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [PipelineRunEntity].
 *
 * Status strings are passed in by the repository (enum `name` values); every
 * mutating query that could race a terminal transition takes the terminal
 * status list as a `NOT IN` guard, so a finished run can never be flipped
 * back to an active status by a late writer.
 */
@Dao
interface PipelineRunDao {

    /**
     * Inserts a freshly enqueued run record. Conflicts are IGNOREd — an
     * existing row (whatever its status) is never overwritten, keeping the
     * insert consistent with the write-once terminal guard: a re-delivered or
     * racing insert can never resurrect a settled run as QUEUED.
     *
     * @param run The entity to insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRun(run: PipelineRunEntity)

    /**
     * Transitions the run to the RUNNING status, recording the resolved
     * pipeline id and graph content hash, unless the run is already terminal.
     *
     * @param runId Id of the run to update.
     * @param status The RUNNING status name.
     * @param pipelineId Id of the resolved pipeline.
     * @param graphContentHash Content hash of the resolved graph.
     * @param terminalStatuses Status names that must not be overwritten.
     */
    @Query(
        "UPDATE pipeline_runs SET status = :status, pipelineId = :pipelineId, " +
            "graphContentHash = :graphContentHash " +
            "WHERE id = :runId AND status NOT IN (:terminalStatuses)",
    )
    suspend fun markRunning(
        runId: String,
        status: String,
        pipelineId: String,
        graphContentHash: String,
        terminalStatuses: List<String>,
    )

    /**
     * Updates the run's status unless the run is already terminal.
     *
     * @param runId Id of the run to update.
     * @param status The new status name.
     * @param terminalStatuses Status names that must not be overwritten.
     */
    @Query(
        "UPDATE pipeline_runs SET status = :status " +
            "WHERE id = :runId AND status NOT IN (:terminalStatuses)",
    )
    suspend fun updateStatus(runId: String, status: String, terminalStatuses: List<String>)

    /**
     * Records the node currently executing unless the run is already terminal.
     *
     * @param runId Id of the run to update.
     * @param nodeId Id of the graph node that just started.
     * @param terminalStatuses Status names that must not be overwritten.
     */
    @Query(
        "UPDATE pipeline_runs SET currentNodeId = :nodeId " +
            "WHERE id = :runId AND status NOT IN (:terminalStatuses)",
    )
    suspend fun updateCurrentNode(runId: String, nodeId: String, terminalStatuses: List<String>)

    /**
     * Writes a terminal status with its finish timestamp and optional error
     * message. Idempotent: a run that is already terminal is left untouched.
     *
     * @param runId Id of the run to finish.
     * @param status The terminal status name to write.
     * @param finishedAt Epoch millis of the terminal transition.
     * @param errorMessage Failure / interruption reason, or `null`.
     * @param terminationReason `RunTerminationKind` name when the app itself
     *   decided to stop the run, or `null` for a completion or an ordinary
     *   node failure. Written in the same statement as the status so a
     *   terminal row can never exist without the cause that produced it.
     * @param terminalStatuses Status names that must not be overwritten.
     * @return The number of rows actually transitioned — `1` on a real
     *   terminal transition, `0` when the run was already terminal (the
     *   idempotent no-op case). Lets callers act only on a genuine transition.
     */
    @Query(
        "UPDATE pipeline_runs SET status = :status, finishedAt = :finishedAt, " +
            "errorMessage = :errorMessage, terminationReason = :terminationReason " +
            "WHERE id = :runId AND status NOT IN (:terminalStatuses)",
    )
    suspend fun finishRun(
        runId: String,
        status: String,
        finishedAt: Long,
        errorMessage: String?,
        terminationReason: String?,
        terminalStatuses: List<String>,
    ): Int

    /**
     * Writes the run tree's accumulated spend onto its root row.
     *
     * Absolute values rather than increments: the ledger in memory is the
     * authority while a run executes, and writing what it holds keeps the row
     * idempotent under a retry. The same `status NOT IN (:terminalStatuses)`
     * guard every other mutation carries applies here too — a settled run's
     * counters are part of the record of what happened and must not be moved
     * by a late write from a coroutine that has not noticed yet.
     *
     * @param rootRunId Id of the run at the root of the tree.
     * @param stepsSpent Node executions charged to the tree so far.
     * @param tokensSpent Tokens charged to the tree so far.
     * @param terminalStatuses Status names that must not be written to.
     */
    @Query(
        "UPDATE pipeline_runs SET stepsSpent = :stepsSpent, tokensSpent = :tokensSpent " +
            "WHERE id = :rootRunId AND status NOT IN (:terminalStatuses)",
    )
    suspend fun recordSpend(rootRunId: String, stepsSpent: Int, tokensSpent: Int, terminalStatuses: List<String>)

    /**
     * Reads back the spend already charged to a run tree.
     *
     * Projected rather than read through the whole row because the only caller
     * is the engine seeding its ledger at the top of a run, and a resume path
     * that loads and maps a full record just to take four integers would be
     * paying for columns it never looks at.
     *
     * @param rootRunId Id of the run at the root of the tree.
     * @return The persisted counters, or `null` when no such row exists.
     */
    @Query(
        "SELECT stepsSpent, tokensSpent, stepCeilingExtensions, tokenCeilingExtensions " +
            "FROM pipeline_runs WHERE id = :rootRunId",
    )
    suspend fun getSpend(rootRunId: String): RunSpendProjection?

    /**
     * Grants one more portion of the step ceiling to a run tree.
     *
     * An increment rather than an absolute write, and deliberately unlike
     * [recordSpend] beside it: the ledger in memory is the authority for spend,
     * but nothing in memory is the authority for grants — the answer that buys a
     * portion routinely arrives in a process where no ledger exists, hours after
     * the one that asked has died. `+ 1` in SQL is the only form that is correct
     * without a reader.
     *
     * Carries no terminal-status guard, unlike every other mutation here. The
     * caller increments *before* resuming, and the resume path requires the run
     * to be in a resumable status, so a settled run cannot reach this; adding
     * the guard would only hide a caller that got the order wrong.
     *
     * @param rootRunId Id of the run at the root of the tree.
     */
    @Query("UPDATE pipeline_runs SET stepCeilingExtensions = stepCeilingExtensions + 1 WHERE id = :rootRunId")
    suspend fun extendStepCeiling(rootRunId: String)

    /**
     * Grants one more portion of the token ceiling to a run tree.
     *
     * Split from [extendStepCeiling] rather than parameterised by axis because
     * a column name cannot be bound as a query parameter, and the alternative —
     * one statement with a `CASE` over both columns — writes to a column the
     * caller did not name on every call.
     *
     * @param rootRunId Id of the run at the root of the tree.
     */
    @Query("UPDATE pipeline_runs SET tokenCeilingExtensions = tokenCeilingExtensions + 1 WHERE id = :rootRunId")
    suspend fun extendTokenCeiling(rootRunId: String)

    /**
     * Returns the run with [runId], or `null` when no such row exists. Backs
     * checkpoint-resume validation, which addresses one specific run.
     *
     * @param runId Id of the run to load.
     */
    @Query("SELECT * FROM pipeline_runs WHERE id = :runId")
    suspend fun getRun(runId: String): PipelineRunEntity?

    /**
     * Returns the session and parent of [runId], or `null` when no such row
     * exists. Two-column projection, for the same reason [getSpend] is one:
     * read on every terminal transition, to decide whether the run leaves a line
     * in a chat and in which one.
     *
     * @param runId Id of the run to inspect.
     */
    @Query("SELECT sessionId, parentRunId FROM pipeline_runs WHERE id = :runId")
    suspend fun getRunChatIdentity(runId: String): RunChatIdentityProjection?

    /**
     * Returns just the [PipelineRunEntity.origin] discriminator of [runId], or
     * `null` when no such row exists. A single-column projection so a caller that
     * only needs to know *which surface* started a run (e.g. the terminal-outcome
     * observers, to skip work for non-trigger runs) avoids reading and mapping the
     * whole row.
     *
     * @param runId Id of the run to inspect.
     */
    @Query("SELECT origin FROM pipeline_runs WHERE id = :runId")
    suspend fun getRunOrigin(runId: String): String?

    /**
     * Counts the top-level runs of one [origin] that started at or after [sinceEpochMs].
     *
     * Backs the scheduling tool's runaway guard: a task that keeps re-scheduling
     * itself holds only ever **one** queued item, so the queue's depth says
     * nothing — the tell is how many scheduled runs actually fired in the recent
     * past. Counted in SQL so the guard never loads run rows it does not read.
     *
     * Only runs without a [PipelineRunEntity.parentRunId]: a nested pipeline's run
     * inherits its parent's origin and is part of the run that started it, so
     * counting it would spend the allowance once per nested step.
     *
     * @param origin `RunOrigin` name to count.
     * @param sinceEpochMs Inclusive lower bound on `startedAt`, epoch-millis.
     * @return The number of matching top-level runs.
     */
    @Query(
        "SELECT COUNT(*) FROM pipeline_runs " +
            "WHERE origin = :origin AND startedAt >= :sinceEpochMs AND parentRunId IS NULL",
    )
    suspend fun countRootRunsByOriginSince(origin: String, sinceEpochMs: Long): Int

    /**
     * Returns the direct child runs of [parentRunId] (the sub-pipeline runs a
     * `PIPELINE` node spawned), oldest first. Building the full run tree walks
     * this recursively in the repository — nesting is bounded by the runtime
     * depth ceiling, so a Kotlin-side recursion is simpler than a SQL CTE.
     *
     * @param parentRunId Id of the parent run.
     */
    @Query("SELECT * FROM pipeline_runs WHERE parentRunId = :parentRunId ORDER BY startedAt ASC")
    suspend fun getChildRuns(parentRunId: String): List<PipelineRunEntity>

    /**
     * Flips a resumable run back to the QUEUED status for checkpoint resume,
     * clearing the markers ([PipelineRunEntity.finishedAt],
     * [PipelineRunEntity.errorMessage], [PipelineRunEntity.terminationReason])
     * a sweep may have stamped — a run that is running again must not still
     * carry the reason it was stopped for. The
     * `WHERE status = :fromStatus` guard pins the transition to the expected
     * starting status — INTERRUPTED for the terminal-exit path next to
     * [discardInterruptedRun], or a WAITING_* status for a parked run whose
     * pending interaction was answered; any other status leaves the row
     * untouched.
     *
     * @param runId Id of the run to resume.
     * @param fromStatus The status name the row must currently hold.
     * @param toStatus The QUEUED status name to write.
     * @return The number of updated rows — `1` when the guarded transition
     *   applied, `0` when the row was missing or in a different status.
     */
    @Query(
        "UPDATE pipeline_runs SET status = :toStatus, finishedAt = NULL, errorMessage = NULL, " +
            "terminationReason = NULL " +
            "WHERE id = :runId AND status = :fromStatus",
    )
    suspend fun markResumed(runId: String, fromStatus: String, toStatus: String): Int

    /**
     * Returns the most recently started top-level run of [sessionId] whose
     * status is in [activeStatuses], or `null` when the session has no active
     * top-level run. The `parentRunId IS NULL` guard keeps sub-pipeline runs
     * (which share the session and start *after* their parent) from masking the
     * root the reattach protocol must surface.
     *
     * @param sessionId Id of the chat session to query.
     * @param activeStatuses Non-terminal status names to match.
     */
    @Query(
        "SELECT * FROM pipeline_runs WHERE sessionId = :sessionId AND parentRunId IS NULL " +
            "AND status IN (:activeStatuses) ORDER BY startedAt DESC LIMIT 1",
    )
    suspend fun getActiveRunForSession(sessionId: String, activeStatuses: List<String>): PipelineRunEntity?

    /**
     * Returns the most recently started top-level run of [sessionId] regardless
     * of status, or `null` when the session has never had a top-level run.
     * Backs the console replay baseline for sessions whose last run already
     * finished; sub-pipeline runs are excluded (`parentRunId IS NULL`) — the
     * console reconstructs the nested trace from the root's run tree.
     *
     * @param sessionId Id of the chat session to query.
     */
    @Query(
        "SELECT * FROM pipeline_runs WHERE sessionId = :sessionId AND parentRunId IS NULL " +
            "ORDER BY startedAt DESC LIMIT 1",
    )
    suspend fun getLatestRunForSession(sessionId: String): PipelineRunEntity?

    /**
     * Observes all top-level runs of [sessionId], most recently started first.
     * Sub-pipeline runs are excluded — they are internal to their root's tree.
     *
     * @param sessionId Id of the chat session to observe.
     */
    @Query(
        "SELECT * FROM pipeline_runs WHERE sessionId = :sessionId AND parentRunId IS NULL " +
            "ORDER BY startedAt DESC",
    )
    fun observeRunsForSession(sessionId: String): Flow<List<PipelineRunEntity>>

    /**
     * Returns every run whose status is in [statuses]. Used by the orphan
     * sweep at application start (all non-terminal statuses; process-owned
     * runs are filtered out by the repository).
     *
     * @param statuses Status names to match.
     */
    @Query("SELECT * FROM pipeline_runs WHERE status IN (:statuses)")
    suspend fun getRunsByStatuses(statuses: List<String>): List<PipelineRunEntity>

    /**
     * Observes the distinct session ids owning a run whose status is in
     * [statuses]. Backs the drawer thread-list activity indicator (all
     * non-terminal statuses). A single-column DISTINCT projection on
     * purpose: Room re-runs the query on every `pipeline_runs` write (the
     * engine writes per-node progress throughout a run), so each
     * invalidation must stay a cheap column read instead of materialising
     * full rows the consumer would reduce to ids anyway.
     *
     * @param statuses Status names to match.
     */
    @Query(
        "SELECT DISTINCT sessionId FROM pipeline_runs WHERE parentRunId IS NULL AND status IN (:statuses)",
    )
    fun observeSessionIdsByStatuses(statuses: List<String>): Flow<List<String>>

    /**
     * Discards an interrupted run: flips it to the FAILED status with the
     * supplied error message. The `WHERE status = :fromStatus` guard pins the
     * transition to INTERRUPTED rows only — the single sanctioned
     * terminal-to-terminal transition (user dismissed the resume offer); any
     * other status leaves the row untouched.
     *
     * @param runId Id of the run to discard.
     * @param fromStatus The INTERRUPTED status name the row must currently hold.
     * @param toStatus The FAILED status name to write.
     * @param errorMessage The "discarded by user" marker to record.
     * @param terminationReason The matching `RunTerminationKind` name, written
     *   in the same statement so the typed cause and its rendering cannot drift.
     */
    @Query(
        "UPDATE pipeline_runs SET status = :toStatus, errorMessage = :errorMessage, " +
            "terminationReason = :terminationReason " +
            "WHERE id = :runId AND status = :fromStatus",
    )
    suspend fun discardInterruptedRun(
        runId: String,
        fromStatus: String,
        toStatus: String,
        errorMessage: String,
        terminationReason: String,
    )

    /**
     * Retention: deletes every **terminal top-level** run that is not among the
     * [keepPerSession] most recently started top-level runs of its own session.
     * Only top-level runs are counted and deleted (`parentRunId IS NULL`) — a
     * deleted root takes its whole sub-pipeline tree with it through the
     * self-referential `parentRunId` foreign-key cascade, and each run's
     * persisted trace rides the `trace_steps.runId` cascade in turn. The
     * per-session window counts top-level runs of *any* status, so a session
     * whose recent slots are filled by active runs keeps proportionally fewer
     * old terminal ones — the window is a hard cap, not a terminal-only quota.
     * Non-terminal runs (including WAITING_* runs parked on a background
     * approval or clarification) are never deleted: their expiry is owned by
     * the pending-interaction maintenance pass, which settles them to FAILED
     * first.
     *
     * @param keepPerSession How many most-recent top-level runs each session keeps.
     * @param terminalStatuses Terminal status names — the only deletable ones.
     * @return The number of deleted runs.
     */
    @Query(
        "DELETE FROM pipeline_runs WHERE parentRunId IS NULL AND status IN (:terminalStatuses) AND id NOT IN (" +
            "SELECT recent.id FROM pipeline_runs AS recent " +
            "WHERE recent.sessionId = pipeline_runs.sessionId AND recent.parentRunId IS NULL " +
            "ORDER BY recent.startedAt DESC LIMIT :keepPerSession)",
    )
    suspend fun deleteTerminalRunsBeyondSessionLimit(keepPerSession: Int, terminalStatuses: List<String>): Int

    /**
     * Retention: deletes every **terminal top-level** run whose terminal
     * transition happened before [cutoff], regardless of the per-session count.
     * Only top-level runs are targeted (`parentRunId IS NULL`); their
     * sub-pipeline children ride the self-referential `parentRunId` cascade, so
     * a child is never deleted out from under a parent that is still inside the
     * window. Rows with a `NULL` `finishedAt` are left untouched (terminal rows
     * always carry the timestamp; the guard is defence in depth). Persisted
     * trace rows ride the `trace_steps.runId` foreign-key cascade.
     *
     * @param cutoff Epoch millis; runs finished strictly before it are deleted.
     * @param terminalStatuses Terminal status names — the only deletable ones.
     * @return The number of deleted runs.
     */
    @Query(
        "DELETE FROM pipeline_runs WHERE parentRunId IS NULL AND status IN (:terminalStatuses) " +
            "AND finishedAt IS NOT NULL AND finishedAt < :cutoff",
    )
    suspend fun deleteTerminalRunsFinishedBefore(cutoff: Long, terminalStatuses: List<String>): Int
}
