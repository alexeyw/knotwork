package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.PipelineConstants
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelineValidationException
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

/**
 * Packages the currently-edited [PipelineGraph] into a user-saved
 * [PipelinePreset] and persists it via [PipelinePresetRepository].
 *
 * The use case is the only entry point for creating user presets — it
 * enforces the same name validation as [CreatePipelineUseCase] /
 * [RenamePipelineUseCase] (trim + 1..[MAX_NAME_LENGTH]) and runs
 * [PipelineGraph.validate] before saving so a broken graph never reaches
 * the bundled-catalogue picker.
 *
 * The persisted preset always carries `isBundled = false` — bundled
 * presets ship inside the APK and never originate from this flow.
 *
 * @property pipelinePresetRepository Persistence sink for the new preset.
 */
class SavePipelineAsPresetUseCase @Inject constructor(private val pipelinePresetRepository: PipelinePresetRepository) {
    /**
     * Builds a [PipelinePreset] from [graph] and persists it.
     *
     * @param graph The pipeline graph to package as a preset.
     * @param name Display name for the preset. Trimmed; must be 1..[MAX_NAME_LENGTH] after trim.
     * @param description Free-form description shown under the name in the picker.
     * @param category Bucket for picker grouping.
     * @param tags Lower-case kebab-case labels for filter-by-tag.
     * @return [Result.success] holding the id of the newly persisted preset,
     *   or [Result.failure] when the name fails validation, the graph fails
     *   [PipelineGraph.validate], or the persistence layer throws.
     */
    suspend operator fun invoke(
        graph: PipelineGraph,
        name: String,
        description: String,
        category: PresetCategory,
        tags: List<String> = emptyList(),
    ): Result<String> {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Preset name must not be blank"))
        }
        if (trimmedName.length > MAX_NAME_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Preset name must be at most $MAX_NAME_LENGTH characters"),
            )
        }

        val errors = graph.validate()
        if (errors.isNotEmpty()) {
            return Result.failure(PipelineValidationException(errors))
        }

        val preset = PipelinePreset(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            description = description.trim(),
            category = category,
            // Normalise tags: trim, drop blanks, deduplicate while
            // preserving the author's chosen order.
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            graph = graph,
            isBundled = false,
        )

        return try {
            pipelinePresetRepository.saveUserPreset(preset)
            Result.success(preset.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        /** The shared pipeline-name ceiling ([PipelineConstants.MAX_NAME_LENGTH]). */
        const val MAX_NAME_LENGTH = PipelineConstants.MAX_NAME_LENGTH
    }
}
