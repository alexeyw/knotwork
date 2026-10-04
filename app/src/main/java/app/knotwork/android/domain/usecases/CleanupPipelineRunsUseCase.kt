package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.PrivacySettings
import app.knotwork.android.domain.repositories.RunTraceRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Applies the run-history retention policy: one bounded cleanup pass over
 * persisted pipeline runs and their traces.
 *
 * Two user-tunable limits (Settings → Privacy) bound how much derived
 * user content the run store accumulates at rest:
 *  - **Per-session count** (`traceRetentionRunsPerSession`) — each chat
 *    session keeps only its N most recently started runs; older terminal
 *    runs are deleted.
 *  - **Max age** (`traceRetentionMaxAgeDays`) — terminal runs whose terminal
 *    transition is older than the window are deleted regardless of count.
 *
 * Only **terminal** runs (`COMPLETED` / `FAILED` / `CANCELLED` /
 * `INTERRUPTED`) are ever deleted. Runs parked on a background approval or
 * clarification (`WAITING_*`) are non-terminal and therefore untouchable by
 * retention for as long as they wait — their expiry is owned by the
 * pending-interaction maintenance pass, which settles them to `FAILED`
 * first; only then do they become retention candidates. Each deleted run's
 * persisted trace rows are removed atomically by the storage-level cascade.
 *
 * The pass also deletes legacy trace rows that predate run-trace persistence
 * (no run attribution, hence unreachable from any replay path) once they age
 * past the same max-age window.
 *
 * Both settings are read fresh on every pass, so a user edit applies to the
 * next maintenance run without rescheduling. Storage failures are absorbed
 * by the repositories per their best-effort contract — a failed pass reports
 * zero deletions and the next scheduled pass simply tries again.
 *
 * @property pipelineRunRepository Store the terminal runs are deleted from.
 * @property runTraceRepository Store the legacy (pre-run) trace rows are
 *   deleted from.
 * @property privacySettings Source of the two retention limits.
 */
class CleanupPipelineRunsUseCase @Inject constructor(
    private val pipelineRunRepository: PipelineRunRepository,
    private val runTraceRepository: RunTraceRepository,
    private val privacySettings: PrivacySettings,
) {

    /**
     * Runs one retention pass with the currently configured limits.
     *
     * @return The outcome counters, all zero when nothing qualified for
     *   deletion (or the store failed and the repositories absorbed it).
     */
    suspend operator fun invoke(): Outcome {
        val keepPerSession = privacySettings.traceRetentionRunsPerSession.first()
        val maxAgeDays = privacySettings.traceRetentionMaxAgeDays.first()
        val cutoff = System.currentTimeMillis() - maxAgeDays * MILLIS_PER_DAY
        val deletedRuns = pipelineRunRepository.applyRetention(
            keepPerSession = keepPerSession,
            maxAgeCutoffEpochMs = cutoff,
        )
        val deletedLegacyTraceRows = runTraceRepository.deleteLegacyTraceBefore(cutoff)
        return Outcome(deletedRuns = deletedRuns, deletedLegacyTraceRows = deletedLegacyTraceRows)
    }

    /**
     * Counters of one retention pass, surfaced for logging by the caller.
     *
     * @property deletedRuns Terminal runs deleted (their run-scoped trace
     *   rows went with them via the storage cascade).
     * @property deletedLegacyTraceRows Legacy pre-run trace rows deleted.
     */
    data class Outcome(val deletedRuns: Int, val deletedLegacyTraceRows: Int)

    private companion object {
        /** Milliseconds in one day, for the max-age cutoff arithmetic. */
        const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1_000L
    }
}
