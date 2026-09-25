package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.PipelineRunDao
import app.knotwork.android.data.local.models.PipelineRunEntity
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunCeilingAxis
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSpend
import app.knotwork.android.domain.models.RunTerminationKind
import app.knotwork.android.domain.models.RunTerminationReason
import app.knotwork.android.domain.models.externalAutomationStatusForTerminal
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.services.ExternalAutomationCallbackNotifier
import app.knotwork.android.domain.services.RunOutcomeAnnouncer
import app.knotwork.android.domain.usecases.triggerRunOutcomeForTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [PipelineRunRepository].
 *
 * Maps between the domain `PipelineRun` and the persistence
 * [PipelineRunEntity] (enums stored as their `name` strings) and routes all
 * DAO calls through [Dispatchers.IO]. The terminal-status guard required by
 * the repository contract is implemented in SQL — every mutating DAO query
 * carries a `status NOT IN (terminal)` clause — so the guard holds even when
 * two writers race on different coroutines.
 *
 * **Best-effort contract.** Every method absorbs storage and mapping failures
 * (logged, neutral result) per the interface contract: run records must never
 * take down the execution they describe, nor brick app startup when the table
 * holds an unreadable row. `CancellationException` is always re-thrown.
 *
 * **Process ownership.** [liveRunIds] records every run id created by this
 * process (the class is a process-wide `@Singleton`). [getOrphanedRuns]
 * filters those ids out, which is what makes the startup orphan sweep safe to
 * run at any time: a run still executing in this process — kept alive by the
 * foreground service or a WorkManager worker while no Activity exists — can
 * never be mistaken for an orphan of a dead process.
 */
