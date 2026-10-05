package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.verification.NotPromisedReason
import app.knotwork.android.domain.verification.NotVerifiableReason
import app.knotwork.android.domain.verification.Reproducibility
import app.knotwork.android.domain.verification.RunAgainAvailability
import app.knotwork.android.domain.verification.RunAgainNotOfferedReason
import app.knotwork.android.domain.verification.RunDescription
import app.knotwork.android.domain.verification.RunModelUse
import app.knotwork.android.domain.verification.VerificationPlan
import app.knotwork.android.domain.verification.VerifyAvailability
import app.knotwork.android.domain.verification.VerifyMismatch
import app.knotwork.android.domain.verification.VisitKind
import app.knotwork.design.components.console.NotVerifiableUi
import app.knotwork.design.components.console.RunActionsUi
import app.knotwork.design.components.console.RunAgainActionUi
import app.knotwork.design.components.console.RunCloudUse
import app.knotwork.design.components.console.RunDetailUi
import app.knotwork.design.components.console.RunExportActionUi
import app.knotwork.design.components.console.RunHeaderUi
import app.knotwork.design.components.console.RunLineUi
import app.knotwork.design.components.console.RunMismatchUi
import app.knotwork.design.components.console.RunModelUi
import app.knotwork.design.components.console.RunPromiseUi
import app.knotwork.design.components.console.RunVerifyActionUi
import app.knotwork.design.components.console.VerdictRowUi
import app.knotwork.design.components.console.VerdictUi
import java.math.BigDecimal

/**
 * How the run strip writes a run: numbers grouped and rounded for reading — seeds
 * and windows in groups, temperatures as recorded, durations as a person estimates
 * them — with, beside it in this file, the projection of a run's description onto
 * the strip and the rule for when a check asks first.
 */
internal object RunDisplay {

    /** A narrow no-break space between digit groups, so `1 482 913` never wraps inside. */
    private const val GROUP_SEPARATOR = ' '

    private const val DIGIT_GROUP = 3
    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L

    /**
     * A non-negative integer in groups of three: `1482913` → `1 482 913`.
     *
     * @param value The number.
     * @return Its grouped digits.
     */
    fun grouped(value: Int): String = value.toString().reversed().chunked(DIGIT_GROUP)
        .joinToString(separator = GROUP_SEPARATOR.toString()).reversed()

    /**
     * A sampler value as recorded, without trailing zeros: `0.7`, `0.95`, `1`.
     *
     * @param value The value.
     * @return Its shortest decimal form.
     */
    fun decimal(value: Double): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    /**
     * About how long [ms] is: seconds under a minute, else minutes and seconds.
     *
     * @param ms The duration.
     * @return `55 s`, `1 min 35 s` or `2 min`.
     */
    fun duration(ms: Long): String {
        val seconds = ((ms + MS_PER_SECOND / 2) / MS_PER_SECOND).coerceAtLeast(1L)
        val minutes = seconds / SECONDS_PER_MINUTE
        val rest = seconds % SECONDS_PER_MINUTE
        return when {
            minutes == 0L -> "$seconds s"
            rest == 0L -> "$minutes min"
            else -> "$minutes min $rest s"
        }
    }
}

/** A long check asks first: more than this many on-device calls… */
internal const val VERIFY_CONFIRM_CALLS = 3

/** …or at least this much recorded model time. */
internal const val VERIFY_CONFIRM_MS = 30_000L

/**
 * Projects a run's description onto the run strip.
 *
 * @receiver What the run header shows.
 * @return The strip's line, fields and actions.
 */
