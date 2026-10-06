package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.knotwork.android.data.local.models.TriggerEvaluationEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `trigger_evaluations` table (introduced in v51, HITL columns added
 * in v56) backing the trigger-evaluation journal.
 */
@Dao
interface TriggerJournalDao {

    /**
     * Inserts one evaluation record (upsert by primary key). The record id is a
     * fresh UUID per evaluation, so in practice this always inserts; `REPLACE`
     * only guards the degenerate id-collision case.
     *
     * @param entity The row to persist.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TriggerEvaluationEntity)

    /**
     * Attributes a terminal run outcome onto the journal row(s) whose run matches
     * [runId]. A no-op when no row references [runId].
     *
     * @param runId Id of the settled run.
     * @param outcomeKind Terminal outcome discriminator.
     * @param outcomeError Human-readable error for a failure outcome, else `null`.
     */
    @Query(
        "UPDATE trigger_evaluations SET outcomeKind = :outcomeKind, outcomeError = :outcomeError " +
            "WHERE runId = :runId",
    )
    suspend fun updateOutcome(runId: String, outcomeKind: String, outcomeError: String?)

    /**
     * Records a newly raised human-in-the-loop gate on the journal row(s) whose
     * run matches [runId]: counts the gate, remembers its kind, resets the
     * resolution to "still waiting" and clears the parked flag (the fresh gate
     * starts in its live waiting phase, whatever the previous one did).
     *
     * @param runId Id of the run that raised the gate.
     * @param kind Gate-kind discriminator (approval / clarification).
     * @param pendingResolution The "not resolved yet" resolution discriminator.
     */
    @Query(
        "UPDATE trigger_evaluations SET hitlGateCount = hitlGateCount + 1, hitlLastKind = :kind, " +
            "hitlLastResolution = :pendingResolution, hitlParked = 0 WHERE runId = :runId",
    )
    suspend fun recordHitlGateRaised(runId: String, kind: String, pendingResolution: String)

    /**
     * Marks the current gate of [runId] as parked on a durable record — the
     * answer can now only arrive from a notification.
     *
     * Guarded on a counted gate so a park reported without its preceding raise
     * (a lost write) cannot leave a row claiming a park it never recorded.
     *
     * @param runId Id of the run whose gate parked.
     */
    @Query("UPDATE trigger_evaluations SET hitlParked = 1 WHERE runId = :runId AND hitlGateCount > 0")
    suspend fun recordHitlGateParked(runId: String)

    /**
     * Settles the current gate of [runId] with its final resolution. Guarded on a
     * counted gate for the same reason as [recordHitlGateParked].
     *
     * @param runId Id of the run whose gate ended.
     * @param resolution Terminal gate-resolution discriminator.
     */
    @Query(
        "UPDATE trigger_evaluations SET hitlLastResolution = :resolution " +
            "WHERE runId = :runId AND hitlGateCount > 0",
    )
    suspend fun recordHitlGateResolved(runId: String, resolution: String)

    /**
     * Returns the id of the trigger whose journal row carries [runId].
     *
     * Only a firing writes a run id onto its row, so the row is the `FIRED` one
     * that started the run. Served by the `runId` index; a run is started by at
     * most one firing, and `LIMIT 1` keeps the answer single regardless.
     *
     * @param runId Id of the root run a firing started.
     * @return the trigger id, or `null` when no row carries [runId].
     */
    @Query("SELECT triggerId FROM trigger_evaluations WHERE runId = :runId LIMIT 1")
    suspend fun findTriggerIdByRunId(runId: String): String?

    /**
     * Observes a single trigger's journal, newest evaluation first.
     *
     * @param triggerId The trigger whose evaluations to observe.
     * @return A [Flow] emitting the trigger's evaluation rows on every change.
     */
    @Query("SELECT * FROM trigger_evaluations WHERE triggerId = :triggerId ORDER BY evaluatedAt DESC")
    fun observeByTrigger(triggerId: String): Flow<List<TriggerEvaluationEntity>>

