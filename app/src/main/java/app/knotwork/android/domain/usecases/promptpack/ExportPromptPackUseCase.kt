package app.knotwork.android.domain.usecases.promptpack

import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.models.InvalidInputException
import app.knotwork.android.domain.promptpack.PromptPackMarkdownSerializer
import app.knotwork.android.domain.repositories.PromptPresetRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * A prompt rendered into its file form, ready to be written to a document
 * the user picks.
 *
 * @property fileName Suggested document name, offered to the picker.
 * @property displayName The prompt's display name, so the confirmation can
 *   say which prompt was written without re-reading it.
 * @property content The markdown document text.
 */
data class ExportedPromptPack(val fileName: String, val displayName: String, val content: String)

/**
 * Renders a saved prompt into its prompt-pack file form.
 *
 * Bundled prompts export exactly as user ones do: exporting is a read, and a
 * curated prompt is the likeliest thing someone wants to hand to another
 * person. Nothing about the export path can mutate the catalogue.
 *
 * @property promptPresetRepository Source of the prompt being exported.
 */
class ExportPromptPackUseCase @Inject constructor(private val promptPresetRepository: PromptPresetRepository) {

    /**
     * Looks up [presetId] and renders it.
     *
     * @param presetId Id of the prompt to export, from either catalogue.
     * @return [Result.success] with the rendered document, or
     *   [Result.failure] when no prompt carries that id — which can happen
     *   if the row was deleted between the tap and the picker returning.
     */
    suspend operator fun invoke(presetId: String): Result<ExportedPromptPack> {
        val preset = try {
            promptPresetRepository.getPresetById(presetId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Returned rather than thrown: the caller launches this in
            // `viewModelScope` and reads the `Result`, so a raw throw here
            // would take the process down instead of showing a message.
            return Result.failure(e)
        } ?: return Result.failure(
            InvalidInputException(InputProblem.NotFound(InputProblem.Subject.PROMPT_PRESET, presetId)),
        )
        return Result.success(
            ExportedPromptPack(
                fileName = PromptPackMarkdownSerializer.suggestFileName(preset),
                displayName = preset.name,
                content = PromptPackMarkdownSerializer.serialize(preset),
            ),
        )
    }
}