@Singleton
class PipelineRunRepositoryImpl @Inject constructor(
    private val pipelineRunDao: PipelineRunDao,
    private val usageTelemetry: UsageTelemetryRepository,
    private val triggerJournal: TriggerJournalRepository,
    private val externalAutomationJournal: ExternalAutomationJournalRepository,
    private val externalAutomationCallback: ExternalAutomationCallbackNotifier,
    private val runOutcomeAnnouncer: RunOutcomeAnnouncer,
) : PipelineRunRepository {

    /**
     * Ids of runs created by the current process. Membership means the run's
     * in-memory machinery (queue worker, suspension deferreds) is — or was —
     * hosted here, so the orphan sweep must not touch the record. The set
     * dies with the process, exactly matching the ownership semantics.
     */
    private val liveRunIds = ConcurrentHashMap.newKeySet<String>()

    override suspend fun createRun(run: PipelineRun) {
        // Register ownership before the insert: even if the write fails, the
        // id is process-owned and must be invisible to the orphan sweep.
        liveRunIds.add(run.id)
        absorbing("createRun") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.insertRun(run.toEntity())
            }
        }
    }

    override suspend fun markRunning(runId: String, pipelineId: String, graphContentHash: String) {
        absorbing("markRunning") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.markRunning(
                    runId = runId,
                    status = PipelineRunStatus.RUNNING.name,
                    pipelineId = pipelineId,
                    graphContentHash = graphContentHash,
                    terminalStatuses = TERMINAL_STATUS_NAMES,
                )
            }
        }
    }

    override suspend fun updateStatus(runId: String, status: PipelineRunStatus) {
        absorbing("updateStatus") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.updateStatus(
                    runId = runId,
                    status = status.name,
                    terminalStatuses = TERMINAL_STATUS_NAMES,
                )
            }
        }
    }

    override suspend fun updateCurrentNode(runId: String, nodeId: String) {
        absorbing("updateCurrentNode") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.updateCurrentNode(
                    runId = runId,
                    nodeId = nodeId,
                    terminalStatuses = TERMINAL_STATUS_NAMES,
                )
            }
        }
    }

    override suspend fun finishRun(
        runId: String,
        status: PipelineRunStatus,
        errorMessage: String?,
        reason: RunTerminationReason?,
    ) {
        // Caller contract violation, not a storage failure — never absorbed.
        require(status.isTerminal) { "finishRun requires a terminal status, got $status" }
        val rowsTransitioned = absorbing("finishRun") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.finishRun(
                    runId = runId,
                    status = status.name,
                    finishedAt = System.currentTimeMillis(),
                    errorMessage = errorMessage,
                    terminationReason = reason?.kind?.name,
                    terminalStatuses = TERMINAL_STATUS_NAMES,
                )
            }
        } ?: 0
        // Only a genuine terminal transition feeds the observers: a duplicate /
        // racing finishRun on an already-terminal run is a DB no-op (0 rows) and
        // must neither double-count the run in the usage statistics nor overwrite
        // an outcome already attributed to its trigger-journal row.
        if (rowsTransitioned > 0) {
            recordRunTelemetry(runId, status)
            recordOriginBoundOutcome(runId, status, errorMessage, reason)
            announceOutcomeInChat(runId, status, errorMessage, reason)
        }
    }

    override suspend fun recordSpend(rootRunId: String, stepsSpent: Int, tokensSpent: Int) {
        absorbing("recordSpend") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.recordSpend(
                    rootRunId = rootRunId,
                    stepsSpent = stepsSpent,
                    tokensSpent = tokensSpent,
                    terminalStatuses = TERMINAL_STATUS_NAMES,
                )
            }
        }
    }

    override suspend fun getSpend(rootRunId: String): RunSpend {
        val projection = absorbing("getSpend") {
            withContext(Dispatchers.IO) { pipelineRunDao.getSpend(rootRunId) }
        }
        return RunSpend(
            steps = projection?.stepsSpent ?: 0,
            tokens = projection?.tokensSpent ?: 0,
            stepCeilingExtensions = projection?.stepCeilingExtensions ?: 0,
            tokenCeilingExtensions = projection?.tokenCeilingExtensions ?: 0,
        )
    }

    override suspend fun extendCeiling(rootRunId: String, axis: RunCeilingAxis) {
        absorbing("extendCeiling") {
            withContext(Dispatchers.IO) {
                when (axis) {
                    RunCeilingAxis.STEPS -> pipelineRunDao.extendStepCeiling(rootRunId)
                    RunCeilingAxis.TOKENS -> pipelineRunDao.extendTokenCeiling(rootRunId)
                    // Unmeasured in this release, so there is no column to raise
                    // and nothing that could have breached on it. Listed rather
                    // than defaulted: promoting the money axis must fail to
                    // compile here, not silently discard the user's answer.
                    RunCeilingAxis.MONEY -> Unit
                }
            }
        }
    }

    /**
     * Leaves a line in the run's chat when it ended without an answer.
     *
     * Inside the `rowsTransitioned > 0` guard with the other two terminal
     * side-effects, and for the same reason: a duplicate or racing `finishRun`
     * on an already-terminal run is a DB no-op, and an announcement outside the
     * guard would put a second identical line in the conversation. That race is
     * not hypothetical — `ParkedRunResumer.failPark` is reachable both from the
     * user's response and from the expiry pass.
     *
     * Nested sub-pipeline children are skipped: a child shares its parent's
     * session, and its failure already reaches the user as the root's.
     *
     * @param runId The run that just transitioned.
     * @param status Terminal status it settled at.
     * @param errorMessage Diagnostic written to the record.
     * @param reason Typed cause, when the engine classified the stop.
     */
    private suspend fun announceOutcomeInChat(
        runId: String,
        status: PipelineRunStatus,
        errorMessage: String?,
        reason: RunTerminationReason?,
    ) {
        val identity = absorbing("getRunChatIdentity") {
            withContext(Dispatchers.IO) { pipelineRunDao.getRunChatIdentity(runId) }
        } ?: return
        if (identity.parentRunId != null) return
        runOutcomeAnnouncer.announce(identity.sessionId, status, reason, errorMessage)
    }

    /**
     * Attributes this run's terminal fate back onto whichever per-origin record
     * opened for it — the second phase of the two-phase entry a firing trigger or
     * an admitted external request opened.
     *
     * Dispatched on origin, read through a single-column projection rather than a
     * full run load. The mapping from run status to each surface's outcome
     * vocabulary lives in a pure mapper ([triggerRunOutcomeForTerminal],
     * [externalAutomationStatusForTerminal]) — in the trigger's case keeping a
     * platform kill distinct from a deliberate stop and from a genuine failure.
     *
     * Best-effort throughout: the origin read is absorbed and each store absorbs
     * its own storage failures, so this can never disturb the run it observes.
     */
    private suspend fun recordOriginBoundOutcome(
        runId: String,
        status: PipelineRunStatus,
        errorMessage: String?,
        reason: RunTerminationReason?,
    ) {
        val originName = absorbing("getRunOrigin") {
            withContext(Dispatchers.IO) { pipelineRunDao.getRunOrigin(runId) }
        }
        // Parsed before branching, so the `when` below is exhaustive over the enum
        // rather than over strings. The string form compiled happily with an
        // `else -> Unit`, which meant a newly added origin would have slipped
        // through here silently — the one place in the app where "this origin
        // keeps a record a terminal transition must settle" is decided.
        // An unparseable value is a row this build does not understand; absorbed,
        // like every other read on this path.
        val origin = originName?.let { name -> RunOrigin.entries.firstOrNull { it.name == name } }
        // One projection, then one branch. Two surfaces now keep a per-run record
        // that a terminal transition has to settle, and reading the origin once
        // keeps the hot completion path at a single extra query however many
        // surfaces are added later. Every other origin — interactive chat, the
        // scheduler, the tile, a share, a nested sub-pipeline child — owns no
        // record and does no work here.
        when (origin) {
            RunOrigin.TRIGGER ->
                triggerJournal.recordRunOutcome(
                    runId,
                    triggerRunOutcomeForTerminal(status, errorMessage, reason),
                )
            RunOrigin.EXTERNAL -> recordExternalAutomationOutcome(runId, status)
            RunOrigin.CHAT, RunOrigin.SCHEDULER, RunOrigin.SHARE, RunOrigin.QUICK_TILE, null -> Unit
        }
    }

    /**
     * Settles an external request's journal row and tells the caller how its run
     * ended.
     *
     * **This is the only seam that sees every terminal transition of an external
     * run.** The obvious alternative — announcing from `AgentWorker`, where the app
     * already reports scheduled-task outcomes — cannot work here: a
     * human-in-the-loop gate that outlives its live waiting phase parks the run in a
     * *non-terminal* status, the worker is stopped, and the answer arrives hours
     * later through `ParkedRunResumer` and the in-process task queue, with no worker
     * left to announce anything. A background approval window is measured in hours,
     * so for an external request carrying a gated tool that is the normal path, not
     * an edge case.
     *
     * **Root-only for free.** A nested sub-pipeline child inherits its parent's
     * origin but owns no journal row, so the lookup finds nothing and the callback
     * is not sent twice.
     *
     * **The first terminal outcome is the one that stands**, in the row and in the
     * callback alike. A run that settles, is resumed and settles again keeps its
     * first reported fate: the caller has already acted on it, so a correction it
     * cannot undo is worth less than a record that matches what it was told. The
     * residual is deliberate and narrow — a manually resumed external run shows its
     * first outcome in the journal rather than its last.
     *
     * Best-effort throughout: the journal absorbs its own storage failures and the
     * notifier absorbs delivery failures, so neither can disturb the run they
     * observe. A request that asked for no callback still gets its row settled —
     * the journal is for the user, the callback is for the caller.
     *
     * **A protective stop is reported here as a plain `Failed`, on purpose.** The
     * external status vocabulary is published to third parties and frozen, and its
     * one free-form column already carries the *refusal-reason* vocabulary for
     * `Rejected` / `Blocked` rows. Writing a run-termination kind into that same
     * column would put two vocabularies in one field, which is a defect waiting for
     * the first reader that consults it for a `Failed` row. A caller that needs to
     * know a run hit a ceiling learns it the same way it learns anything else about
     * the run: not from the acknowledgement, which by design carries no run content.
     * The typed reason lives on the run record and in the run's console.
     */
    private suspend fun recordExternalAutomationOutcome(runId: String, status: PipelineRunStatus) {
        val entry = externalAutomationJournal.findByRunId(runId) ?: return
        val outcome = externalAutomationStatusForTerminal(status)
        // The settlement decides whether to notify. A run can reach a terminal
        // status twice — `INTERRUPTED` is terminal *and* resumable, so a run killed
        // by the platform settles, is resumed from the chat, and settles again — and
        // the second settlement is refused. Notifying anyway would send the caller
        // `Failed` and then `Completed` for one request, which is the contradiction
        // this guard exists to prevent.
        if (!externalAutomationJournal.recordOutcome(runId, outcome)) return
        val returnPackage = entry.declaredReturnPackage ?: return
        externalAutomationCallback.notifyOutcome(
            returnPackage = returnPackage,
            returnAction = entry.returnAction,
            requestId = entry.requestId,
            status = outcome,
        )
    }

    /**
     * Records the terminal outcome of a **root**, pipeline-bound run into the
     * privacy-preserving local usage statistics, best-effort.
     *
     * Filters applied so the statistics reflect genuine pipeline usage:
     * - **Opt-in gate first.** Checked before the run read-back, so an opted-out
     *   user pays nothing here (no `getRun`). The telemetry repository re-checks
     *   the flag, but that inner gate is for the trigger path; this outer check
     *   is purely the cost optimisation, not a redundant guard.
     * - **Root runs only** (`parentRunId == null`): a nested sub-pipeline run is
     *   an implementation detail of one user-initiated run and must not inflate
     *   the tally.
     * - **Pipeline-bound only** (`pipelineId != null`): a run that never resolved
     *   a pipeline never executed any pipeline work — e.g. a queued run reaped as
     *   `INTERRUPTED` by the startup orphan sweep, or cancelled before it started.
     *   Counting these would pollute the outcome shares with process-death noise.
     *
     * The same chokepoint also closes the onboarding "first value" marker on a
     * `COMPLETED` run — reusing this filter set rather than adding a second
     * observation point, so the two figures can never disagree about what counts
     * as a run.
     *
     * The telemetry repository absorbs its own storage failures, so this can
     * never disturb the run it describes.
     */
    private suspend fun recordRunTelemetry(runId: String, status: PipelineRunStatus) {
        if (!usageTelemetry.isEnabled()) return
        val run = getRun(runId) ?: return
        if (run.parentRunId != null) return
        val pipelineId = run.pipelineId ?: return
        val atMillis = System.currentTimeMillis()
        usageTelemetry.recordPipelineRunOutcome(pipelineId, status, atMillis)
        // A completed root run is the earliest point the app can honestly call
        // "first value" (VISION §7.2). The repository decides whether *this* run
        // ends the measured onboarding journey; the same filters as above apply,
        // so a nested or pipeline-less run can never close the metric.
        if (status == PipelineRunStatus.COMPLETED) {
            usageTelemetry.recordOnboardingFirstValue(pipelineId, atMillis)
        }
    }

    override suspend fun getRun(runId: String): PipelineRun? = absorbing("getRun") {
        withContext(Dispatchers.IO) {
            pipelineRunDao.getRun(runId)?.toDomain()
        }
    }

    override suspend fun countRootRunsByOriginSince(origin: RunOrigin, sinceEpochMs: Long): Int =
        absorbing("countRootRunsByOriginSince") {
            withContext(Dispatchers.IO) { pipelineRunDao.countRootRunsByOriginSince(origin.name, sinceEpochMs) }
        } ?: 0

    override suspend fun getDescendantRuns(rootRunId: String): List<PipelineRun> = absorbing("getDescendantRuns") {
        withContext(Dispatchers.IO) {
            // Breadth-first walk over the parentRunId links. The depth ceiling
            // bounds the tree, and `visited` guards against a pathological
            // self-referential row so the loop always terminates.
            val collected = mutableListOf<PipelineRun>()
            val visited = mutableSetOf(rootRunId)
            val frontier = ArrayDeque(listOf(rootRunId))
            while (frontier.isNotEmpty()) {
                val parent = frontier.removeFirst()
                pipelineRunDao.getChildRuns(parent).forEach { child ->
                    if (visited.add(child.id)) {
                        collected += child.toDomain()
                        frontier.addLast(child.id)
                    }
                }
            }
            collected
        }
    } ?: emptyList()

    override suspend fun getRootRunId(runId: String): String? = absorbing("getRootRunId") {
        withContext(Dispatchers.IO) {
            // Walk the parentRunId chain to the top. `visited` guards against a
            // pathological self-referential / cyclic chain so the loop always
            // terminates; nesting is shallow in practice (depth ceiling).
            var current = pipelineRunDao.getRun(runId) ?: return@withContext null
            val visited = mutableSetOf(current.id)
            while (true) {
                val parentId = current.parentRunId ?: break
                val parent = pipelineRunDao.getRun(parentId) ?: break
                if (!visited.add(parent.id)) break
                current = parent
            }
            current.id
        }
    }

    override suspend fun markResumed(runId: String, fromStatus: PipelineRunStatus): Boolean {
        // Re-register ownership before the transition for the same reason
        // createRun does: from this moment the run's machinery lives in this
        // process, and a failed write must still keep the id invisible to
        // the orphan sweep.
        liveRunIds.add(runId)
        return absorbing("markResumed") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.markResumed(
                    runId = runId,
                    fromStatus = fromStatus.name,
                    toStatus = PipelineRunStatus.QUEUED.name,
                ) == 1
            }
        } ?: false
    }

    override suspend fun getActiveRunForSession(sessionId: String): PipelineRun? = absorbing("getActiveRunForSession") {
        withContext(Dispatchers.IO) {
            pipelineRunDao.getActiveRunForSession(sessionId, ACTIVE_STATUS_NAMES)?.toDomain()
        }
    }

    override suspend fun getLatestRunForSession(sessionId: String): PipelineRun? = absorbing("getLatestRunForSession") {
        withContext(Dispatchers.IO) {
            pipelineRunDao.getLatestRunForSession(sessionId)?.toDomain()
        }
    }

    override fun observeRunsForSession(sessionId: String): Flow<List<PipelineRun>> =
        pipelineRunDao.observeRunsForSession(sessionId)
            .map { entities -> entities.map { it.toDomain() } }
            .catch { e ->
                Timber.e(e, "Pipeline-run store failure in observeRunsForSession; degrading to empty")
                emit(emptyList())
            }

    override fun observeActiveRunSessionIds(): Flow<Set<String>> =
        pipelineRunDao.observeSessionIdsByStatuses(ACTIVE_STATUS_NAMES)
            .map { it.toSet() }
            // Room re-runs the query on every table write (per-node progress
            // included); the set only changes when a run starts or settles,
            // so deduplicate here instead of in every consumer.
            .distinctUntilChanged()
            .catch { e ->
                Timber.e(e, "Pipeline-run store failure in observeActiveRunSessionIds; degrading to empty")
                emit(emptySet())
            }

    override suspend fun discardInterruptedRun(runId: String) {
        absorbing("discardInterruptedRun") {
            withContext(Dispatchers.IO) {
                pipelineRunDao.discardInterruptedRun(
                    runId = runId,
                    fromStatus = PipelineRunStatus.INTERRUPTED.name,
                    toStatus = PipelineRunStatus.FAILED.name,
                    errorMessage = DISCARDED_BY_USER_MESSAGE,
                    terminationReason = RunTerminationKind.DISCARDED_BY_USER.name,
                )
            }
        }
    }

    override suspend fun getOrphanedRuns(): List<PipelineRun> = absorbing("getOrphanedRuns") {
        withContext(Dispatchers.IO) {
            pipelineRunDao.getRunsByStatuses(ACTIVE_STATUS_NAMES)
                .filter { it.id !in liveRunIds }
                .map { it.toDomain() }
        }
    } ?: emptyList()

    override suspend fun applyRetention(keepPerSession: Int, maxAgeCutoffEpochMs: Long): Int =
        absorbing("applyRetention") {
            withContext(Dispatchers.IO) {
                val beyondLimit = pipelineRunDao.deleteTerminalRunsBeyondSessionLimit(
                    keepPerSession = keepPerSession,
                    terminalStatuses = TERMINAL_STATUS_NAMES,
                )
                val tooOld = pipelineRunDao.deleteTerminalRunsFinishedBefore(
                    cutoff = maxAgeCutoffEpochMs,
                    terminalStatuses = TERMINAL_STATUS_NAMES,
                )
                beyondLimit + tooOld
            }
        } ?: 0

    /**
     * Runs [block] under the best-effort contract via the shared
     * [absorbingStoreFailure] helper, branding the log line with the
     * pipeline-run store prefix.
     *
     * @param operation Name used in the failure log line.
     * @param block The storage operation to attempt.
     * @return The block's result, or `null` when the store failed.
     */
    private suspend fun <T> absorbing(operation: String, block: suspend () -> T): T? =
        absorbingStoreFailure({ "Pipeline-run store failure in $operation; continuing without it" }, block)

    private companion object {
        /** Terminal status names used as the SQL `NOT IN` overwrite guard. */
        val TERMINAL_STATUS_NAMES: List<String> =
            PipelineRunStatus.entries.filter { it.isTerminal }.map { it.name }

        /** Non-terminal status names: active-run lookup and orphan-sweep scope. */
        val ACTIVE_STATUS_NAMES: List<String> =
            PipelineRunStatus.entries.filterNot { it.isTerminal }.map { it.name }

        /** Error message stamped on a run the user explicitly discarded instead of resuming. */
        const val DISCARDED_BY_USER_MESSAGE: String = "Discarded by user"
    }
}

