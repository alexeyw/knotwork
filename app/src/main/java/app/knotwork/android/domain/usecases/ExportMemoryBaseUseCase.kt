package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.memoryio.MemoryJsonSerializer
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MemorySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject

/**
 * Serialises the memory table into a portable JSON blob and writes it to an
 * arbitrary [OutputStream]. Backs the Settings → Memory → Export action and the
 * Memory screen's multi-select "Export selected" action; the screen owns the
 * SAF launcher that produces the stream so the use case stays free of Android
 * imports.
 *
 * The on-disk shape is owned by [MemoryJsonSerializer] (`schemaVersion: 1`):
 * a top-level object carrying `embeddingProviderId` (the provider that produced
 * the stored vectors — lets an importing device detect a vector-space
 * mismatch), `exportedAt`, and a `chunks` array. The matching reader is
 * [MemoryImportUseCase].
 *
 * @property memoryRepository Source of the chunks to export.
 * @property memorySettings Source of the active embedding provider id
 *   stamped onto the document.
 * @return Number of chunks written.
 */
class ExportMemoryBaseUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memorySettings: MemorySettings,
) {
    /**
     * Serialises chunks into [target] and returns the number of entries
     * written. Closes the stream via `bufferedWriter().use { … }`.
     *
     * @param target Destination stream (owned by the caller's SAF launcher).
     * @param ids When `null`, every stored chunk is exported (the Settings →
     *   Memory → Export action). When non-null, only chunks whose id is in the
     *   set are written — backs the Memory screen's multi-select "Export
     *   selected" action. An empty set therefore writes zero chunks.
     * @param nowMillis Epoch-millis recorded as the export time; defaults to the
     *   current wall-clock and is overridable for deterministic tests.
     */
    suspend operator fun invoke(
        target: OutputStream,
        ids: Set<Long>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        val memories = memoryRepository.getAllMemories()
            .let { all -> if (ids == null) all else all.filter { it.id in ids } }
        val providerId = memorySettings.activeEmbeddingProviderId.first()
        val payload = MemoryJsonSerializer.serialize(
            chunks = memories,
            embeddingProviderId = providerId,
            exportedAt = nowMillis,
        )
        target.bufferedWriter().use { writer ->
            writer.write(payload)
        }
        memories.size
    }
}
