package app.knotwork.android.data.repositories

import androidx.annotation.VisibleForTesting
import app.knotwork.android.data.local.dao.TraceStepDao
import app.knotwork.android.data.local.models.ModelCallEntity
import app.knotwork.android.data.local.models.TraceStepEntity
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.RunTraceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Room-backed, write-buffered implementation of [RunTraceRepository].
 *
 * **Batching.** [append] only adds the record to an in-memory buffer guarded
 * by a [Mutex]; the buffer reaches storage as a single batch insert when one
 * of three triggers fires:
 *
 * 1. **Size** — the buffer holds [FLUSH_SIZE] records (a streaming LLM node
 *    emits console events far faster than SQLCipher should be asked to
 *    commit individual rows);
 * 2. **Timer** — [FLUSH_INTERVAL_MS] elapsed since the first record entered
 *    the empty buffer (bounds trace staleness during quiet phases);
 * 3. **Force** — the engine calls [flush] at suspension and terminal points,
 *    making the persisted trace complete at any moment the run can pause,
 *    end, or the process can be killed right after.
 *
 * Draining and inserting happen under the buffer mutex, so concurrent
 * flush triggers cannot reorder records and `seq` order is preserved in
 * insertion order.
 *
 * **Best-effort contract.** A failed batch insert is retried per run id so a
 * single poisoned run cannot destroy co-buffered records of healthy runs;
 * a group that still fails is logged and dropped — re-buffering a poisoned
 * batch forever would grow the buffer unbounded while the store stays
 * broken. Reads degrade to an empty list; a single unreadable row is skipped
 * rather than discarding the whole trace.
 * `kotlinx.coroutines.CancellationException` always propagates.
 */
@Singleton
class RunTraceRepositoryImpl @Inject constructor(private val traceStepDao: TraceStepDao) : RunTraceRepository {

    /**
     * Dispatcher carrying both DAO calls and the flush timer. Swapped in unit
     * tests; the setter rebuilds [timerScope] so a pending timer never
     * outlives the dispatcher it was scheduled on.
     */
    @VisibleForTesting
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO
        set(value) {
            field = value
            timerScope.cancel()
            timerScope = CoroutineScope(value + SupervisorJob())
        }

    /** Process-lifetime scope hosting the deferred flush timer. */
    private var timerScope = CoroutineScope(dispatcher + SupervisorJob())

    /** Guards [buffer] and [timerJob]; also serializes the drain-and-insert. */
    private val bufferMutex = Mutex()

    /** Records accepted by [append] and not yet written to storage. */
    private val buffer = ArrayDeque<RunTraceRecord>()

    /** Pending deferred-flush timer, `null` when the buffer is empty. */
    private var timerJob: Job? = null

    override suspend fun append(record: RunTraceRecord) {
        bufferMutex.withLock {
            buffer.addLast(record)
            when {
                buffer.size >= FLUSH_SIZE -> drainAndInsertLocked()
                timerJob == null -> timerJob = timerScope.launch {
                    delay(FLUSH_INTERVAL_MS)
                    flush()
                }
            }
        }
    }

    override suspend fun flush() {
        bufferMutex.withLock { drainAndInsertLocked() }
    }

    override suspend fun getTraceForRun(runId: String): List<RunTraceRecord> =
        absorbingStoreFailure({ "Run-trace store failure in getTraceForRun; degrading to empty" }) {
            withContext(dispatcher) {
                // Two tables, one numbering: trace rows and model calls merge back
                // into the run's single `seq`-ordered trace.
                val steps = traceStepDao.getTraceStepsForRun(runId).mapNotNull { it.toRecordOrNull() }
                val calls = traceStepDao.getModelCallsForRun(runId).mapNotNull { it.toRecordOrNull() }
                (steps + calls).sortedBy { it.seq }
            }
        } ?: emptyList()

    override suspend fun deleteLegacyTraceBefore(cutoffEpochMs: Long): Int =
        absorbingStoreFailure({ "Run-trace store failure in deleteLegacyTraceBefore; skipping cleanup" }) {
            withContext(dispatcher) {
                traceStepDao.deleteLegacyStepsBefore(cutoffEpochMs)
            }
        } ?: 0

