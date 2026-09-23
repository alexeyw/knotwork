package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.repositories.PipelineRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Use case for renaming an existing pipeline in the library.
 *
 * The pipeline is fetched by id, has its [name] and [updatedAt] mutated, and is
 * persisted through [PipelineRepository.savePipeline]. The graph structure
 * (nodes / connections / ids) is preserved verbatim; this is the only public
 * entry point for changing a pipeline's display name and is therefore the
 * canonical "trim + validate" gate for user-supplied names.
 *
 * Callers receive a [Result] so the UI can distinguish recoverable validation
 * failures (blank name, name too long, missing pipeline) from save errors and
 * surface a Snackbar. The repository never throws across this boundary.
 *
 * @property pipelineRepository Source of the existing graph and the persistence sink.
 */
class RenamePipelineUseCase @Inject constructor(private val pipelineRepository: PipelineRepository) {
    /**
     * Renames the pipeline identified by [pipelineId] to [newName].
     *
     * The new name is trimmed before validation. Empty / blank values and
     * names exceeding [MAX_NAME_LENGTH] are rejected with
     * [IllegalArgumentException] so the caller can present a dedicated error
     * (rather than a generic "save failed").
     *
     * @param pipelineId Unique identifier of the pipeline to rename.
     * @param newName The new display name. Surrounding whitespace is stripped.
     * @return [Result.success] on persistence, [Result.failure] for validation
     * errors, missing pipeline, or storage exceptions.
     */
    suspend operator fun invoke(pipelineId: String, newName: String): Result<Unit> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Pipeline name cannot be empty"))
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Pipeline name must be $MAX_NAME_LENGTH characters or fewer"),
            )
        }
        // Wrap both the read and the write in a single try/catch — a thrown
        // `getPipelineById` (closed DB, decryption failure, I/O) must surface
        // as `Result.failure`, never as an unhandled exception that escapes
        // the use-case boundary.
        return try {
            val existing = pipelineRepository.getPipelineById(pipelineId)
                ?: return Result.failure(IllegalStateException("Pipeline not found"))
            pipelineRepository.savePipeline(
                existing.copy(name = trimmed, updatedAt = System.currentTimeMillis()),
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        /**
         * Upper bound on the rendered name length — the shared ceiling, which
         * the JSON importer applies too (by truncation), so a file cannot
         * carry a longer name past the rule.
         */
        const val MAX_NAME_LENGTH = PipelineConstants.MAX_NAME_LENGTH
    }
}
