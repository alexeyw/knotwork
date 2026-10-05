@file:Suppress(
    // Fixture values are the illustration: durations, call counts and hash prefixes read as written.
    "MagicNumber",
)

package app.knotwork.design.screens.chat

import app.knotwork.design.components.console.ConsoleFilter
import app.knotwork.design.components.console.ConsoleSnap
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.ConsoleTraceSpan
import app.knotwork.design.components.console.ConsoleVarRow
import app.knotwork.design.components.console.NotVerifiableUi
import app.knotwork.design.components.console.RunActionsUi
import app.knotwork.design.components.console.RunAgainActionUi
import app.knotwork.design.components.console.RunAgainConfirmUi
import app.knotwork.design.components.console.RunCloudUse
import app.knotwork.design.components.console.RunDetailUi
import app.knotwork.design.components.console.RunExportActionUi
import app.knotwork.design.components.console.RunExportUi
import app.knotwork.design.components.console.RunHeaderUi
import app.knotwork.design.components.console.RunLineUi
import app.knotwork.design.components.console.RunMismatchUi
import app.knotwork.design.components.console.RunModelUi
import app.knotwork.design.components.console.RunPromiseUi
import app.knotwork.design.components.console.RunVerifyActionUi
import app.knotwork.design.components.console.SpanStatus
import app.knotwork.design.components.console.VerdictRowUi
import app.knotwork.design.components.console.VerdictUi
import app.knotwork.design.components.console.VerificationStageUi
import app.knotwork.design.components.console.VerificationUi
import app.knotwork.design.components.console.VerifyConfirmUi

/**
 * Fixtures for the run strip, the check's sheet, the confirmations and the export
 * sheet, over the chat with the console open. Every value is illustrative.
 */
internal object ChatHomeRunPreview {

    private const val SEED = "1 482 913"
    private const val SEED_DIGITS = "1482913"
    private const val MODEL = "Gemma 4 E2B"
    private const val WINDOW = "4 096"
    private const val MODEL_SHA = "3f9a0c2e7b41d85f6a0e93c17d2b4e8f05c6a91b7e3d2f40c8a1b56e9d7f0a23"
    private const val DIGEST = "9c41e07d5a2b8f63e1c04d97b6a35f28c7e910d4b3a6f85e2d0c71b49a8e6f13"

    /** The states of the run strip the design draws. */
    enum class Header {
        LOCAL_CPU,
        GPU,
        MIXED,
        CLOUD_ONLY,
        NPU,
        PRE_VERSION,
        HASH_PENDING,
        ACTIVE,
        SEED_NOT_OFFERED_IMAGE,
        SEED_NOT_OFFERED_TRIGGER,
        VERIFY_MISMATCH,
    }

    /** The stages of the check the design draws. */
    enum class Check { PROGRESS, ALL_MATCHED, MIXED, DIVERGED, NOTHING_VERIFIABLE, CANCELLED, FAILED_LOAD }

    private fun model(backend: String = "CPU", sha: String? = MODEL_SHA, now: String? = null) =
        RunModelUi(name = MODEL, backend = backend, window = WINDOW, sha256 = sha, nowTag = now)

    private fun recorded(
        models: List<RunModelUi> = listOf(model()),
        cloud: RunCloudUse = RunCloudUse.NONE,
        digest: String? = DIGEST,
        promise: RunPromiseUi = RunPromiseUi.CPU,
    ) = RunDetailUi.Recorded(
        seed = SEED,
        seedDigits = SEED_DIGITS,
        temperature = "0.7",
        topK = 40,
        topP = "0.95",
        models = models,
        cloud = cloud,
        appVersion = "0.12.0 (17)",
        runtimeVersion = "LiteRT-LM 0.17.1",
        device = "Google Pixel 8 · Android 16",
        digest = digest,
        promise = promise,
    )

    private fun line(backend: String = "CPU", withCloud: Boolean = false) = RunLineUi.Seeded(
        seed = SEED,
        seedDigits = SEED_DIGITS,
        backend = backend,
        model = MODEL,
        temperature = "0.7",
        withCloud = withCloud,
    )

    private fun actions(
        verify: RunVerifyActionUi = RunVerifyActionUi.Available(calls = 6, estimate = "1 min 35 s"),
        runAgain: RunAgainActionUi = RunAgainActionUi.AVAILABLE,
        export: RunExportActionUi = RunExportActionUi.FULL,
        busy: Boolean = false,
    ) = RunActionsUi(busy = busy, verify = verify, runAgain = runAgain, export = export)

