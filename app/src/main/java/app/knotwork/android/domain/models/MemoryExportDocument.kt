package app.knotwork.android.domain.models

/**
 * Parsed representation of a memory export file.
 *
 * Produced by [app.knotwork.android.domain.memoryio.MemoryJsonSerializer.parse] and
 * consumed by [app.knotwork.android.domain.usecases.MemoryImportUseCase] to load the
 * chunks back into the local store. Decoupled from the on-disk JSON so the
 * import use case never touches `org.json`.
 *
 * @property embeddingProviderId Id of the [app.knotwork.android.domain.services.EmbeddingProvider]
 *   that was active when the file was exported. When this differs from the
 *   importing device's active provider the stored embeddings live in a
 *   different vector space and must be re-computed before they can match a
 *   query (see [app.knotwork.android.domain.usecases.RecomputePendingEmbeddingsUseCase]).
 * @property exportedAt Epoch-millis the file was written. Informational only.
 * @property chunks The exported memory chunks, carrying their original id,
 *   text, embedding, provenance and tags. When parsed from a file every chunk
 *   is unpinned and none is dated later than the parse — a file cannot grant
 *   either privilege (see [app.knotwork.android.domain.memoryio.MemoryJsonSerializer]).
 * @property pinnedInFile How many chunks the file marked as pinned. The pins
 *   are not applied; the count lets the import dialog tell the user they were
 *   dropped. `0` for a document not read from a file.
 */
data class MemoryExportDocument(
    val embeddingProviderId: String,
    val exportedAt: Long,
    val chunks: List<MemoryChunk>,
    val pinnedInFile: Int = 0,
)
