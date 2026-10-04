package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MemorySettings
import app.knotwork.android.domain.services.CompactionCoverageVerifier
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.KMeansClusterer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Consolidates stale, redundant long-term memory chunks into denser summaries
 * so the `memory_chunks` table does not balloon with near-duplicate facts as
 * the agent is used over weeks.
 *
 * Lifecycle of one compaction pass:
 *  1. Load the non-pinned chunks older than `memoryCompactionAgeDays`
 *     ([MemoryRepository.getCompactionCandidates]). Pinned and fresh chunks are
 *     never candidates, so they are left untouched.
 *  2. If fewer than [MIN_CHUNKS_TO_COMPACT] candidates exist, do nothing — too
 *     little to gain from clustering.
 *  3. Ensure the active local model is loaded (it usually already is); if it
 *     cannot be loaded, skip the pass rather than fail the worker.
 *  4. Cluster the candidates by embedding similarity ([KMeansClusterer]).
 *  5. For every cluster with at least [MIN_CLUSTER_SIZE] members, run the
 *     consolidation prompt ([DefaultPrompts.MemoryCompaction.SYSTEM_FALLBACK])
 *     once to fold the members into a single fact and embed that summary with
 *     the **active** provider.
 *  6. **Verify before deleting anything.** [CompactionCoverageVerifier] reports
 *     which members the summary actually represents. Only those are replaced —
 *     atomically, via [MemoryRepository.replaceWithConsolidated], which writes
 *     the summary tagged [MemorySource.Compaction] (carrying exactly the merged
 *     ids) and removes the originals in one transaction. Members the summary
 *     failed to cover stay stored verbatim, and a cluster whose summary covers
 *     fewer than [MIN_COVERED_TO_CONSOLIDATE] members is rejected outright —
 *     saving it would add a chunk without removing enough, i.e. grow the table
 *     while diluting it.
 *
 * **Why the gate exists.** A 4B on-device model asked to merge several facts
 * can quietly drop one of them, and the originals were the only copy. The
 * verification step is what keeps "compaction" from meaning "the model's word
 * against data the user can never get back"; the residual risk it cannot see —
 * a detail distorted inside an otherwise faithful sentence — is contained on
 * the read side, where
 * [app.knotwork.android.domain.services.MemoryReranker] never lets a summary
 * displace a verbatim chunk that restates it.
 *
 * The pass is best-effort and resilient: a blank model reply, an embedding
 * failure, a failed verification, or a save error on one cluster skips **only
 * that cluster** (its originals are kept), and the use case never throws except
 * to propagate [CancellationException]. This mirrors [MemoryExtractionUseCase]
 * so background maintenance can never corrupt or lose memory.
 *
 * The feature toggle (`memoryCompactionEnabled`) is intentionally **not** read
 * here — the worker that schedules this use case owns that gate, leaving the
 * use case directly callable by the out-of-schedule hard-limit trigger.
 *
 * @property llmInferenceEngine Local model used to run the consolidation prompt.
 * @property loadModelUseCase Ensures the active model is loaded before inference.
 * @property promptTemplateEngine Substitutes runtime `$VARIABLE`s (here `$DATE`).
 * @property promptVariableProviders Registered providers backing the templating.
 * @property embeddingProviderResolver Resolves the active embedding backend per call.
 * @property memoryRepository Candidate loading, persistence, and deletion.
 * @property memorySettings Source of the compaction age window.
 * @property kMeansClusterer Groups candidate chunks by embedding similarity.
 * @property coverageVerifier Decides which cluster members a generated summary
 *   is allowed to replace.
 */