    /** The run strip in [state]. */
    fun header(state: Header): RunHeaderUi = when (state) {
        Header.LOCAL_CPU -> RunHeaderUi(line(), recorded(), actions())
        Header.GPU -> RunHeaderUi(line("GPU"), recorded(listOf(model("GPU")), promise = RunPromiseUi.GPU), actions())
        Header.MIXED -> RunHeaderUi(
            line(withCloud = true),
            recorded(cloud = RunCloudUse.SOME, promise = RunPromiseUi.MIXED_CPU),
            actions(verify = RunVerifyActionUi.Available(calls = 4, estimate = "1 min 31 s")),
        )
        Header.CLOUD_ONLY -> RunHeaderUi(
            RunLineUi.CloudOnly,
            recorded(models = emptyList(), cloud = RunCloudUse.ALL, promise = RunPromiseUi.CLOUD_ONLY),
            actions(verify = RunVerifyActionUi.NoLocalCalls),
        )
        Header.NPU -> RunHeaderUi(
            line("NPU"),
            recorded(listOf(model("NPU")), promise = RunPromiseUi.NPU),
            actions(verify = RunVerifyActionUi.NpuOnly(calls = 6)),
        )
        Header.PRE_VERSION -> RunHeaderUi(
            RunLineUi.PreVersion,
            RunDetailUi.PreVersion,
            actions(
                verify = RunVerifyActionUi.PreVersion,
                runAgain = RunAgainActionUi.NOT_OFFERED_PRE_VERSION,
                export = RunExportActionUi.RECORDS_ONLY,
            ),
        )
        Header.HASH_PENDING -> RunHeaderUi(
            line("GPU"),
            recorded(listOf(model("GPU")), promise = RunPromiseUi.GPU),
            actions(verify = RunVerifyActionUi.Mismatch(RunMismatchUi.HashPending)),
        )
        Header.ACTIVE -> RunHeaderUi(line(), recorded(digest = null), actions(busy = true))
        Header.SEED_NOT_OFFERED_IMAGE -> RunHeaderUi(
            line(),
            recorded(),
            actions(runAgain = RunAgainActionUi.NOT_OFFERED_IMAGE),
        )
        Header.SEED_NOT_OFFERED_TRIGGER -> RunHeaderUi(
            line(),
            recorded(),
            actions(runAgain = RunAgainActionUi.NOT_OFFERED_BACKGROUND),
        )
        Header.VERIFY_MISMATCH -> RunHeaderUi(
            line("GPU"),
            recorded(listOf(model("GPU", now = "CPU")), promise = RunPromiseUi.GPU),
            actions(verify = RunVerifyActionUi.Mismatch(RunMismatchUi.BackendAndWindow("GPU", WINDOW, "CPU"))),
        )
    }

    /** Traces with each node's two hash chips — a sub-pipeline row has none. */
    fun hashedTraces(): List<ConsoleTraceSpan> = listOf(
        span("INTENT_ROUTER", 1_800, "14:01:58.212", 0, "0a77f3d1", "c90b2e14"),
        span("TOOL", 400, "14:01:58.611", 0, "5be2a0c7", "7d14e9b2"),
        span("PIPELINE", 41_300, "14:02:39.902", 0, null, null),
        span("LITE_RT", 38_200, "14:02:36.904", 1, "e2c94f10", "7afd06b4"),
        span("EVALUATION", 2_500, "14:02:39.401", 1, "91aa3e05", "4c0d72f8"),
        span("LITE_RT", 51_000, "14:03:30.410", 0, "b38f6d21", "f5e017c9"),
    )

    private fun span(type: String, ms: Long, at: String, depth: Int, input: String?, output: String?) =
        ConsoleTraceSpan(
            name = type,
            durationMs = ms,
            startedAt = at,
            status = SpanStatus.Ok,
            depth = depth,
            inputSha256 = input?.let { it.padEnd(64, '0') },
            outputSha256 = output?.let { it.padEnd(64, '0') },
        )

    /** Vars with each node's two hash chips under its section header. */
    fun hashedVars(): List<ConsoleVarRow> = listOf(
        varRow("INTENT_ROUTER#route", "output", "\"day_overview\"", 0, "0a77f3d1", "c90b2e14"),
        varRow("LITE_RT#summar", "output", "\"Design review 11:00, vendor call 15:30\"", 1, "e2c94f10", "7afd06b4"),
        varRow("LITE_RT#answer", "output", "\"Two meetings: design review at 11:00…\"", 0, "b38f6d21", "f5e017c9"),
    )

    private fun varRow(node: String, key: String, value: String, depth: Int, input: String, output: String) =
        ConsoleVarRow(
            node = node,
            key = key,
            valueJson = value,
            depth = depth,
            inputSha256 = input.padEnd(64, '0'),
            outputSha256 = output.padEnd(64, '0'),
        )

    /** The chat with the console open at Full on [tab], its run strip in [state]. */
    fun console(
        state: Header = Header.LOCAL_CPU,
        expanded: Boolean = true,
        tab: ConsoleTab = ConsoleTab.Logs,
    ): ChatHomeViewState {
        val base = ChatHomePreview.consoleExpanded()
        return base.copy(
            console = base.console.copy(
                snap = ConsoleSnap.Full,
                tab = tab,
                vars = hashedVars(),
                traces = hashedTraces(),
                filter = ConsoleFilter.allOn,
                runHeader = header(state),
                runHeaderExpanded = expanded,
            ),
        )
    }

