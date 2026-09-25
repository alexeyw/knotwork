package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject

/**
 * Re-runs the user's active embedding provider over every stored memory
 * chunk and writes the fresh embedding back. Backs the Settings →
 * Memory → Re-embed action.
 *
 * This is the canonical way to repair embedding-space drift after the user
 * switches embedding providers: chunks written under a previous provider (a
 * different dimension) would otherwise never match a query embedded with the
 * new active provider. The provider is resolved once up front so the whole
 * batch lands in a single, consistent space.
 *
 * Streams progress as a fraction `0f..1f` so the UI can render an
 * inline progress indicator without polling. Emits a final `1f` once
 * every chunk has been re-encoded and persisted.
 *
 * Errors on individual chunks are logged and skipped — a single bad
 * row should not abort the rest of the batch. The caller can still
 * inspect the final emitted value (which will be `1f` once the iteration
 * finishes) and the Timber log to spot partial failures.
 */
class ReembedAllMemoriesUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val embeddingProviderResolver: EmbeddingProviderResolver,
) {
    /**
     * @return A cold [Flow] that begins at `0f` and ticks up to `1f` as
     *   chunks are re-embedded; consumers should `collect` it inside a
     *   `viewModelScope` (or hand it to a WorkManager worker for
     *   resilience).
     */
    operator fun invoke(): Flow<Float> = flow {
        val memories = memoryRepository.getAllMemories()
        if (memories.isEmpty()) {
            emit(1f)
            return@flow
        }
        emit(0f)
        val provider = embeddingProviderResolver.resolve()
        memories.forEachIndexed { index, memory ->
            try {
                val embedding = provider.embed(memory.text)
                // markMemoryReembedded (not updateMemory) so this manual pass
                // also clears any `needsReembedding` flag left by an import
                // under a different provider — otherwise the two repair paths
                // diverge and the background worker would re-embed these rows
                // again. The text is unchanged by a re-embed, so only the
                // vector needs writing.
                memoryRepository.markMemoryReembedded(memory.id, embedding)
            } catch (e: CancellationException) {
                // A cancelled re-embed must actually halt instead of silently
                // grinding through the rest of the corpus.
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Failed to re-embed memory %s", memory.id)
            }
            emit((index + 1).toFloat() / memories.size)
        }
    }.flowOn(Dispatchers.IO)
}
