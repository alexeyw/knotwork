package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.ActiveModelMeta
import app.knotwork.android.domain.models.LocalModel
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing downloaded LLM models metadata.
 */
interface LocalModelRepository {
    /**
     * Retrieves all saved models as a Flow.
     *
     * @return Flow containing a list of [LocalModel].
     */
    fun getAllModels(): Flow<List<LocalModel>>

    /**
     * Retrieves the currently active model if one exists.
     *
     * @return The active [LocalModel] or null.
     */
    suspend fun getActiveModel(): LocalModel?

    /**
     * Inserts a new model record.
     *
     * @param model The [LocalModel] to insert.
     * @return The row ID of the newly inserted item.
     */
    suspend fun insertModel(model: LocalModel): Long

    /**
     * Updates an existing model record.
     *
     * @param model The [LocalModel] to update.
     */
    suspend fun updateModel(model: LocalModel)

    /**
     * Deletes a model record by its ID.
     *
     * @param id The ID of the model to delete.
     */
    suspend fun deleteModelById(id: Long)

    /**
     * Sets a specific model as the active one, unsetting any previously active models.
     *
     * @param id The ID of the model to activate.
     */
    suspend fun setActiveModel(id: Long)

    /**
     * Sets the manual vision-capability flag of the model with the given [id].
     * Backs the "this model can read images" toggle on the model screen; the
     * multimodal pre-flight send guard reads the resulting
     * [LocalModel.supportsVision] to decide whether an image message may run
     * against the active model.
     *
     * @param id The ID of the model to update.
     * @param enabled `true` to mark the model vision-capable, `false` otherwise.
     */
    suspend fun setVisionSupport(id: Long, enabled: Boolean)

    /**
     * Sets the manual audio-capability flag of the model with the given [id].
     * Backs the "this model can transcribe audio" toggle on the model screen;
     * the voice-input transcription pre-flight reads the resulting
     * [LocalModel.supportsAudio] to decide whether the active model may
     * transcribe a recorded/picked clip.
     *
     * @param id The ID of the model to update.
     * @param enabled `true` to mark the model audio-capable, `false` otherwise.
     */
    suspend fun setAudioSupport(id: Long, enabled: Boolean)

    /**
     * Live snapshot of the currently active model enriched with on-device
     * metadata (file size, parsed quantization marker, downloaded
     * timestamp). Emits `null` when no model has been activated yet —
     * the Settings → Local model card then renders an empty state with a
     * primary "Browse models" CTA.
     */
    fun observeActiveModelMeta(): Flow<ActiveModelMeta?>

    /**
     * Returns `true` when a model with the given on-disk [fileName] is
     * already present in the local database. Used by the onboarding flow
     * to gate the step-2 download — if the user re-opens onboarding
     * after a previous install, the matching row immediately renders as
     * "Installed" and the CTA stays enabled without re-downloading.
     *
     * Matching is performed against [LocalModel.name] (the filename the
     * download manager wrote to disk), case-sensitive.
     */
    suspend fun isInstalled(fileName: String): Boolean

    /**
     * Returns the [LocalModel] whose on-disk name matches [fileName]
     * exactly, or `null` when no such row exists. Independent of the
     * `isActive` flag — the onboarding flow needs the path of the row
     * the user *picked*, not the row currently selected as active.
     */
    suspend fun findByFileName(fileName: String): LocalModel?

    /**
     * Returns the [LocalModel] whose file is [path], or `null` when no row points
     * at it. A file downloaded from a repository sub-folder is registered under
     * its repository name but stored under a flattened one, so the path is what
     * names the file.
     *
     * @param path Absolute path of the model file.
     */
    suspend fun findByPath(path: String): LocalModel?
}
