package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.knotwork.android.data.local.models.PendingInteractionEntity

/**
 * Data Access Object for [PendingInteractionEntity].
 *
 * The response-recording updates ([recordDecision], [recordAnswer]) carry an
 * `IS NULL` guard so the first writer wins: a duplicate notification tap or a
 * race between the notification action and the chat card can never overwrite
 * an already-recorded response.
 */
@Dao
interface PendingInteractionDao {

    /**
     * Inserts a freshly parked interaction, replacing any previous record of
     * the same run — a re-park after a TOCTOU mismatch supersedes the stale
     * request snapshot.
     *
     * @param interaction The entity to write.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(interaction: PendingInteractionEntity)

    /**
     * Returns the pending interaction of run [runId], or `null` when the run
     * is not parked.
     *
     * @param runId Id of the run to look up.
     */
    @Query("SELECT * FROM pending_interactions WHERE runId = :runId")
    suspend fun getForRun(runId: String): PendingInteractionEntity?

    /**
     * Returns the most recently parked interaction of session [sessionId],
     * or `null` when the session has none.
     *
     * @param sessionId Id of the chat session to look up.
     */
    @Query(
        "SELECT * FROM pending_interactions WHERE sessionId = :sessionId " +
            "ORDER BY requestedAt DESC LIMIT 1",
    )
    suspend fun getForSession(sessionId: String): PendingInteractionEntity?

    /**
     * Returns the record parking approval request [requestId], or `null` when
     * no record parks it.
     *
     * @param requestId Identity of the approval request to look up.
     */
    @Query("SELECT * FROM pending_interactions WHERE requestId = :requestId")
    suspend fun getForRequest(requestId: String): PendingInteractionEntity?

    /**
     * Records the user's decision, guarded to first-writer-wins. For a ceiling
     * pause; an approval answer goes through [recordApprovalDecision].
     *
     * @param runId Id of the parked run.
     * @param decision The `PendingDecision` name to record.
     * @return The number of updated rows — `1` when this call recorded the
     *   decision, `0` when the record is missing or already decided.
     */
    @Query(
        "UPDATE pending_interactions SET decision = :decision " +
            "WHERE runId = :runId AND decision IS NULL",
    )
    suspend fun recordDecision(runId: String, decision: String): Int

    /**
     * Records the user's approval decision, guarded to first-writer-wins and
     * to the request it answers: a record the same run re-parked with a
     * different request in the meantime is not written.
     *
     * @param runId Id of the parked run.
     * @param requestId Identity of the request the decision was given for.
     * @param decision The `PendingDecision` name to record.
     * @return The number of updated rows — `1` when this call recorded the
     *   decision, `0` when the record is missing, parks another request, or is
     *   already decided.
     */
    @Query(
        "UPDATE pending_interactions SET decision = :decision " +
            "WHERE runId = :runId AND requestId = :requestId AND decision IS NULL",
    )
    suspend fun recordApprovalDecision(runId: String, requestId: String, decision: String): Int

    /**
     * Records the user's clarification answer, guarded to first-writer-wins.
     *
     * @param runId Id of the parked run.
     * @param answer The answer text to record.
     * @return The number of updated rows — `1` when this call recorded the
     *   answer, `0` when the record is missing or already answered.
     */
    @Query(
        "UPDATE pending_interactions SET answer = :answer " +
            "WHERE runId = :runId AND answer IS NULL",
    )
    suspend fun recordAnswer(runId: String, answer: String): Int

    /**
     * Deletes the pending interaction of run [runId]; no-op when absent.
     *
     * @param runId Id of the run whose record to delete.
     */
    @Query("DELETE FROM pending_interactions WHERE runId = :runId")
    suspend fun delete(runId: String)

    /**
     * Returns every interaction parked at or before [cutoff]. Backs the
     * maintenance expiry pass.
     *
     * @param cutoff Inclusive upper bound on [PendingInteractionEntity.requestedAt].
     */
    @Query("SELECT * FROM pending_interactions WHERE requestedAt <= :cutoff")
    suspend fun getRequestedAtOrBefore(cutoff: Long): List<PendingInteractionEntity>

    /**
     * Returns the run ids of all pending interactions, for the orphan-sweep
     * exemption set.
     */
    @Query("SELECT runId FROM pending_interactions")
    suspend fun getAllRunIds(): List<String>
}
