package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.knotwork.android.data.local.models.LocalModelEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for managing local LLM models in the Room database.
 */
@Dao
interface LocalModelDao {
    /**
     * Retrieves all saved models as a Flow.
     *
     * @return Flow containing a list of [LocalModelEntity].
     */
    @Query("SELECT * FROM local_models")
    fun getAllModels(): Flow<List<LocalModelEntity>>

    /**
     * Retrieves the currently active model if one exists.
     *
     * @return The active [LocalModelEntity] or null.
     */
    @Query("SELECT * FROM local_models WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveModel(): LocalModelEntity?

    /**
     * Live projection of the currently active model — emits a new value
     * whenever any row toggles the `isActive` column. Powers the
     * Settings → Local model card.
     */
    @Query("SELECT * FROM local_models WHERE isActive = 1 LIMIT 1")
    fun observeActiveModel(): Flow<LocalModelEntity?>

    /**
     * Inserts a new model record. Replaces if a conflict occurs.
     *
     * @param model The [LocalModelEntity] to insert.
     * @return The row ID of the newly inserted item.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: LocalModelEntity): Long

    /**
     * Updates an existing model record.
     *
     * @param model The [LocalModelEntity] to update.
     */
    @Update
    suspend fun updateModel(model: LocalModelEntity)

    /**
     * Returns the row with the given [id], or `null` when none exists.
     * Used by `LocalModelRepository.deleteModelById` to resolve the on-disk
     * [LocalModelEntity.path] so the model file can be removed alongside the
     * record.
     *
     * @param id The ID of the model to look up.
     * @return the matching entity, or `null`.
     */
    @Query("SELECT * FROM local_models WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): LocalModelEntity?

    /**
     * Deletes a model record by its ID.
     *
     * @param id The ID of the model to delete.
     */
    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun deleteModelById(id: Long)

    /**
     * Unsets the isActive flag for all models.
     */
    @Query("UPDATE local_models SET isActive = 0")
    suspend fun deactivateAllModels()

    /**
     * Sets the isActive flag to 1 for a specific model by its ID.
     *
     * @param id The ID of the model to activate.
     */
    @Query("UPDATE local_models SET isActive = 1 WHERE id = :id")
    suspend fun activateModelById(id: Long)

    /**
     * Sets the [LocalModelEntity.supportsVision] flag for a specific model.
     * Backs the manual "this model can read images" toggle on the model screen;
     * a targeted column update avoids a read-modify-write of the whole row.
     *
     * @param id The ID of the model to update.
     * @param enabled `true` to mark the model vision-capable, `false` otherwise.
     */
    @Query("UPDATE local_models SET supportsVision = :enabled WHERE id = :id")
    suspend fun setVisionSupport(id: Long, enabled: Boolean)

    /**
     * Sets the [LocalModelEntity.supportsAudio] flag for a specific model.
     * Backs the manual "this model can transcribe audio" toggle on the model
     * screen; a targeted column update avoids a read-modify-write of the whole
     * row.
     *
     * @param id The ID of the model to update.
     * @param enabled `true` to mark the model audio-capable, `false` otherwise.
     */
    @Query("UPDATE local_models SET supportsAudio = :enabled WHERE id = :id")
    suspend fun setAudioSupport(id: Long, enabled: Boolean)

    /**
     * Returns the number of rows whose [LocalModelEntity.name] matches
     * [fileName] exactly. Used by [LocalModelRepository.isInstalled] to
     * avoid loading the full table just to test presence.
     *
     * @param fileName the on-disk filename to look up.
     * @return row count (0 when not installed, >= 1 when installed).
     */
    @Query("SELECT COUNT(*) FROM local_models WHERE name = :fileName")
    suspend fun countByName(fileName: String): Int

    /**
     * Returns the first row whose [LocalModelEntity.name] matches
     * [fileName] exactly. The `LIMIT 1` makes the call deterministic
     * if the user has somehow ended up with two rows for the same
     * filename. Used by `OnboardingViewModel` to resolve the on-disk
     * path of the *picked* model independently of whichever model
     * currently has `isActive = 1` — picking and activation are
     * orthogonal.
     *
     * @param fileName the on-disk filename to look up.
     * @return the matching entity, or `null` when no row exists.
     */
    @Query("SELECT * FROM local_models WHERE name = :fileName LIMIT 1")
    suspend fun findByName(fileName: String): LocalModelEntity?

    /**
     * Returns the first row whose [LocalModelEntity.path] is [path] exactly.
     *
     * @param path Absolute path of the model file.
     * @return the matching entity, or `null` when no row exists.
     */
    @Query("SELECT * FROM local_models WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): LocalModelEntity?
}
