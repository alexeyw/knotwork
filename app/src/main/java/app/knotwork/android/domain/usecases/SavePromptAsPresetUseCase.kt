package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.PromptPresetConstants
import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.models.InvalidInputException
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.repositories.PromptPresetRepository
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

/**
 * Packages a freshly-edited system prompt into a user-saved
 * [PromptPreset] and persists it via [PromptPresetRepository].
 *
 * The use case is the only entry point for creating user prompt presets —
 * it enforces:
 * - the same name validation as [SavePipelineAsPresetUseCase]
 *   (trim + 1..[PromptPresetConstants.MAX_NAME_LENGTH]),
 * - non-blank `systemPrompt` not exceeding
 *   [PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH] after trim,
 * - a [NodeType] that actually runs a system prompt
 *   ([PromptPresetConstants.LLM_DRIVEN_NODE_TYPES]); a non-LLM type is
 *   rejected because saving a preset against it would never be used by
 *   any executor.
 *
 * The persisted preset always carries `isBundled = false` — bundled
 * presets ship inside the APK and never originate from this flow.
 *
 * @property promptPresetRepository Persistence sink for the new preset.
 */
class SavePromptAsPresetUseCase @Inject constructor(private val promptPresetRepository: PromptPresetRepository) {

    /**
     * Builds a [PromptPreset] from [systemPrompt] and persists it.
     *
     * @param systemPrompt The raw prompt template to save; must not be
     *   blank after trim; must not exceed
     *   [PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH] characters.
     * @param name Display name for the preset. Trimmed; must be
     *   1..[PromptPresetConstants.MAX_NAME_LENGTH] characters after trim.
     * @param description Free-form description shown under the name in the
     *   picker. Trimmed.
     * @param nodeType The node type this preset targets. Must be an
     *   LLM-driven type (see [PromptPresetConstants.LLM_DRIVEN_NODE_TYPES]).
     * @param tags Lower-case kebab-case labels for filter-by-tag.
     * @return [Result.success] holding the id of the newly persisted preset,
     *   or [Result.failure] when validation fails or the persistence layer
     *   throws.
     */
    @Suppress("ReturnCount")
    suspend operator fun invoke(
        systemPrompt: String,
        name: String,
        description: String,
        nodeType: NodeType,
        tags: List<String> = emptyList(),
        existingId: String? = null,
    ): Result<String> {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            return Result.failure(InvalidInputException(InputProblem.BlankName(InputProblem.Subject.PRESET)))
        }
        if (trimmedName.length > PromptPresetConstants.MAX_NAME_LENGTH) {
            return Result.failure(
                InvalidInputException(
                    InputProblem.NameTooLong(InputProblem.Subject.PRESET, PromptPresetConstants.MAX_NAME_LENGTH),
                ),
            )
        }

        val trimmedPrompt = systemPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Result.failure(InvalidInputException(InputProblem.BlankSystemPrompt))
        }
        if (trimmedPrompt.length > PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH) {
            return Result.failure(
                InvalidInputException(InputProblem.SystemPromptTooLong(PromptPresetConstants.MAX_SYSTEM_PROMPT_LENGTH)),
            )
        }

        if (nodeType !in PromptPresetConstants.LLM_DRIVEN_NODE_TYPES) {
            return Result.failure(InvalidInputException(InputProblem.NoPromptForNodeType(nodeType.name)))
        }

        val preset = PromptPreset(
            // `existingId` enables in-place update of a user-saved preset
            // (insert-or-update semantics — the repository upserts by id).
            // A blank `existingId` falls back to a fresh UUID so the
            // legacy save-new path keeps working unchanged.
            id = existingId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = trimmedName,
            description = description.trim(),
            nodeType = nodeType,
            systemPrompt = trimmedPrompt,
            // Normalise tags: trim, drop blanks, deduplicate while
            // preserving the author's chosen order.
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            isBundled = false,
        )

        return try {
            promptPresetRepository.saveUserPreset(preset)
            Result.success(preset.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