/**
 * Maps the domain run to its persistence entity, storing enums as `name` strings.
 *
 * @return The entity ready for insertion.
 */
private fun PipelineRun.toEntity(): PipelineRunEntity = PipelineRunEntity(
    id = id,
    sessionId = sessionId,
    pipelineId = pipelineId,
    origin = origin.name,
    status = status.name,
    currentNodeId = currentNodeId,
    startedAt = startedAt,
    finishedAt = finishedAt,
    errorMessage = errorMessage,
    graphContentHash = graphContentHash,
    userPrompt = userPrompt,
    parentRunId = parentRunId,
    hadImage = hadImage,
    stepsSpent = stepsSpent,
    tokensSpent = tokensSpent,
    terminationReason = terminationReason?.name,
)

/**
 * Maps the persistence entity back to the domain run. [origin] and [status] are
 * parsed strictly ([IllegalArgumentException] on unknown names) — an unreadable
 * row is data corruption; the repository's best-effort wrapper turns it into a
 * logged degraded read instead of a crash.
 *
 * `terminationReason` is the deliberate exception, parsed permissively: an
 * absent or unrecognised cause means "this build cannot say why the run
 * stopped", which is an ordinary state (every row written before the column
 * existed is in it) rather than corruption. Failing the whole row over it would
 * hide the run instead of the one field.
 *
 * @return The domain model.
 */
private fun PipelineRunEntity.toDomain(): PipelineRun = PipelineRun(
    id = id,
    sessionId = sessionId,
    pipelineId = pipelineId,
    origin = RunOrigin.valueOf(origin),
    status = PipelineRunStatus.valueOf(status),
    currentNodeId = currentNodeId,
    startedAt = startedAt,
    finishedAt = finishedAt,
    errorMessage = errorMessage,
    graphContentHash = graphContentHash,
    userPrompt = userPrompt,
    parentRunId = parentRunId,
    hadImage = hadImage,
    stepsSpent = stepsSpent,
    tokensSpent = tokensSpent,
    terminationReason = terminationReason?.let { name ->
        RunTerminationKind.entries.firstOrNull { it.name == name }
    },
)
