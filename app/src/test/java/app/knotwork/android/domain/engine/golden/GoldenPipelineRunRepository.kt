package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.repositories.PipelineRunRepository
import kotlinx.coroutines.flow.Flow

/**
 * In-memory run store of the golden harness that records every write the engine and the
 * `PIPELINE` executor make to run records.
 *
 * `updateCurrentNode` is the visit boundary of the trace: the engine calls it before every
 * node, `INPUT` and `OUTPUT` included (which write no `NodeIo`), so the harness forwards it to
 * the [GoldenNodeTracker] and records the visit. The harness itself plays the task queue and
 * seeds the root record through [seedRoot] and [prepareResume], which are not recorded — they
 * are the queue's writes, not the engine's.
 *
 * Methods no run path reaches throw, so a refactor that starts using one shows up as a failing
 * scenario rather than as a silently answered call.
 *
 * @param log The run's event log.
 * @param tracker Receives the visit boundaries.
 */
internal class GoldenPipelineRunRepository(private val log: GoldenEventLog, private val tracker: GoldenNodeTracker) :
    PipelineRunRepository {

    private val runs = mutableMapOf<String, PipelineRun>()
    private val spend = mutableMapOf<String, RunSpend>()

    /**
     * Creates the root run record as the task queue would before the first attempt.
     *
     * @param run The record, in `RUNNING`.
     */
    fun seedRoot(run: PipelineRun) {
        runs[run.id] = run
    }

    /**
     * Flips the root run back to `RUNNING` before a resume, as the task queue does.
     *
     * @param runId The root run.
     */
    fun prepareResume(runId: String) {
        runs[runId] = requireNotNull(runs[runId]).copy(status = PipelineRunStatus.RUNNING)
    }

    /**
     * Status of [runId] as the store holds it.
     *
     * @param runId A run id.
     * @return The status, or `null` for an unknown run.
     */
    fun statusOf(runId: String): PipelineRunStatus? = runs[runId]?.status

    override suspend fun createRun(run: PipelineRun) {
        runs[run.id] = run
        log.record(
            "run.create ${run.id} pipeline=${run.pipelineId} parent=${run.parentRunId} status=${run.status} " +
                "origin=${run.origin} hadImage=${run.hadImage}",
            "userPrompt" to run.userPrompt.orEmpty(),
        )
    }

    override suspend fun markRunning(runId: String, pipelineId: String, graphContentHash: String) {
        runs[runId]?.let { runs[runId] = it.copy(status = PipelineRunStatus.RUNNING, pipelineId = pipelineId) }
        log.record("run.running $runId pipeline=$pipelineId graphHash=$graphContentHash")
    }

    override suspend fun updateStatus(runId: String, status: PipelineRunStatus) {
        runs[runId]?.let { runs[runId] = it.copy(status = status) }
        log.record("run.status $runId $status")
    }

    override suspend fun updateCurrentNode(runId: String, nodeId: String) {
        val position = tracker.enter(runId, nodeId)
        runs[runId]?.let { runs[runId] = it.copy(currentNodeId = nodeId) }
        log.record("visit $runId ${position.pipeline.id}/$nodeId ${position.node.type} #${position.visit}")
    }

    override suspend fun finishRun(
        runId: String,
        status: PipelineRunStatus,
        errorMessage: String?,
        reason: RunTerminationReason?,
    ) {
        runs[runId]?.let { runs[runId] = it.copy(status = status, errorMessage = errorMessage) }
        log.record("run.finish $runId $status reason=$reason", "error" to errorMessage.orEmpty())
    }

    override suspend fun recordSpend(rootRunId: String, stepsSpent: Int, tokensSpent: Int) {
        spend[rootRunId] = (spend[rootRunId] ?: RunSpend()).copy(steps = stepsSpent, tokens = tokensSpent)
        log.record("run.spend $rootRunId steps=$stepsSpent tokens=$tokensSpent")
    }

    override suspend fun getSpend(rootRunId: String): RunSpend = spend[rootRunId] ?: RunSpend()

    override suspend fun extendCeiling(rootRunId: String, axis: RunCeilingAxis) {
        val current = spend[rootRunId] ?: RunSpend()
        spend[rootRunId] = when (axis) {
            RunCeilingAxis.STEPS -> current.copy(stepCeilingExtensions = current.stepCeilingExtensions + 1)
            RunCeilingAxis.TOKENS -> current.copy(tokenCeilingExtensions = current.tokenCeilingExtensions + 1)
            RunCeilingAxis.MONEY -> error("No golden scenario grants a money ceiling")
        }
    }

    override suspend fun getRun(runId: String): PipelineRun? = runs[runId]

    override suspend fun getRootRunId(runId: String): String? {
        var run = runs[runId] ?: return null
        while (true) {
            run = runs[run.parentRunId ?: return run.id] ?: return run.id
        }
    }

    override suspend fun markResumed(runId: String, fromStatus: PipelineRunStatus): Boolean {
        val run = runs[runId]
        val applied = run != null && run.status == fromStatus
        if (applied) runs[runId] = requireNotNull(run).copy(status = PipelineRunStatus.QUEUED)
        log.record("run.resumed $runId from=$fromStatus applied=$applied")
        return applied
    }

    override suspend fun getDescendantRuns(rootRunId: String): List<PipelineRun> = unused("getDescendantRuns")

    override suspend fun countRootRunsByOriginSince(origin: RunOrigin, sinceEpochMs: Long): Int =
        unused("countRootRunsByOriginSince")

    override suspend fun getActiveRunForSession(sessionId: String): PipelineRun? = unused("getActiveRunForSession")

    override suspend fun getLatestRunForSession(sessionId: String): PipelineRun? = unused("getLatestRunForSession")

    override fun observeRunsForSession(sessionId: String): Flow<List<PipelineRun>> = unused("observeRunsForSession")

    override fun observeActiveRunSessionIds(): Flow<Set<String>> = unused("observeActiveRunSessionIds")

    override suspend fun discardInterruptedRun(runId: String) = unused("discardInterruptedRun")

    override suspend fun getOrphanedRuns(): List<PipelineRun> = unused("getOrphanedRuns")

    override suspend fun applyRetention(keepPerSession: Int, maxAgeCutoffEpochMs: Long): Int = unused("applyRetention")

    private fun unused(method: String): Nothing =
        error("PipelineRunRepository.$method is not on any golden run path; extend the harness deliberately")
}