internal fun RunDescription.toRunHeaderUi(): RunHeaderUi {
    val header = run.header
    val nowTag = (verify as? VerifyAvailability.Mismatch)?.mismatch?.nowTag()
    val models = models.mapIndexed { index, use -> use.toUi(nowTag.takeIf { index == 0 }) }
    val cloud = when {
        cloudCalls == 0 -> RunCloudUse.NONE
        localCalls == 0 -> RunCloudUse.ALL
        else -> RunCloudUse.SOME
    }
    val line = when {
        header == null -> RunLineUi.PreVersion
        cloud == RunCloudUse.ALL -> RunLineUi.CloudOnly
        else -> RunLineUi.Seeded(
            seed = RunDisplay.grouped(header.seed),
            seedDigits = header.seed.toString(),
            backend = models.firstOrNull()?.backend,
            model = models.firstOrNull()?.name,
            temperature = RunDisplay.decimal(header.sampler.temperature),
            withCloud = cloud == RunCloudUse.SOME,
        )
    }
    val detail = if (header == null) {
        RunDetailUi.PreVersion
    } else {
        RunDetailUi.Recorded(
            seed = RunDisplay.grouped(header.seed),
            seedDigits = header.seed.toString(),
            temperature = RunDisplay.decimal(header.sampler.temperature),
            topK = header.sampler.topK,
            topP = RunDisplay.decimal(header.sampler.topP),
            models = models,
            cloud = cloud,
            appVersion = header.appVersion,
            runtimeVersion = header.runtimeVersion,
            device = header.device,
            digest = digest,
            promise = promise(cloud),
        )
    }
    return RunHeaderUi(line = line, detail = detail, actions = actions())
}

/** The model row of one file the run used; [nowTag] marks the first row when it no longer matches. */
private fun RunModelUse.toUi(nowTag: String?): RunModelUi = RunModelUi(
    name = name ?: UNKNOWN,
    backend = backend?.name ?: UNKNOWN,
    window = contextWindow?.let(RunDisplay::grouped) ?: UNKNOWN,
    sha256 = sha256,
    nowTag = nowTag,
)

/** What the device uses now, for the tag on the field that no longer matches; `null` for a file mismatch. */
private fun VerifyMismatch.nowTag(): String? = when (this) {
    is VerifyMismatch.Backend -> current.name
    is VerifyMismatch.Window -> RunDisplay.grouped(current)
    is VerifyMismatch.BackendAndWindow -> currentBackend.name
    is VerifyMismatch.ModelMissing, is VerifyMismatch.ModelChanged, is VerifyMismatch.HashPending -> null
}

/** The promise block's case. */
private fun RunDescription.promise(cloud: RunCloudUse): RunPromiseUi = when (val promise = reproducibility) {
    is Reproducibility.Promised -> {
        val gpu = LocalBackend.GPU in promise.backends
        when {
            cloud == RunCloudUse.SOME && gpu -> RunPromiseUi.MIXED_GPU
            cloud == RunCloudUse.SOME -> RunPromiseUi.MIXED_CPU
            gpu -> RunPromiseUi.GPU
            else -> RunPromiseUi.CPU
        }
    }
    is Reproducibility.NotPromised -> when (promise.reason) {
        NotPromisedReason.NPU -> RunPromiseUi.NPU
        NotPromisedReason.CLOUD_ONLY -> RunPromiseUi.CLOUD_ONLY
        NotPromisedReason.IMAGE -> RunPromiseUi.IMAGE
        // A run without a header shows one sentence instead of the promise.
        NotPromisedReason.PRE_VERSION -> RunPromiseUi.CPU
    }
}

/** The three actions. */
private fun RunDescription.actions(): RunActionsUi = RunActionsUi(
    busy = !run.status.isTerminal,
    verify = when (val verify = verify) {
        is VerifyAvailability.Available -> verify.plan.toAction(localCalls)
        VerifyAvailability.Busy -> RunVerifyActionUi.PreVersion
        VerifyAvailability.PreVersion -> RunVerifyActionUi.PreVersion
        VerifyAvailability.NoLocalCalls -> RunVerifyActionUi.NoLocalCalls
        is VerifyAvailability.Mismatch -> RunVerifyActionUi.Mismatch(verify.mismatch.toUi())
    },
    runAgain = when (val again = runAgain) {
        is RunAgainAvailability.Available, RunAgainAvailability.Busy -> RunAgainActionUi.AVAILABLE
        is RunAgainAvailability.NotOffered -> when (again.reason) {
            RunAgainNotOfferedReason.IMAGE -> RunAgainActionUi.NOT_OFFERED_IMAGE
            RunAgainNotOfferedReason.BACKGROUND_ORIGIN -> RunAgainActionUi.NOT_OFFERED_BACKGROUND
            RunAgainNotOfferedReason.PRE_VERSION -> RunAgainActionUi.NOT_OFFERED_PRE_VERSION
            RunAgainNotOfferedReason.PIPELINE_DELETED -> RunAgainActionUi.NOT_OFFERED_PIPELINE_DELETED
        }
    },
    export = if (run.header == null) RunExportActionUi.RECORDS_ONLY else RunExportActionUi.FULL,
)

