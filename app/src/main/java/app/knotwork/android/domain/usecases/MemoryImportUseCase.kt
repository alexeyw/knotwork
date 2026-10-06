package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.memoryio.MemoryJsonSerializer
import app.knotwork.android.domain.models.MemoryExportDocument
import app.knotwork.android.domain.models.MemoryImportOutcome
import app.knotwork.android.domain.models.MemoryImportStrategy
import app.knotwork.android.domain.models.MemoryWithHistory
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.MemoryReembedScheduler
import javax.inject.Inject

/**
 * Imports long-term memory chunks from a JSON document produced by
 * [app.knotwork.android.domain.usecases.ExportMemoryBaseUseCase] on another device.
 *
 * Two-step orchestration, mirroring [ImportPipelineUseCase]:
 *
 * 1. [parse] turns the raw JSON into a [MemoryImportOutcome] so the UI can
 *    branch (Success / SchemaMismatch / Failure) and surface a warning before
 *    touching the database.
 * 2. [import] reconciles the parsed [MemoryExportDocument] with the local store
 *    under the chosen [MemoryImportStrategy] and reports what happened.
 *
 * Splitting parse from import keeps the use case testable without an `Activity`
 * and lets the UI raise a strategy / compatibility dialog before any mutation.
 *
 * **Provider mismatch is decided against the *resolved* provider.** Both the
 * re-embed decision ([import]) and the UI warning ([isProviderMismatched])
 * compare the file's stamp against [EmbeddingProviderResolver.resolve], not the
 * raw persisted `activeEmbeddingProviderId`. The resolver falls back to the
 * on-device default when the selected provider is unavailable (e.g.
 * `openai_3_small` is selected but no API key is configured), and that fallback
 * is exactly what retrieval and auto-extraction embed with. Comparing against
 * the raw setting would, in that state, see an OpenAI-stamped file "match" the
 * OpenAI selection and skip re-embedding — leaving imported 1536-d vectors that
 * every USE-embedded query scores as a mismatch and never repairs.
 *
 * @property memoryRepository Backing store for the existing-id lookup, the
 *   transactional replace used by [MemoryImportStrategy.Replace], and the bulk
 *   insert.
 * @property embeddingProviderResolver Resolves the provider retrieval will
 *   actually use (with on-device fallback), so the re-embed decision matches
 *   the space queries are embedded in.
 * @property reembedScheduler Enqueues the background re-embed pass when the
 *   imported chunks were flagged for re-embedding (provider mismatch).
 */
class MemoryImportUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val embeddingProviderResolver: EmbeddingProviderResolver,
    private val reembedScheduler: MemoryReembedScheduler,
) {

    /**
     * Parses [jsonText] into a [MemoryImportOutcome] without persisting
     * anything. Never throws — see [MemoryJsonSerializer.parse].
     *
     * @param jsonText Raw JSON read from the user-selected file.
     * @return The parse outcome the UI should branch on.
     */
    fun parse(jsonText: String): MemoryImportOutcome = MemoryJsonSerializer.parse(jsonText)

    /**
     * Reports whether [document] was exported under a different embedding
     * provider than the one this device will actually use for retrieval — i.e.
     * the [EmbeddingProviderResolver.resolve] result, which accounts for the
     * on-device fallback when the selected provider is unavailable. Drives the
     * import dialog's "embeddings will be re-computed" warning, and mirrors the
     * exact decision [import] makes, so the warning never disagrees with the
     * behaviour.
     *
     * @param document The parsed document about to be imported.
     * @return `true` when the file's vectors live in a different space than the
     *   active resolved provider and would need re-embedding.
     */
    suspend fun isProviderMismatched(document: MemoryExportDocument): Boolean =
        document.embeddingProviderId != embeddingProviderResolver.resolve().id

    /**
     * Reconciles [document] with the local store under [strategy].
     *
     * - [MemoryImportStrategy.Replace] atomically wipes every existing chunk and
     *   loads the file's chunks, so the store becomes an exact copy of the file.
     *   A document with no chunks is a no-op: the existing store is left intact
     *   rather than wiped with nothing to load.
     * - [MemoryImportStrategy.Merge] keeps existing chunks and inserts only
     *   those whose id is not already present (duplicates are skipped).
     *
     * When [document]'s `embeddingProviderId` differs from the **resolved**
     * active provider ([isProviderMismatched]) the imported vectors live in an
     * incompatible space, so every inserted chunk is flagged for re-embedding
     * and a background re-embed pass is scheduled (the worker repairs them off
     * the hot path).
     *
     * @param document The parsed document to import.
     * @param strategy How to reconcile with the existing store.
     * @return A [MemoryImportResult] describing how many chunks were imported
     *   and skipped, and whether a re-embed was scheduled.
     */
    suspend fun import(document: MemoryExportDocument, strategy: MemoryImportStrategy): MemoryImportResult {
        val needsReembedding = isProviderMismatched(document)
        val toInsert = when (strategy) {
            MemoryImportStrategy.Replace -> {
                // Guard: never wipe the store when there is nothing to load.
                if (document.chunks.isEmpty()) {
                    return MemoryImportResult(imported = 0, skipped = 0, needsReembedding = false)
                }
                val deduped = document.memories.dedupById()
                memoryRepository.replaceImportedMemories(deduped, needsReembedding)
                deduped
            }
            MemoryImportStrategy.Merge -> {
                val existing = memoryRepository.getExistingMemoryIds()
                // Drop chunks whose id is already stored. id == 0 always passes
                // (existing holds only real positive row ids) and dedupById then
                // keeps every id-0 chunk while collapsing positive-id duplicates
                // within the file, so the count matches what REPLACE-on-conflict
                // actually persists.
                val fresh = document.memories.filter { it.chunk.id !in existing }.dedupById()
                memoryRepository.insertImportedMemories(fresh, needsReembedding)
                fresh
            }
        }
        // Schedule the background repair only when chunks landed under a
        // mismatched provider; nothing to do otherwise.
        val scheduledReembed = needsReembedding && toInsert.isNotEmpty()
        if (scheduledReembed) {
            reembedScheduler.schedule()
        }
        return MemoryImportResult(
            imported = toInsert.size,
            skipped = document.chunks.size - toInsert.size,
            needsReembedding = scheduledReembed,
        )
    }

    /**
     * Drops file-internal duplicate ids so the reported `imported` count matches
     * the rows actually persisted: `@Insert(onConflict = REPLACE)` collapses two
     * chunks sharing a positive id into a single row (last wins). Chunks with
     * `id == 0` are never deduped — each gets its own auto-assigned key.
     */
    private fun List<MemoryWithHistory>.dedupById(): List<MemoryWithHistory> {
        val byId = LinkedHashMap<Long, MemoryWithHistory>()
        val autoAssigned = ArrayList<MemoryWithHistory>()
        for (memory in this) {
            if (memory.chunk.id == 0L) autoAssigned.add(memory) else byId[memory.chunk.id] = memory
        }
        return byId.values + autoAssigned
    }
}

/**
 * Summary of a completed [MemoryImportUseCase.import] call, used to compose the
 * confirmation snackbar.
 *
 * @property imported Number of chunks written to the store.
 * @property skipped Number of chunks dropped as duplicates (always `0` for
 *   [MemoryImportStrategy.Replace]).
 * @property needsReembedding `true` when the imported chunks were flagged for
 *   lazy re-embedding because the file used a different embedding provider.
 */
data class MemoryImportResult(val imported: Int, val skipped: Int, val needsReembedding: Boolean)