    /**
     * Observes the newest evaluation row of **every** trigger — one row per
     * `triggerId`, the one with the greatest `evaluatedAt`. Drives the list
     * health-badge's staleness check (how long since each trigger was last
     * evaluated). A rare `evaluatedAt` tie yields more than one row for a trigger;
     * the caller keys by `triggerId` so the surplus row is harmlessly overwritten.
     *
     * @return A [Flow] emitting the latest row per trigger on every change.
     */
    @Query(
        "SELECT t.* FROM trigger_evaluations t INNER JOIN (" +
            "SELECT triggerId, MAX(evaluatedAt) AS maxAt FROM trigger_evaluations GROUP BY triggerId" +
            ") m ON t.triggerId = m.triggerId AND t.evaluatedAt = m.maxAt",
    )
    fun observeLatestPerTrigger(): Flow<List<TriggerEvaluationEntity>>

    /**
     * Observes the newest **fired** evaluation row of every trigger that has ever
     * fired — one row per `triggerId`, the fired one with the greatest
     * `evaluatedAt`. Drives the health-badge's "last run failed" check: its
     * settled outcome (or lack of one, when the latest fire is still pending) tells
     * whether the most recent run of a trigger errored. Rows of other verdicts are
     * excluded so a later skip / re-arm never masks the last fired outcome.
     *
     * @return A [Flow] emitting the latest fired row per trigger on every change.
     */
    @Query(
        "SELECT t.* FROM trigger_evaluations t INNER JOIN (" +
            "SELECT triggerId, MAX(evaluatedAt) AS maxAt FROM trigger_evaluations " +
            "WHERE verdictKind = 'FIRED' GROUP BY triggerId" +
            ") m ON t.triggerId = m.triggerId AND t.evaluatedAt = m.maxAt WHERE t.verdictKind = 'FIRED'",
    )
    fun observeLatestFiredPerTrigger(): Flow<List<TriggerEvaluationEntity>>

    /**
     * Reads the **entire** journal as a one-shot snapshot, newest evaluation
     * first. Unlike the reactive observers this returns a plain list, so the
     * caller gets a stable point-in-time copy — used by the debug journal dump
     * that a soak run pulls off the device for offline analysis. The whole table
     * is bounded by the retention pass, so an unfiltered read stays modest.
     *
     * @return Every stored row, ordered by [TriggerEvaluationEntity.evaluatedAt]
     *   descending (ties broken by `id` for a deterministic order).
     */
    @Query("SELECT * FROM trigger_evaluations ORDER BY evaluatedAt DESC, id ASC")
    suspend fun getAllOrderedByEvaluatedAt(): List<TriggerEvaluationEntity>

    /**
     * Deletes every record older than [cutoff].
     *
     * @param cutoff Age cutoff, epoch-millis.
     * @return The number of rows deleted.
     */
    @Query("DELETE FROM trigger_evaluations WHERE evaluatedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Trims the table to its newest [maxRecords] rows, deleting the rest.
     *
     * @param maxRecords Hard cap on retained rows.
     * @return The number of rows deleted.
     */
    @Query(
        "DELETE FROM trigger_evaluations WHERE id NOT IN (" +
            "SELECT id FROM trigger_evaluations ORDER BY evaluatedAt DESC LIMIT :maxRecords)",
    )
    suspend fun enforceCap(maxRecords: Int): Int

    /**
     * Applies both retention limits — the age window then the hard cap — in one
     * transaction so the journal can never be observed half-trimmed.
     *
     * @param cutoff Age cutoff, epoch-millis; older rows are deleted first.
     * @param maxRecords Hard cap applied after the age pass.
     * @return The total number of rows deleted across both passes.
     */
    @Transaction
    suspend fun applyRetention(cutoff: Long, maxRecords: Int): Int = deleteOlderThan(cutoff) + enforceCap(maxRecords)
}
