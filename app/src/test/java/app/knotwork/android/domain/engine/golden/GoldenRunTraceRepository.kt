package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.ResumeContext
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.RunTraceRepository

/**
 * In-memory persistent run trace of the golden harness; records every append and flush.
 *
 * The persisted trace is the part of the normal form the task names first — sequence number,
 * node, input, output, routing verdicts, tool name, console events — and the one later work
 * extends (hashes per `NodeIo`). Two things are left out of the rendering because they depend
 * on the clock: every record's timestamp and `NodeIo.durationMs`, and the duration in the
 * `✓ TYPE in Nms` console line, which is masked. Any other number left in that line fails the
 * run rather than slipping a wall-clock value into a golden file.
 *
 * The store also rebuilds a run's checkpoint exactly as the task queue does, so a resumed
 * scenario replays the records the interrupted attempt wrote.
 *
 * @param log The run's event log.
 */
internal class GoldenRunTraceRepository(private val log: GoldenEventLog) : RunTraceRepository {

    private val records = mutableListOf<RunTraceRecord>()

    override suspend fun append(record: RunTraceRecord) {
        records += record
        when (record) {
            is RunTraceRecord.NodeIo -> log.record(
                buildString {
                    append("trace.node seq=${record.seq} run=${record.runId} ${record.nodeId} ${record.nodeType}")
                    append(" depth=${record.depth} tokens=${record.tokenCount}")
                    record.conditionResult?.let { append(" condition=$it") }
                    record.routingKey?.let { append(" routingKey=$it") }
                    record.resolvedToolName?.let { append(" tool=$it") }
                },
                "input" to record.inputText,
                "output" to record.outputText,
            )
            is RunTraceRecord.ConsoleEntry -> log.record(
                "trace.console seq=${record.seq} run=${record.runId} depth=${record.depth} ${record.type}",
                "message" to maskDuration(record.type, record.message),
            )
            is RunTraceRecord.MemorySnapshot -> log.record(
                "trace.memory seq=${record.seq} run=${record.runId} chunks=${record.entries.map { it.id }}",
            )
        }
    }

    override suspend fun flush() {
        log.record("trace.flush")
    }

    override suspend fun getTraceForRun(runId: String): List<RunTraceRecord> = records.filter { it.runId == runId }

    override suspend fun deleteLegacyTraceBefore(cutoffEpochMs: Long): Int =
        error("RunTraceRepository.deleteLegacyTraceBefore is not on any golden run path")

    /**
     * Rebuilds the checkpoint of [runId] from its persisted records, as
     * `TaskQueueManagerImpl.processResumeTask` does: the seq-ordered `NodeIo` prefix, the
     * latest memory snapshot, and the first free sequence number.
     *
     * @param runId The run to resume.
     * @return The checkpoint.
     */
    fun resumeContextFor(runId: String): ResumeContext {
        val trace = records.filter { it.runId == runId }
        return ResumeContext(
            records = trace.filterIsInstance<RunTraceRecord.NodeIo>().sortedBy { it.seq },
            memorySnapshot = trace.filterIsInstance<RunTraceRecord.MemorySnapshot>().maxByOrNull { it.seq }?.entries,
            nextSeq = (trace.maxOfOrNull { it.seq } ?: -1L) + 1,
        )
    }

    private fun maskDuration(type: ConsoleEventType, message: String): String {
        if (type != ConsoleEventType.NodeExecution) return message
        val masked = message.replace(DURATION, " in <ms>ms")
        check(!RESIDUAL_DURATION.containsMatchIn(masked)) {
            "A NodeExecution console line carries a duration the golden mask does not recognise: $message"
        }
        return masked
    }

    private companion object {
        /** The duration the engine appends to a node's completion line (`✓ LITE_RT in 512ms`). */
        val DURATION = Regex(" in \\d+ms$")

        /** Any millisecond figure left after masking. */
        val RESIDUAL_DURATION = Regex("\\d+\\s?ms")
    }
}
