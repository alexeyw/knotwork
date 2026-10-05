package app.knotwork.design.components.console

import androidx.compose.runtime.Immutable

/**
 * What the console's run strip shows about the run the console belongs to: one
 * line collapsed, every recorded field, the promise and three actions expanded.
 *
 * Every value arrives pre-formatted — the seed grouped for reading, the
 * temperature as recorded, the window grouped — because the catalog never
 * formats numbers or dates; it only places them into its sentences.
 *
 * @property line The collapsed line.
 * @property detail The expanded fields and the promise.
 * @property actions The three actions at the foot of the expanded header.
 */
@Immutable
data class RunHeaderUi(val line: RunLineUi, val detail: RunDetailUi, val actions: RunActionsUi)

/** The collapsed run line. */
sealed interface RunLineUi {

    /**
     * A run with a seed.
     *
     * @property seed The seed, grouped for reading (`1 482 913`).
     * @property seedDigits The seed as digits, for TalkBack to read as a number.
     * @property backend The backend of the run's first model, or `null` before its first on-device call.
     * @property model The run's first model, or `null` before its first on-device call.
     * @property temperature The sampler's temperature, as recorded.
     * @property withCloud Whether some calls went to a cloud model.
     */
    data class Seeded(
        val seed: String,
        val seedDigits: String,
        val backend: String?,
        val model: String?,
        val temperature: String,
        val withCloud: Boolean,
    ) : RunLineUi

    /** Every answer came from a cloud model. */
    data object CloudOnly : RunLineUi

    /** The run was recorded before runs kept a header. */
    data object PreVersion : RunLineUi
}

/** The expanded header's body. */
sealed interface RunDetailUi {

    /** A run recorded before runs kept a header: one sentence instead of the fields. */
    data object PreVersion : RunDetailUi

    /**
     * A run with a header.
     *
     * @property seed The seed, grouped for reading.
     * @property seedDigits The seed as digits — what copying it takes.
     * @property temperature The sampler's temperature.
     * @property topK The sampler's top-k.
     * @property topP The sampler's top-p.
     * @property models Each model file the on-device calls used; empty for a run
     *   that made none.
     * @property cloud How much of the run a cloud model answered.
     * @property appVersion The app's version name and code.
     * @property runtimeVersion The on-device runtime and its version.
     * @property device The device descriptor.
     * @property digest The full run digest, or `null` while the run is going.
     * @property promise What is promised about repeating the run.
     */
    data class Recorded(
        val seed: String,
        val seedDigits: String,
        val temperature: String,
        val topK: Int,
        val topP: String,
        val models: List<RunModelUi>,
        val cloud: RunCloudUse,
        val appVersion: String,
        val runtimeVersion: String,
        val device: String,
        val digest: String?,
        val promise: RunPromiseUi,
    ) : RunDetailUi
}

/**
 * One model file the run used on the device.
 *
 * @property name The model's name.
 * @property backend The backend its calls ran on.
 * @property window The context window, grouped for reading.
 * @property sha256 The file's full SHA-256 as the run recorded it, or `null` when
 *   the file had not been hashed yet.
 * @property nowTag What the device uses now where it no longer matches the run
 *   (`CPU`, `2 048`), or `null` when it matches.
 */
@Immutable
data class RunModelUi(
    val name: String,
    val backend: String,
    val window: String,
    val sha256: String?,
    val nowTag: String? = null,
)

/** How much of a run a cloud model answered. */
enum class RunCloudUse {
    /** No call went to a cloud model. */
    NONE,

    /** Some calls did; others ran on the device. */
    SOME,

    /** Every model call did. */
    ALL,
}

/** The promise block's wording, one per case. */
enum class RunPromiseUi {
    /** Repeats on CPU. */
    CPU,

    /** Repeats on GPU at the same context window. */
    GPU,

    /** On-device calls repeat on CPU; cloud answers do not. */
    MIXED_CPU,

    /** On-device calls repeat on GPU at the same window; cloud answers do not. */
    MIXED_GPU,

    /** Not promised: the run used the NPU. */
    NPU,

    /** Not promised: every answer came from a cloud model. */
    CLOUD_ONLY,

    /** Not promised: the run read an image. */
    IMAGE,
}

/**
 * The three actions and why each is or is not available.
 *
 * @property busy The run is still going: said once, above three disabled rows.
 * @property verify The check.
 * @property runAgain Starting the run again with its seed.
 * @property export The trace export.
 */
@Immutable
data class RunActionsUi(
    val busy: Boolean,
    val verify: RunVerifyActionUi,
    val runAgain: RunAgainActionUi,
    val export: RunExportActionUi,
)

