package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MemorySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Produces a cheap, LLM-free estimate of what a [MemoryCompactionUseCase] pass
 * would do, so the "Compact memory?" confirmation dialog can preview the cost
 * before the user commits.
 *
 * The estimate mirrors the real pass's gating without running it: it loads the
 * same non-pinned-older-than-age candidate set, derives the same cluster count
 * `k = max(1, floor(sqrt(N) / 2))` the [app.knotwork.android.domain.services.KMeansClusterer]
 * would pick, and assumes each cluster collapses to a single summary. The
 * figures are therefore approximate (hence the "~" the UI renders) — actual
 * removal depends on per-cluster size floors and best-effort skips.
 *
 * The estimate is deliberately an **upper bound**: the real pass deletes only
 * the facts each summary is verified to cover
 * ([app.knotwork.android.domain.services.CompactionCoverageVerifier]), so
 * clusters whose summary comes out unfaithful remove fewer chunks than
 * predicted — or none. Over-promising removal and under-delivering is the safe
 * direction for a preview of a destructive action; the reverse would be a
 * surprise.
 *
 * @property memoryRepository Candidate loading.
 * @property memorySettings Source of the compaction age window.
 */
class EstimateCompactionUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memorySettings: MemorySettings,
) {
    /**
     * Computes a compaction estimate as of [nowMillis].
     *
     * @param nowMillis Wall-clock "now" used to compute the age cutoff;
     *   defaults to the system clock, overridable in tests.
     * @return The estimated removed-chunk count, freed bytes, and runtime.
     */
    suspend operator fun invoke(nowMillis: Long = System.currentTimeMillis()): CompactionEstimate =
        withContext(Dispatchers.Default) {
            val ageDays = memorySettings.memoryCompactionAgeDays.first()
            val cutoff = nowMillis - ageDays.toLong() * TimeAndIdConstants.MS_PER_DAY
            val candidates = memoryRepository.getCompactionCandidates(cutoff)
            val n = candidates.size
            if (n < MIN_CHUNKS_TO_COMPACT) return@withContext CompactionEstimate.EMPTY

            // Same cluster-count heuristic the real clusterer uses.
            val clusters = maxOf(1, floor(sqrt(n.toDouble()) / 2.0).toInt())
            // Each cluster collapses to one summary, so removed ≈ N − clusters.
            val estimatedRemoved = (n - clusters).coerceAtLeast(0)
            val avgBytes = candidates.averageBytes()
            val estimatedFreedBytes = (estimatedRemoved.toLong() * avgBytes)
            val estimatedRuntimeSeconds = (clusters * SECONDS_PER_CLUSTER).coerceAtLeast(1)

            CompactionEstimate(
                estimatedRemoved = estimatedRemoved,
                estimatedFreedBytes = estimatedFreedBytes,
                estimatedRuntimeSeconds = estimatedRuntimeSeconds,
            )
        }

    /** Mean on-disk byte cost of a chunk (text + comma-encoded embedding). */
    private fun List<MemoryChunk>.averageBytes(): Long {
        if (isEmpty()) return 0L
        val total = sumOf { chunk ->
            chunk.text.length.toLong() + chunk.embedding.size.toLong() * CHARS_PER_FLOAT
        }
        return total / size
    }

    private companion object {
        /** Below this candidate count the real pass does nothing, so neither does the estimate. */
        const val MIN_CHUNKS_TO_COMPACT = 3

        /** Rough wall-clock budget per consolidated cluster (one local-model call). */
        const val SECONDS_PER_CLUSTER = 2

        /** Approximate characters a single float occupies in the comma-encoded embedding column. */
        const val CHARS_PER_FLOAT = 9L
    }
}

/**
 * Preview of a prospective compaction pass.
 *
 * @property estimatedRemoved Approximate number of chunks that would be removed.
 * @property estimatedFreedBytes Approximate on-disk bytes that would be freed.
 * @property estimatedRuntimeSeconds Rough wall-clock runtime in seconds.
 */
data class CompactionEstimate(
    val estimatedRemoved: Int,
    val estimatedFreedBytes: Long,
    val estimatedRuntimeSeconds: Int,
) {
    /** Holds the [EMPTY] sentinel used when nothing is eligible for compaction. */
    companion object {
        /** Zero estimate — nothing eligible to compact. */
        val EMPTY = CompactionEstimate(estimatedRemoved = 0, estimatedFreedBytes = 0L, estimatedRuntimeSeconds = 0)
    }
}
