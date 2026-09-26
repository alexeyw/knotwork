package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.knotwork.android.data.local.models.BackgroundPromptEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the `background_prompts` table: the prompts of queued
 * background runs, held here instead of in the background runtime's
 * unencrypted store.
 */
@Dao
interface BackgroundPromptDao {

    /**
     * Stores a prompt, replacing one stored under the same id.
     *
     * @param entity The prompt row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BackgroundPromptEntity)

    /**
     * Reads the prompt stored under [id].
     *
     * @param id The id a background request carries.
     * @return The prompt, or `null` when none is stored.
     */
    @Query("SELECT prompt FROM background_prompts WHERE id = :id")
    suspend fun promptOf(id: String): String?

    /**
     * Removes the prompt stored under [id].
     *
     * @param id The prompt's id.
     */
    @Query("DELETE FROM background_prompts WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Lists the ids of the prompts stored before [storedBefore].
     *
     * @param storedBefore Cutoff, epoch millis.
     * @return The ids, in no particular order.
     */
    @Query("SELECT id FROM background_prompts WHERE createdAt < :storedBefore")
    suspend fun idsStoredBefore(storedBefore: Long): List<String>

    /**
     * Removes the prompts stored under [ids].
     *
     * @param ids The ids to remove.
     */
    @Query("DELETE FROM background_prompts WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    /**
     * Observes every stored prompt.
     *
     * @return The rows, re-emitted on every change.
     */
    @Query("SELECT * FROM background_prompts")
    fun observeAll(): Flow<List<BackgroundPromptEntity>>
}