    /**
     * Drains the buffer and batch-inserts the drained records. Must be called
     * under [bufferMutex]. Cancels the pending timer — the work it was
     * scheduled for is being done right now — unless this call *is* the timer
     * firing: cancelling the calling coroutine would abort the insert below
     * and silently drop the drained batch.
     *
     * **Failure isolation.** The shared buffer interleaves records of every
     * run in the process, and Room inserts the batch in one transaction — a
     * single poisoned run (e.g. its `pipeline_runs` row never landed, so the
     * `runId` foreign key rejects the whole transaction) would otherwise
     * destroy co-buffered records of healthy runs. On a batch failure the
     * drained records are retried per run id, so only the failing run's group
     * is dropped (logged) per the best-effort contract.
     */
    private suspend fun drainAndInsertLocked() {
        val pendingTimer = timerJob
        timerJob = null
        if (pendingTimer != null && pendingTimer != coroutineContext[Job]) {
            pendingTimer.cancel()
        }
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        buffer.clear()
        val batchInserted = absorbingStoreFailure(
            { "Run-trace store failure flushing ${batch.size} records; retrying per run" },
        ) {
            withContext(dispatcher) {
                insertRecords(batch)
            }
        }
        if (batchInserted == null) {
            batch.groupBy { it.runId }.forEach { (runId, group) ->
                absorbingStoreFailure(
                    { "Run-trace store failure flushing ${group.size} records of run $runId; group dropped" },
                ) {
                    withContext(dispatcher) {
                        insertRecords(group)
                    }
                }
            }
        }
    }

    /**
     * Writes [records] in one transaction: model calls to `model_calls`, every
     * other record to `trace_steps`.
     *
     * @param records The records to write, in their in-run order.
     */
    private suspend fun insertRecords(records: List<RunTraceRecord>) {
        val steps = mutableListOf<TraceStepEntity>()
        val calls = mutableListOf<ModelCallEntity>()
        for (record in records) {
            when (record) {
                is RunTraceRecord.LocalModelCall -> calls += record.toEntity()
                is RunTraceRecord.CloudModelCall -> calls += record.toEntity()
                is RunTraceRecord.NodeIo -> steps += record.toTraceStep()
                is RunTraceRecord.ConsoleEntry -> steps += record.toTraceStep()
                is RunTraceRecord.MemorySnapshot -> steps += record.toTraceStep()
            }
        }
        traceStepDao.insertBatch(steps, calls)
    }

    private companion object {
        /** Buffer size that triggers an immediate flush. */
        const val FLUSH_SIZE: Int = 32

        /** Max time a record may sit in the buffer before a deferred flush. */
        const val FLUSH_INTERVAL_MS: Long = 500L
    }
}

/**
 * Maps a node's input/output record to its `trace_steps` row.
 *
 * @return The entity ready for insertion.
 */
private fun RunTraceRecord.NodeIo.toTraceStep(): TraceStepEntity = TraceStepEntity(
    sessionId = sessionId,
    nodeName = nodeType,
    outputText = outputText,
    timestamp = timestamp,
    durationMs = durationMs,
    tokenCount = tokenCount,
    runId = runId,
    seq = seq,
    recordKind = TraceStepEntity.KIND_NODE_IO,
    nodeId = nodeId,
    inputText = inputText,
    conditionResult = conditionResult,
    routingKey = routingKey,
    resolvedToolName = resolvedToolName,
    depth = depth,
    visit = visit,
    inputSha256 = inputSha256,
    outputSha256 = outputSha256,
)

/**
 * Maps a console line to its `trace_steps` row: the message goes into the shared
 * `outputText` payload column and `nodeName` stays empty (a console line is not
 * tied to a node) — see [TraceStepEntity].
 *
 * @return The entity ready for insertion.
 */