/** The check's row for an available plan: the calls it repeats, or the NPU-only line. */
private fun VerificationPlan.toAction(localCalls: Int): RunVerifyActionUi = if (calls == 0) {
    RunVerifyActionUi.NpuOnly(calls = localCalls)
} else {
    RunVerifyActionUi.Available(calls = calls, estimate = recordedModelMs?.let(RunDisplay::duration))
}

/**
 * A mismatch with both values written for reading.
 *
 * @receiver The mismatch.
 * @return Its catalog form.
 */
internal fun VerifyMismatch.toUi(): RunMismatchUi = when (this) {
    is VerifyMismatch.Backend -> RunMismatchUi.Backend(recorded.name, current.name)
    is VerifyMismatch.Window -> RunMismatchUi.Window(RunDisplay.grouped(recorded), RunDisplay.grouped(current))
    is VerifyMismatch.BackendAndWindow ->
        RunMismatchUi.BackendAndWindow(recordedBackend.name, RunDisplay.grouped(recordedWindow), currentBackend.name)
    is VerifyMismatch.ModelMissing -> RunMismatchUi.ModelMissing(modelName)
    is VerifyMismatch.ModelChanged -> RunMismatchUi.ModelChanged(modelName)
    is VerifyMismatch.HashPending -> RunMismatchUi.HashPending
}

/**
 * Whether a check of [plan] asks first: more than three on-device calls, or 30 s
 * or more of recorded model time.
 *
 * @param plan The check's plan.
 * @return `true` when the confirmation is shown.
 */
internal fun asksBeforeVerifying(plan: VerificationPlan): Boolean =
    plan.calls > VERIFY_CONFIRM_CALLS || (plan.recordedModelMs ?: 0L) >= VERIFY_CONFIRM_MS

/**
 * The check's rows before it starts: each visit waiting, not verifiable with its
 * reason, or a sub-pipeline heading.
 *
 * @param plan The check's plan.
 * @return One row per visit, in run order.
 */
internal fun initialVerdictRows(plan: VerificationPlan): List<VerdictRowUi> = plan.visits.map { visit ->
    VerdictRowUi(
        label = visit.label,
        type = visit.nodeType,
        depth = visit.depth,
        verdict = when (val kind = visit.kind) {
            VisitKind.Repeat -> VerdictUi.Waiting
            VisitKind.SubPipeline -> VerdictUi.Group
            is VisitKind.NotVerifiable -> VerdictUi.NotVerifiable(kind.reason.toUi())
        },
    )
}

/** A reason a visit is not repeated, in the catalog's terms. */
private fun NotVerifiableReason.toUi(): NotVerifiableUi = when (this) {
    NotVerifiableReason.TOOL_NOT_EXECUTED -> NotVerifiableUi.TOOL
    NotVerifiableReason.CLOUD_MODEL -> NotVerifiableUi.CLOUD
    NotVerifiableReason.NO_MODEL_CALL -> NotVerifiableUi.NO_CALL
    NotVerifiableReason.NPU -> NotVerifiableUi.NPU
    NotVerifiableReason.IMAGE_INPUT -> NotVerifiableUi.IMAGE
    NotVerifiableReason.MODEL_NOT_RECORDED -> NotVerifiableUi.MODEL_NOT_RECORDED
}

/** Shown where the record has no value: a call recorded without its file, backend or window. */
private const val UNKNOWN = "—"
