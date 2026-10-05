package app.knotwork.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.knotwork.android.data.local.models.ModelCallEntity
import app.knotwork.android.data.local.models.TraceStepEntity

/**
 * Data Access Object for the persistent pipeline-run trace: [TraceStepEntity]
 * rows and the [ModelCallEntity] rows that share their `seq` numbering. The write surface is deliberately batch-only: every production
 * insert goes through the buffered run-trace recorder, so a per-row insert
 * path would only invite per-event SQLCipher commits back onto the
 * streaming hot path. Per-session reads and deletes are likewise absent —
 * the trace is queried per run; session-scoped cleanup rides the
 * `chat_sessions` foreign-key cascade, and run-scoped cleanup rides the
 * `pipeline_runs` cascade (the only direct delete here targets legacy
 * pre-run rows, see [deleteLegacyStepsBefore]).
 */
@Dao
interface TraceStepDao {
    /**
     * Inserts a batch of trace records in a single transaction. This is the
     * write path of the buffered run-trace recorder: flushing accumulated
     * records in one statement keeps SQLCipher I/O off the hot inference path.
     *
     * @param steps The records to insert, in their in-run order.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTraceSteps(steps: List<TraceStepEntity>)

    /**
     * Returns the full persisted trace of one pipeline run ordered by the
     * in-run sequence number — the order events were emitted by the engine.
     *
     * @param runId The pipeline run id.
     * @return All trace records of the run, oldest first.
     */
    @Query("SELECT * FROM trace_steps WHERE runId = :runId ORDER BY seq ASC")
    suspend fun getTraceStepsForRun(runId: String): List<TraceStepEntity>

    /**
     * Inserts a batch of model-call records. Called only from [insertBatch], so
     * the calls land in the same transaction as the trace rows buffered with them.
     *
     * @param calls The model calls to insert, in their in-run order.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModelCalls(calls: List<ModelCallEntity>)

    /**
     * Writes one flushed batch of the run-trace recorder — trace rows and model
     * calls — in a single transaction, so a crash never keeps a node's record
     * without the model calls made before it, or the other way round.
     *
     * @param steps The trace rows of the batch.
     * @param calls The model calls of the batch.
     */
    @Transaction
    suspend fun insertBatch(steps: List<TraceStepEntity>, calls: List<ModelCallEntity>) {
        if (steps.isNotEmpty()) insertTraceSteps(steps)
        if (calls.isNotEmpty()) insertModelCalls(calls)
    }

    /**
     * Returns the model calls of one pipeline run ordered by the in-run sequence
     * number; merged with [getTraceStepsForRun] by `seq` into the run's trace.
     *
     * @param runId The pipeline run id.
     * @return The run's model calls, oldest first.
     */
    @Query("SELECT * FROM model_calls WHERE runId = :runId ORDER BY seq ASC")
    suspend fun getModelCallsForRun(runId: String): List<ModelCallEntity>

    /**
     * Retention: deletes legacy trace rows written before run-trace
     * persistence existed (`runId IS NULL`) once they age past [cutoff].
     * Such rows belong to no run, so they are unreachable from any replay
     * path and would otherwise survive until their whole session is deleted.
     * Run-scoped rows are never touched here — they ride the
     * `pipeline_runs` foreign-key cascade when retention deletes their run.
     *
     * @param cutoff Epoch millis; legacy rows older than it are deleted.
     * @return The number of deleted rows.
     */
    @Query("DELETE FROM trace_steps WHERE runId IS NULL AND timestamp < :cutoff")
    suspend fun deleteLegacyStepsBefore(cutoff: Long): Int
}
