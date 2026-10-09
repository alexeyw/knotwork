package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.knotwork.android.data.local.models.TriggerEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `triggers` table (v46) backing user-defined automation triggers.
 */
@Dao
interface TriggerDao {

    /**
     * Observes every trigger, newest first.
     *
     * @return A [Flow] emitting the full trigger list on every change.
     */
    @Query("SELECT * FROM triggers ORDER BY createdAt DESC")
    fun getAll(): Flow<List<TriggerEntity>>

    /**
     * Observes the enabled, pipeline-bound triggers — the set the scheduler
     * registers with the background runtime. Filtered in SQL (on the indexed
     * `enabled` column plus a non-null `pipelineId`) so the hot sync path does
     * no in-memory filtering.
     *
     * @return A [Flow] emitting the active-trigger subset on every change.
     */
    @Query("SELECT * FROM triggers WHERE enabled = 1 AND pipelineId IS NOT NULL ORDER BY createdAt DESC")
    fun getActive(): Flow<List<TriggerEntity>>

    /**
     * Loads a single trigger by id.
     *
     * @param id The trigger id.
     * @return The row, or `null` if none matches.
     */
    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun getById(id: String): TriggerEntity?

    /**
     * Inserts or replaces a trigger (upsert by primary key).
     *
     * @param entity The row to persist.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TriggerEntity)

    /**
     * Deletes a trigger by id. Idempotent.
     *
     * @param id The trigger id to remove.
     */
    @Query("DELETE FROM triggers WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Updates a trigger's enabled flag.
     *
     * @param id The trigger id.
     * @param enabled The new enabled state.
     */
    @Query("UPDATE triggers SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /**
     * Enables a disabled trigger and records the moment it was enabled. A trigger
     * that is already enabled is left as it is, so the moment stays the first one.
     *
     * @param id The trigger id.
     * @param activatedAt Epoch-millis of the enabling.
     */
    @Query("UPDATE triggers SET enabled = 1, activatedAt = :activatedAt WHERE id = :id AND enabled = 0")
    suspend fun enable(id: String, activatedAt: Long)

    /**
     * Records which silence the user was last told about.
     *
     * @param id The trigger id.
     * @param signOfLife The last-sign-of-life moment the notice was about.
     */
    @Query("UPDATE triggers SET staleNoticeFor = :signOfLife WHERE id = :id")
    suspend fun setStaleNoticeFor(id: String, signOfLife: Long)

    /**
     * Updates a trigger's armed edge latch.
     *
     * @param id The trigger id.
     * @param armed The new armed state.
     */
    @Query("UPDATE triggers SET armed = :armed WHERE id = :id")
    suspend fun setArmed(id: String, armed: Boolean)

    /**
     * Records the most recent fire time.
     *
     * @param id The trigger id.
     * @param firedAt Epoch-millis of the fire.
     */
    @Query("UPDATE triggers SET lastFiredAt = :firedAt WHERE id = :id")
    suspend fun markFired(id: String, firedAt: Long)

    /**
     * Binds the trigger to the chat session its background runs land in.
     *
     * @param id The trigger id.
     * @param sessionId The chat session id to bind.
     */
    @Query("UPDATE triggers SET sessionId = :sessionId WHERE id = :id")
    suspend fun setSessionId(id: String, sessionId: String)
}