class MemoryCompactionUseCase @Inject constructor(
    private val llmInferenceEngine: LlmInferenceEngine,
    private val loadModelUseCase: LoadModelUseCase,
    private val promptTemplateEngine: PromptTemplateEngine,
    private val promptVariableProviders: Set<@JvmSuppressWildcards PromptVariableProvider>,
    private val embeddingProviderResolver: EmbeddingProviderResolver,
    private val memoryRepository: MemoryRepository,
    private val memorySettings: MemorySettings,
    private val kMeansClusterer: KMeansClusterer,
    private val coverageVerifier: CompactionCoverageVerifier,
) {

    /**
     * Runs one compaction pass and returns what it changed.
     *
     * @param nowMillis Wall-clock "now" used to compute the age cutoff. Defaults
     *   to the system clock; tests pass a fixed value for determinism.
     * @return Counters describing the pass (zeroed when nothing was eligible).
     */
    suspend operator fun invoke(nowMillis: Long = System.currentTimeMillis()): MemoryCompactionOutcome =
        withContext(Dispatchers.Default) {
            val ageDays = memorySettings.memoryCompactionAgeDays.first()
            val cutoff = nowMillis - ageDays.toLong() * TimeAndIdConstants.MS_PER_DAY
            val verboseLogging = memorySettings.verboseMemoryLoggingEnabled.first()

            val candidates = memoryRepository.getCompactionCandidates(cutoff)
            if (candidates.size < MIN_CHUNKS_TO_COMPACT) {
                return@withContext MemoryCompactionOutcome.EMPTY
            }

            // The completing/daily worker usually leaves the model warm; if it
            // cannot be loaded we skip rather than fail the background pass.
            if (loadModelUseCase() is Result.Error) {
                Timber.tag(TAG).w("Active model unavailable; skipping memory compaction")
                return@withContext MemoryCompactionOutcome.EMPTY
            }

            val clusters = kMeansClusterer.cluster(candidates.map { it.embedding })
            val systemPrompt = promptTemplateEngine.render(
                DefaultPrompts.MemoryCompaction.SYSTEM_FALLBACK,
                promptVariableProviders.toList(),
            )

            var clustersProcessed = 0
            var clustersRejected = 0
            var chunksConsolidated = 0
            var chunksCreated = 0
            var chunksKeptUnverified = 0

            for (cluster in clusters) {
                if (cluster.size < MIN_CLUSTER_SIZE) continue
                val members = cluster.map { candidates[it] }
                val consolidation = consolidateCluster(systemPrompt, members, verboseLogging)
                if (consolidation == null) {
                    clustersRejected++
                } else {
                    clustersProcessed++
                    chunksConsolidated += consolidation.mergedCount
                    chunksCreated++
                    chunksKeptUnverified += consolidation.keptCount
                }
            }

            // Stamp the last-compacted time only when a cluster was actually
            // consolidated — a pass where every candidate fell below the size
            // floor changed nothing, so labelling it "compacted just now" would
            // mislead the user (and could throttle a later genuine pass).
            if (clustersProcessed > 0) {
                memorySettings.setMemoryLastCompactedAt(nowMillis)
            }

            MemoryCompactionOutcome(
                clustersProcessed = clustersProcessed,
                chunksConsolidated = chunksConsolidated,
                chunksCreated = chunksCreated,
                clustersRejected = clustersRejected,
                chunksKeptUnverified = chunksKeptUnverified,
            )
        }

    /**
     * Consolidates a single cluster: runs the summary prompt, embeds the result,
     * verifies which members the summary really covers, and atomically replaces
     * exactly those with a [MemorySource.Compaction] chunk.
     *
     * Rejects the cluster (returns `null`, keeping **every** original) on a
     * blank model reply, an embedding or persistence failure, or a summary that
     * covers fewer than [MIN_COVERED_TO_CONSOLIDATE] members — in the last case
     * the summary is not saved at all, because a chunk that replaces one or zero
     * facts grows the table instead of compacting it.
     *
     * @param systemPrompt Pre-rendered consolidation system prompt.
     * @param members The chunks in this cluster (size already validated ≥ floor).
     * @param verboseLogging When `true`, logs the cluster membership (the merged
     *   chunk ids, and any member the summary failed to cover) of every
     *   successful consolidation for observability. Gated on
     *   `MemorySettings.verboseMemoryLoggingEnabled` so the logcat stays quiet
     *   by default.
     * @return What the consolidation changed, or `null` if the cluster was left
     *   untouched.
     */
    private suspend fun consolidateCluster(
        systemPrompt: String,
        members: List<MemoryChunk>,
        verboseLogging: Boolean,
    ): ClusterConsolidation? {
        val summary = runInference(systemPrompt, members).trim()
        if (summary.isEmpty()) return null

        return try {
            val embedding = embeddingProviderResolver.resolve().embed(summary)
            val verdict = coverageVerifier.verify(members, embedding)
            if (verdict.covered.size < MIN_COVERED_TO_CONSOLIDATE) {
                Timber.tag(TAG).w(
                    "Consolidation summary covered only %d of %d facts; discarding it and keeping the originals",
                    verdict.covered.size,
                    members.size,
                )
                return null
            }

            val mergedIds = verdict.covered.map { it.id }
            memoryRepository.replaceWithConsolidated(
                text = summary,
                embedding = embedding,
                originalIds = mergedIds,
            )
            if (verboseLogging) {
                Timber.tag(TAG).d(
                    "Compaction cluster: merged ids %s → 1 summary; kept unverified ids %s",
                    mergedIds,
                    verdict.uncovered.map { it.id },
                )
            }
            ClusterConsolidation(mergedCount = mergedIds.size, keptCount = verdict.uncovered.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to consolidate a memory cluster; keeping its originals")
            null
        }
    }

    /**
     * What one successful cluster consolidation changed.
     *
     * @property mergedCount Members the summary covered, and which were
     *   therefore deleted.
     * @property keptCount Members the summary failed to cover, left stored
     *   verbatim.
     */
    private data class ClusterConsolidation(val mergedCount: Int, val keptCount: Int)

    /**
     * Builds the consolidation prompt from the rendered system prompt and the
     * cluster's facts, runs it once through the local model, and joins the
     * streamed tokens. Never throws for an inference error — returns an empty
     * string so the caller skips the cluster.
     *
     * @param systemPrompt Pre-rendered consolidation system prompt.
     * @param members The chunks whose texts are folded into one fact.
     * @return The model's raw reply (expected to be a single plain-text fact).
     */
    private suspend fun runInference(systemPrompt: String, members: List<MemoryChunk>): String {
        val factsBlock = members.joinToString(separator = "\n") { "- ${it.text}" }
        val fullPrompt = "$systemPrompt\n\nFACTS:\n$factsBlock\n\nCONSOLIDATED FACT: "

        return try {
            llmInferenceEngine.generateResponseStream(fullPrompt).toList().joinToString(separator = "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Memory compaction inference failed")
            ""
        }
    }

    private companion object {
        const val TAG = "MemoryCompaction"

        /**
         * Minimum number of candidate chunks for a pass to be worthwhile. Below
         * this there is nothing meaningful to cluster.
         */
        const val MIN_CHUNKS_TO_COMPACT = 3

        /**
         * Minimum cluster size eligible for consolidation. Clusters of one or
         * two chunks are left untouched — merging so few facts rarely removes
         * redundancy and risks losing nuance.
         */
        const val MIN_CLUSTER_SIZE = 3

        /**
         * Minimum number of cluster members a summary must be verified to cover
         * for the consolidation to be worth committing.
         *
         * Below this the trade collapses: replacing a single fact with a
         * model's paraphrase of it removes no redundancy while adding the risk
         * the paraphrase is wrong, and covering none of them means the reply was
         * not a summary of this cluster at all.
         */
        const val MIN_COVERED_TO_CONSOLIDATE = 2
    }

    /**
     * Summary of a single compaction pass.
     *
     * @property clustersProcessed Number of clusters consolidated into a summary.
     * @property chunksConsolidated Number of original chunks merged away (and
     *   deleted) across all processed clusters.
     * @property chunksCreated Number of new summary chunks written (equals
     *   [clustersProcessed]; tracked separately for observability clarity).
     * @property clustersRejected Number of eligible clusters left untouched
     *   because their summary was blank, failed to persist, or did not cover
     *   enough of the cluster to be trusted. A non-zero value is the signal
     *   that the local model is producing summaries worth distrusting.
     * @property chunksKeptUnverified Number of chunks that belonged to a
     *   consolidated cluster but were **not** represented by its summary, and so
     *   were kept verbatim instead of being deleted.
     */
    data class MemoryCompactionOutcome(
        val clustersProcessed: Int,
        val chunksConsolidated: Int,
        val chunksCreated: Int,
        val clustersRejected: Int = 0,
        val chunksKeptUnverified: Int = 0,
    ) {
        /** Shared constants for [MemoryCompactionOutcome]. */
        companion object {
            /** Result of a pass that did nothing (too few candidates, or model unavailable). */
            val EMPTY = MemoryCompactionOutcome(clustersProcessed = 0, chunksConsolidated = 0, chunksCreated = 0)
        }
    }
}