private fun RunTraceRecord.ConsoleEntry.toTraceStep(): TraceStepEntity = TraceStepEntity(
    sessionId = sessionId,
    nodeName = "",
    outputText = message,
    timestamp = timestamp,
    runId = runId,
    seq = seq,
    recordKind = TraceStepEntity.KIND_CONSOLE_EVENT,
    consoleEventType = type.toStorageName(),
    depth = depth,
)

/**
 * Maps a memory snapshot to its `trace_steps` row, the chunks serialized into
 * the `outputText` payload column.
 *
 * @return The entity ready for insertion.
 */
private fun RunTraceRecord.MemorySnapshot.toTraceStep(): TraceStepEntity = TraceStepEntity(
    sessionId = sessionId,
    nodeName = "",
    outputText = serializeMemoryEntries(entries),
    timestamp = timestamp,
    runId = runId,
    seq = seq,
    recordKind = TraceStepEntity.KIND_MEMORY_SNAPSHOT,
)

/**
 * Maps a persistence row back to the domain record, or `null` when the row
 * cannot be interpreted (no run attribution, unknown kind or console type) —
 * the read path skips such rows instead of failing the whole trace.
 *
 * @return The domain record, or `null` for an unreadable row.
 */
private fun TraceStepEntity.toRecordOrNull(): RunTraceRecord? {
    val recordRunId = runId ?: return null
    return when (recordKind) {
        TraceStepEntity.KIND_NODE_IO -> RunTraceRecord.NodeIo(
            runId = recordRunId,
            sessionId = sessionId,
            seq = seq,
            timestamp = timestamp,
            nodeId = nodeId.orEmpty(),
            nodeType = nodeName,
            inputText = inputText.orEmpty(),
            outputText = outputText,
            durationMs = durationMs,
            tokenCount = tokenCount,
            conditionResult = conditionResult,
            routingKey = routingKey,
            resolvedToolName = resolvedToolName,
            depth = depth,
            visit = visit,
            inputSha256 = inputSha256,
            outputSha256 = outputSha256,
        )
        TraceStepEntity.KIND_CONSOLE_EVENT -> consoleEventTypeFromStorage(consoleEventType)?.let { type ->
            RunTraceRecord.ConsoleEntry(
                runId = recordRunId,
                sessionId = sessionId,
                seq = seq,
                timestamp = timestamp,
                type = type,
                message = outputText,
                depth = depth,
            )
        }
        TraceStepEntity.KIND_MEMORY_SNAPSHOT -> deserializeMemoryEntries(outputText)?.let { entries ->
            RunTraceRecord.MemorySnapshot(
                runId = recordRunId,
                sessionId = sessionId,
                seq = seq,
                timestamp = timestamp,
                entries = entries,
            )
        } ?: run {
            Timber.w("Skipping memory-snapshot trace row %d with unreadable payload", id)
            null
        }
        else -> {
            Timber.w("Skipping trace row %d with unknown recordKind '%s'", id, recordKind)
            null
        }
    }
}

/**
 * Stable storage name of a [ConsoleEventType] variant. The sealed hierarchy
 * has no `name` property, so the mapping is explicit — renaming a variant
 * must not silently change what is on disk.
 *
 * @return The storage discriminator string.
 */
private fun ConsoleEventType.toStorageName(): String = when (this) {
    ConsoleEventType.NodeExecution -> "NODE_EXECUTION"
    ConsoleEventType.ToolCall -> "TOOL_CALL"
    ConsoleEventType.MemoryAccess -> "MEMORY_ACCESS"
    ConsoleEventType.SystemMessage -> "SYSTEM_MESSAGE"
    ConsoleEventType.Error -> "ERROR"
    ConsoleEventType.StructuredOutputRepair -> "STRUCTURED_OUTPUT_REPAIR"
    ConsoleEventType.CloudRetry -> "CLOUD_RETRY"
    ConsoleEventType.HistoryCompression -> "HISTORY_COMPRESSION"
    ConsoleEventType.RunCeiling -> "RUN_CEILING"
    ConsoleEventType.StuckDetector -> "STUCK_DETECTOR"
}