    private val nodes = listOf(
        Triple("route", "INTENT_ROUTER", 0),
        Triple("calendar.today", "TOOL", 0),
        Triple("digest", "PIPELINE", 0),
        Triple("summarise events", "LITE_RT", 1),
        Triple("weather.now", "TOOL", 1),
        Triple("check summary", "EVALUATION", 1),
        Triple("answer", "LITE_RT", 0),
    )

    private fun rows(verdicts: List<VerdictUi>): List<VerdictRowUi> =
        nodes.zip(verdicts) { (label, type, depth), verdict -> VerdictRowUi(label, type, depth, verdict) }

    private val tool = VerdictUi.NotVerifiable(NotVerifiableUi.TOOL)
    private val unchecked = VerdictUi.Unchecked

    /** The check's sheet at [stage]. */
    fun verification(stage: Check): VerificationUi {
        val (stageUi, verdicts) = when (stage) {
            Check.PROGRESS -> VerificationStageUi.Running(done = 3, total = 6, left = "55 s") to listOf(
                VerdictUi.Matched(1),
                tool,
                VerdictUi.Group,
                VerdictUi.Matched(2),
                tool,
                VerdictUi.Checking(call = 1, of = 1),
                VerdictUi.Waiting,
            )
            Check.ALL_MATCHED -> VerificationStageUi.AllMatched(calls = 6, notVerifiable = 2) to listOf(
                VerdictUi.Matched(1),
                tool,
                VerdictUi.Group,
                VerdictUi.Matched(2),
                tool,
                VerdictUi.Matched(1),
                VerdictUi.Matched(2),
            )
            Check.MIXED -> VerificationStageUi.AllMatched(calls = 4, notVerifiable = 3) to listOf(
                VerdictUi.Matched(1),
                tool,
                VerdictUi.Group,
                VerdictUi.Matched(2),
                tool,
                VerdictUi.Matched(1),
                VerdictUi.NotVerifiable(NotVerifiableUi.CLOUD),
            )
            Check.DIVERGED -> VerificationStageUi.SomeDiverged(
                nodes = 1,
                firstNode = "summarise events",
                call = 2,
                of = 2,
            ) to listOf(
                VerdictUi.Matched(1),
                tool,
                VerdictUi.Group,
                VerdictUi.Diverged(
                    call = 2,
                    of = 2,
                    recordedSha256 = "7afd06b4".padEnd(64, '1'),
                    replayedSha256 = "ded1b3c2".padEnd(64, '2'),
                ),
                tool,
                VerdictUi.Matched(1),
                VerdictUi.Matched(2),
            )
            Check.NOTHING_VERIFIABLE -> VerificationStageUi.NothingVerifiable to listOf(
                VerdictUi.NotVerifiable(NotVerifiableUi.NPU),
                tool,
                VerdictUi.Group,
                VerdictUi.NotVerifiable(NotVerifiableUi.NPU),
                tool,
                VerdictUi.NotVerifiable(NotVerifiableUi.NPU),
                VerdictUi.NotVerifiable(NotVerifiableUi.NPU),
            )
            Check.CANCELLED -> VerificationStageUi.Cancelled(done = 3, total = 6, allMatched = true) to listOf(
                VerdictUi.Matched(1),
                tool,
                VerdictUi.Group,
                VerdictUi.Matched(2),
                tool,
                unchecked,
                unchecked,
            )
            Check.FAILED_LOAD -> VerificationStageUi.Failed(
                model = MODEL,
                reason = "not enough memory",
            ) to listOf(unchecked, tool, VerdictUi.Group, unchecked, tool, unchecked, unchecked)
        }
        return VerificationUi(
            seed = SEED,
            backend = if (stage == Check.NOTHING_VERIFIABLE) "NPU" else "CPU",
            model = MODEL,
            stage = stageUi,
            rows = rows(verdicts),
        )
    }

    /** The confirmation before a long check; [mixed] adds the skipped cloud calls. */
    fun verifyConfirm(mixed: Boolean = false): VerifyConfirmUi = VerifyConfirmUi(
        calls = if (mixed) 4 else 6,
        seed = SEED,
        cloudCalls = if (mixed) 2 else 0,
        estimate = if (mixed) "1 min 31 s" else "1 min 35 s",
    )

    /** The confirmation before starting the run again with its seed. */
    fun runAgainConfirm(): RunAgainConfirmUi =
        RunAgainConfirmUi(seed = SEED, pipeline = "Morning brief", temperature = "0.7", topK = 40, topP = "0.95")

    /** The export sheet. */
    fun export(): RunExportUi = RunExportUi(fileName = "knotwork-run-trace-20261005-1403.json", size = "48 kB")
}
