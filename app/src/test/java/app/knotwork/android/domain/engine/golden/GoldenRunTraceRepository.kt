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
 * **Durability is modelled, not assumed.** The production store buffers appends and reads
 * back only what reached the database, so here too an append is durable only after the next
 * [flush], [getTraceForRun] returns durable records only, and the harness drops whatever is
 * still unflushed when an attempt ends — the process can die while a run is parked. A
 * refactoring that lost a flush before a suspension therefore changes what the resumed run
 * replays, instead of changing one line.
 *
 * @param log The run's event log.
 */
internal class GoldenRunTraceRepository(private val log: GoldenEventLog) : RunTraceRepository {

    private val durable = mutableListOf<RunTraceRecord>()
    private val buffered = mutableListOf<RunTraceRecord>()

    override suspend fun append(record: RunTraceRecord) {
        buffered += record
        when (record) {
            is RunTraceRecord.NodeIo -> log.record(
                buildString {
                    append("trace.node seq=${record.seq} run=${record.runId} ${record.nodeId} ${record.nodeType}")
                    append(" depth=${record.depth} visit=${record.visit} tokens=${record.tokenCount}")
                    record.conditionResult?.let { append(" condition=$it") }
                    record.routingKey?.let { append(" routingKey=$it") }
                    record.resolvedToolName?.let { append(" tool=$it") }
                    append(" in=${record.inputSha256.short()} out=${record.outputSha256.short()}")
                },
                "input" to record.inputText,
                "output" to record.outputText,
            )
            // The texts are in the `model local` event of the same call; the record pins what
            // was kept of it — sampling, model file, backend, window — and the hashes.
            is RunTraceRecord.LocalModelCall -> log.record(
                buildString {
                    append("trace.model.local seq=${record.seq} run=${record.runId} ${record.nodeId} ")
                    append("${record.nodeType} visit=${record.visit} call=${record.call} depth=${record.depth} ")
                    append("seed=${record.sampling.seed} t=${record.sampling.sampler.temperature} ")
                    append("k=${record.sampling.sampler.topK} p=${record.sampling.sampler.topP} ")
                    append("model=${record.modelPath} sha=${record.modelSha256.short()} ")
                    append("backend=${record.backend} window=${record.contextWindow} image=${record.hadImage} ")
                    append("prompt=${record.promptSha256.short()} output=${record.outputSha256.short()}")
                },
            )
            is RunTraceRecord.CloudModelCall -> log.record(
                "trace.model.cloud seq=${record.seq} run=${record.runId} ${record.nodeId} ${record.nodeType} " +
                    "visit=${record.visit} call=${record.call} depth=${record.depth} " +
                    "provider=${record.provider} model=${record.model}",
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

    /** The first twelve hex digits of a hash: enough to show any change, short enough to read. */
    private fun String?.short(): String = this?.take(SHORT_HASH) ?: "none"

    override suspend fun flush() {
        durable += buffered
        buffered.clear()
        log.record("trace.flush")
    }

    override suspend fun getTraceForRun(runId: String): List<RunTraceRecord> = durable.filter { it.runId == runId }

    override suspend fun deleteLegacyTraceBefore(cutoffEpochMs: Long): Int =
        log.violation("RunTraceRepository.deleteLegacyTraceBefore is not on any golden run path")

    /**
     * Forgets the records no flush made durable, as a process that dies while its run is
     * parked would. Records the loss only when there is one.
     */
    fun dropUnflushed() {
        if (buffered.isEmpty()) return
        log.record("trace.lost unflushed=${buffered.size} (the attempt ended before they were flushed)")
        buffered.clear()
    }

    /**
     * The most recent `NodeIo` appended for [nodeId] at [depth], flushed or not.
     *
     * @param nodeId The node.
     * @param depth Its nesting depth.
     * @return The record, or `null`.
     */
    fun lastNodeIo(nodeId: String, depth: Int): RunTraceRecord.NodeIo? = (durable + buffered)
        .filterIsInstance<RunTraceRecord.NodeIo>()
        .lastOrNull { it.nodeId == nodeId && it.depth == depth }

    /**
     * The most recent `NodeIo` of [nodeType] at [depth] whose output is [outputText], flushed or
     * not.
     *
     * @param nodeType The node type name.
     * @param depth The nesting depth.
     * @param outputText The output to match.
     * @return The record, or `null`.
     */
    fun matchingNodeIo(nodeType: String, depth: Int, outputText: String): RunTraceRecord.NodeIo? = (durable + buffered)
        .filterIsInstance<RunTraceRecord.NodeIo>()
        .lastOrNull { it.nodeType == nodeType && it.depth == depth && it.outputText == outputText }

    /**
     * The most recent console record with sequence number [seq] at [depth], flushed or not.
     *
     * @param seq The console event's sequence number within its run.
     * @param depth Its nesting depth.
     * @return The record, or `null`.
     */
    fun consoleEntry(seq: Long, depth: Int): RunTraceRecord.ConsoleEntry? = (durable + buffered)
        .filterIsInstance<RunTraceRecord.ConsoleEntry>()
        .lastOrNull { it.seq == seq && it.depth == depth }

    /**
     * Rebuilds the checkpoint of [runId] from its durable records, as
     * `TaskQueueManagerImpl.processResumeTask` does: the seq-ordered `NodeIo` prefix, the
     * latest memory snapshot, and the first free sequence number.
     *
     * @param runId The run to resume.
     * @return The checkpoint.
     */
    fun resumeContextFor(runId: String): ResumeContext {
        val trace = durable.filter { it.runId == runId }
        return ResumeContext(
            records = trace.filterIsInstance<RunTraceRecord.NodeIo>().sortedBy { it.seq },
            memorySnapshot = trace.filterIsInstance<RunTraceRecord.MemorySnapshot>().maxByOrNull { it.seq }?.entries,
            nextSeq = (trace.maxOfOrNull { it.seq } ?: -1L) + 1,
        )
    }

    private fun maskDuration(type: ConsoleEventType, message: String): String {
        if (type != ConsoleEventType.NodeExecution) return message
        val masked = message.replace(DURATION, " in <ms>ms")
        if (RESIDUAL_DURATION.containsMatchIn(masked)) {
            log.violation(
                "A NodeExecution console line carries a duration the golden mask does not recognise: $message",
            )
        }
        return masked
    }

    private companion object {
        /** The duration the engine appends to a node's completion line (`✓ LITE_RT in 512ms`). */
        val DURATION = Regex(" in \\d+ms$")

        /** Any millisecond figure left after masking. */
        val RESIDUAL_DURATION = Regex("\\d+\\s?ms")

        /** Hex digits of a hash the trace shows. */
        const val SHORT_HASH = 12
    }
}