/**
 * Inverse of [toStorageName].
 *
 * @param name The stored discriminator, possibly `null` or unknown.
 * @return The matching [ConsoleEventType], or `null` when unrecognized.
 */
private fun consoleEventTypeFromStorage(name: String?): ConsoleEventType? = when (name) {
    "NODE_EXECUTION" -> ConsoleEventType.NodeExecution
    "TOOL_CALL" -> ConsoleEventType.ToolCall
    "MEMORY_ACCESS" -> ConsoleEventType.MemoryAccess
    "SYSTEM_MESSAGE" -> ConsoleEventType.SystemMessage
    "ERROR" -> ConsoleEventType.Error
    "STRUCTURED_OUTPUT_REPAIR" -> ConsoleEventType.StructuredOutputRepair
    "CLOUD_RETRY" -> ConsoleEventType.CloudRetry
    "HISTORY_COMPRESSION" -> ConsoleEventType.HistoryCompression
    "RUN_CEILING" -> ConsoleEventType.RunCeiling
    "STUCK_DETECTOR" -> ConsoleEventType.StuckDetector
    else -> {
        Timber.w("Unknown stored console event type '%s'; skipping row", name)
        null
    }
}

/** JSON key of a memory chunk's id inside a `MEMORY_SNAPSHOT` payload. */
private const val MEMORY_JSON_KEY_ID: String = "id"

/** JSON key of a memory chunk's text inside a `MEMORY_SNAPSHOT` payload. */
private const val MEMORY_JSON_KEY_TEXT: String = "text"

/**
 * Serializes the resolved memory chunks of a
 * [RunTraceRecord.MemorySnapshot] into the shared `outputText` payload
 * column as a JSON array of `{id, text}` objects. Only identity and text
 * survive persistence — embeddings are not needed to rebuild the context
 * block on resume and are not worth the storage.
 *
 * @param entries The chunks to serialize, in retrieval-rank order.
 * @return The JSON array string.
 */
private fun serializeMemoryEntries(entries: List<MemoryChunk>): String {
    val array = JSONArray()
    entries.forEach { chunk ->
        array.put(
            JSONObject()
                .put(MEMORY_JSON_KEY_ID, chunk.id)
                .put(MEMORY_JSON_KEY_TEXT, chunk.text),
        )
    }
    return array.toString()
}

/**
 * Inverse of [serializeMemoryEntries]. A chunk restored from a snapshot
 * carries an empty embedding vector and default metadata — resume only needs
 * the text (context block) and the id (identity).
 *
 * @param payload The stored JSON array string.
 * @return The restored chunks, or `null` when the payload is not valid JSON
 *   (the row is skipped by the read path's best-effort contract).
 */
private fun deserializeMemoryEntries(payload: String): List<MemoryChunk>? = try {
    val array = JSONArray(payload)
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        MemoryChunk(
            id = item.getLong(MEMORY_JSON_KEY_ID),
            text = item.getString(MEMORY_JSON_KEY_TEXT),
            embedding = FloatArray(0),
            timestamp = 0L,
        )
    }
} catch (e: JSONException) {
    Timber.w(e, "Failed to parse memory-snapshot payload")
    null
}

/**
 * Maps an on-device model call to its `model_calls` row.
 *
 * @return The row ready for insertion.
 */
private fun RunTraceRecord.LocalModelCall.toEntity(): ModelCallEntity = ModelCallEntity(
    runId = runId,
    sessionId = sessionId,
    seq = seq,
    timestamp = timestamp,
    depth = depth,
    nodeId = nodeId,
    nodeType = nodeType,
    visit = visit,
    callIndex = call,
    engine = ModelCallEntity.ENGINE_LOCAL,
    seed = sampling.seed,
    temperature = sampling.sampler.temperature,
    topK = sampling.sampler.topK,
    topP = sampling.sampler.topP,
    modelPath = modelPath,
    modelSha256 = modelSha256,
    backend = backend?.key,
    contextWindow = contextWindow,
    hadImage = hadImage,
    prompt = prompt,
    output = output,
    promptSha256 = promptSha256,
    outputSha256 = outputSha256,
)