/** The check's action row. */
sealed interface RunVerifyActionUi {

    /**
     * The check can start.
     *
     * @property calls How many on-device calls it repeats.
     * @property estimate About how long it takes, pre-formatted, or `null` when a
     *   call was recorded without its duration.
     */
    data class Available(val calls: Int, val estimate: String?) : RunVerifyActionUi

    /**
     * The check can start but every on-device call ran on the NPU, so it opens
     * straight to its result.
     *
     * @property calls How many calls ran on the NPU.
     */
    data class NpuOnly(val calls: Int) : RunVerifyActionUi

    /** No answer came from the on-device model. */
    data object NoLocalCalls : RunVerifyActionUi

    /** The run was recorded before seeds. */
    data object PreVersion : RunVerifyActionUi

    /**
     * Something on the device no longer matches the run.
     *
     * @property reason What, naming both values.
     */
    data class Mismatch(val reason: RunMismatchUi) : RunVerifyActionUi
}

/** What no longer matches a run, with both values. */
sealed interface RunMismatchUi {

    /**
     * The backend differs.
     *
     * @property recorded The run's backend.
     * @property now The backend the next load would use.
     */
    data class Backend(val recorded: String, val now: String) : RunMismatchUi

    /**
     * The GPU window differs.
     *
     * @property recorded The run's window.
     * @property now The window the next load would use.
     */
    data class Window(val recorded: String, val now: String) : RunMismatchUi

    /**
     * Both differ.
     *
     * @property recorded The run's backend.
     * @property window The run's window.
     * @property now The backend the next load would use.
     */
    data class BackendAndWindow(val recorded: String, val window: String, val now: String) : RunMismatchUi

    /**
     * The model file is gone.
     *
     * @property model The model's name.
     */
    data class ModelMissing(val model: String) : RunMismatchUi

    /**
     * The model file was replaced.
     *
     * @property model The model's name.
     */
    data class ModelChanged(val model: String) : RunMismatchUi

    /** The model file's checksum is still being computed. */
    data object HashPending : RunMismatchUi
}

/** Where a mismatch is fixed. */
enum class RunSettingsTarget {
    /** The Settings home, where the backend is chosen. */
    SETTINGS,

    /** Settings → Generation, where the context window is set. */
    GENERATION,
}

/** Starting the run again with its seed. */
enum class RunAgainActionUi {
    /** Offered. */
    AVAILABLE,

    /** Not offered: the run read an image. */
    NOT_OFFERED_IMAGE,

    /** Not offered: a trigger or another background surface started the run. */
    NOT_OFFERED_BACKGROUND,

    /** Not offered: no seed was recorded. */
    NOT_OFFERED_PRE_VERSION,

    /** Not offered: the run's pipeline was deleted. */
    NOT_OFFERED_PIPELINE_DELETED,
}

/** The trace export. */
enum class RunExportActionUi {
    /** Header, records, hashes and digest. */
    FULL,

    /** Records only: a run recorded before seeds and hashes. */
    RECORDS_ONLY,
}

/**
 * The confirmation before a long check.
 *
 * @property calls How many on-device calls it repeats.
 * @property seed The run's seed, grouped for reading.
 * @property cloudCalls How many cloud calls it skips.
 * @property estimate About how long it takes, or `null` when unknown.
 */
@Immutable
data class VerifyConfirmUi(val calls: Int, val seed: String, val cloudCalls: Int, val estimate: String?)

/**
 * The confirmation before starting a run again with its seed.
 *
 * @property seed The seed, grouped for reading.
 * @property pipeline The pipeline the new run executes.
 * @property temperature The sampler's temperature.
 * @property topK The sampler's top-k.
 * @property topP The sampler's top-p.
 */
@Immutable
data class RunAgainConfirmUi(
    val seed: String,
    val pipeline: String,
    val temperature: String,
    val topK: Int,
    val topP: String,
)

/**
 * The export sheet.
 *
 * @property fileName The file's name.
 * @property size The file's size, formatted for reading.
 */
@Immutable
data class RunExportUi(val fileName: String, val size: String)

/** Which of a node's two hashes. */
enum class HashKind {
    /** The node's input. */
    INPUT,

    /** The node's output. */
    OUTPUT,
}

/**
 * A node hash the user asked to copy.
 *
 * @property node The node's name as the row shows it.
 * @property kind Input or output.
 * @property sha256 The full hash.
 */
@Immutable
data class ConsoleHashCopy(val node: String, val kind: HashKind, val sha256: String)
