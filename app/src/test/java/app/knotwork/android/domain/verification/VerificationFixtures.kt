package app.knotwork.android.domain.verification

import app.knotwork.android.domain.engine.TraceHashing
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.PipelineRun
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.models.RunTraceRecord

/**
 * Records for the verification tests: a finished run with a header, and model
 * calls and node records shaped as the engine writes them.
 */
internal object VerificationFixtures {

    const val ROOT = "run-1"
    const val SESSION = "session-1"
    const val MODEL_PATH = "/m/gemma.litertlm"
    const val MODEL_SHA = "model-sha"

    val HEADER = RunHeader(
        seed = 1_482_913,
        sampler = RunSampler(temperature = 0.7, topK = 40, topP = 0.9),
        appVersion = "test (1)",
        runtimeVersion = "LiteRT-LM test",
        device = "Test Device · Android 16",
    )

    /** A run record, finished and with [HEADER] unless told otherwise. */
    fun run(
        id: String = ROOT,
        status: PipelineRunStatus = PipelineRunStatus.COMPLETED,
        header: RunHeader? = HEADER,
        parentRunId: String? = null,
        pipelineId: String? = "p",
    ) = PipelineRun(
        id = id,
        sessionId = SESSION,
        pipelineId = pipelineId,
        origin = RunOrigin.CHAT,
        status = status,
        currentNodeId = null,
        startedAt = 0L,
        finishedAt = 1L,
        errorMessage = null,
        graphContentHash = "h",
        parentRunId = parentRunId,
        header = header,
    )

    /** An on-device call whose recorded output is [output]. */
    fun localCall(
        seq: Long,
        nodeId: String,
        nodeType: String = "LITE_RT",
        visit: Int = 0,
        call: Int = 0,
        runId: String = ROOT,
        prompt: String = "prompt of $nodeId#$visit.$call",
        output: String = "answer of $nodeId#$visit.$call",
        backend: LocalBackend? = LocalBackend.CPU,
        window: Int? = 4096,
        modelSha: String? = MODEL_SHA,
        hadImage: Boolean = false,
        durationMs: Long? = 1_000L,
    ) = RunTraceRecord.LocalModelCall(
        runId = runId,
        sessionId = SESSION,
        seq = seq,
        timestamp = seq,
        nodeId = nodeId,
        nodeType = nodeType,
        visit = visit,
        call = call,
        depth = 0,
        sampling = LocalSampling(HEADER.sampler, seed = 100 + seq.toInt()),
        modelPath = MODEL_PATH,
        modelSha256 = modelSha,
        backend = backend,
        contextWindow = window,
        hadImage = hadImage,
        prompt = prompt,
        output = output,
        promptSha256 = TraceHashing.sha256Hex(prompt),
        outputSha256 = TraceHashing.sha256Hex(output),
        durationMs = durationMs,
    )

    /** A cloud call's note. */
    fun cloudCall(seq: Long, nodeId: String, nodeType: String = "CLOUD", visit: Int = 0) =
        RunTraceRecord.CloudModelCall(
            runId = ROOT,
            sessionId = SESSION,
            seq = seq,
            timestamp = seq,
            nodeId = nodeId,
            nodeType = nodeType,
            visit = visit,
            call = 0,
            depth = 0,
            provider = "anthropic",
            model = null,
        )

    /** A node's input/output record, hashed as the engine hashes it unless [hashed] is `false`. */
    fun nodeIo(
        seq: Long,
        nodeId: String,
        nodeType: String,
        visit: Int? = 0,
        runId: String = ROOT,
        input: String = "in",
        output: String = "out",
        hashed: Boolean = true,
    ) = RunTraceRecord.NodeIo(
        runId = runId,
        sessionId = SESSION,
        seq = seq,
        timestamp = seq,
        nodeId = nodeId,
        nodeType = nodeType,
        inputText = input,
        outputText = output,
        durationMs = 1L,
        tokenCount = null,
        visit = visit,
        inputSha256 = if (hashed) TraceHashing.sha256Hex(input) else null,
        outputSha256 = if (hashed) TraceHashing.sha256Hex(output) else null,
    )

    /** A console line. */
    fun console(seq: Long, message: String, runId: String = ROOT) = RunTraceRecord.ConsoleEntry(
        runId = runId,
        sessionId = SESSION,
        seq = seq,
        timestamp = seq,
        type = ConsoleEventType.SystemMessage,
        message = message,
    )

    /** A recorded tree of [runs], each run's trace taken from [traces]. */
    fun tree(vararg runs: PipelineRun, traces: Map<String, List<RunTraceRecord>>) = RecordedRunTree(
        root = runs.first(),
        runs = runs.associateBy { it.id },
        traces = traces,
    )
}