/**
 * Maps a cloud model call to its `model_calls` row; every on-device column stays
 * `null`.
 *
 * @return The row ready for insertion.
 */
private fun RunTraceRecord.CloudModelCall.toEntity(): ModelCallEntity = ModelCallEntity(
    runId = runId,
    sessionId = sessionId,
    seq = seq,
    timestamp = timestamp,
    depth = depth,
    nodeId = nodeId,
    nodeType = nodeType,
    visit = visit,
    callIndex = call,
    engine = ModelCallEntity.ENGINE_CLOUD,
    cloudProvider = provider,
    cloudModel = model,
)

/**
 * Maps a `model_calls` row back to the domain record, or `null` when the row
 * cannot be interpreted — an unknown engine, or an on-device row missing a
 * column every on-device call writes. The read path skips such a row instead of
 * failing the whole trace.
 *
 * @return The domain record, or `null` for an unreadable row.
 */
private fun ModelCallEntity.toRecordOrNull(): RunTraceRecord? = when (engine) {
    ModelCallEntity.ENGINE_LOCAL -> toLocalRecordOrNull()
    ModelCallEntity.ENGINE_CLOUD -> cloudProvider?.let { provider ->
        RunTraceRecord.CloudModelCall(
            runId = runId,
            sessionId = sessionId,
            seq = seq,
            timestamp = timestamp,
            nodeId = nodeId,
            nodeType = nodeType,
            visit = visit,
            call = callIndex,
            depth = depth,
            provider = provider,
            model = cloudModel,
        )
    }
    else -> {
        Timber.w("Skipping model-call row %d with unknown engine '%s'", id, engine)
        null
    }
}

/** The on-device record of this row, or `null` when a column it always has is missing. */
private fun ModelCallEntity.toLocalRecordOrNull(): RunTraceRecord.LocalModelCall? {
    val sampling = samplingOrNull()
    val texts = textsOrNull()
    if (sampling == null || texts == null) {
        Timber.w("Skipping on-device model-call row %d with a missing column", id)
        return null
    }
    return RunTraceRecord.LocalModelCall(
        runId = runId,
        sessionId = sessionId,
        seq = seq,
        timestamp = timestamp,
        nodeId = nodeId,
        nodeType = nodeType,
        visit = visit,
        call = callIndex,
        depth = depth,
        sampling = sampling,
        modelPath = modelPath,
        modelSha256 = modelSha256,
        backend = LocalBackend.fromKey(backend),
        contextWindow = contextWindow,
        hadImage = hadImage,
        prompt = texts.prompt,
        output = texts.output,
        promptSha256 = texts.promptSha256,
        outputSha256 = texts.outputSha256,
    )
}

/** The sampler and seed of an on-device row, or `null` when one of their columns is missing. */
private fun ModelCallEntity.samplingOrNull(): LocalSampling? {
    val temperature = temperature ?: return null
    val topK = topK ?: return null
    val topP = topP ?: return null
    val seed = seed ?: return null
    return LocalSampling(RunSampler(temperature = temperature, topK = topK, topP = topP), seed = seed)
}

/** The texts and hashes of an on-device row, or `null` when one of them is missing. */
private fun ModelCallEntity.textsOrNull(): CallTexts? {
    val prompt = prompt ?: return null
    val output = output ?: return null
    val promptSha256 = promptSha256 ?: return null
    val outputSha256 = outputSha256 ?: return null
    return CallTexts(prompt = prompt, output = output, promptSha256 = promptSha256, outputSha256 = outputSha256)
}

/**
 * What an on-device call read and wrote, with the hashes of both.
 *
 * @property prompt The full prompt.
 * @property output The full output.
 * @property promptSha256 SHA-256 of [prompt].
 * @property outputSha256 SHA-256 of [output].
 */
private data class CallTexts(
    val prompt: String,
    val output: String,
    val promptSha256: String,
    val outputSha256: String,
)
