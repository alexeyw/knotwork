package app.knotwork.android.domain.verification

import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunTraceRecord

/**
 * What a check of a finished run will do, decided before it starts: every node
 * visit of the run tree in the order the run made them, and for each either the
 * on-device calls it will repeat or why it will not.
 *
 * The check repeats each recorded call from its own recorded prompt, with its
 * recorded sampler and seed, and compares the answer byte for byte. It never
 * runs the graph again and never runs a tool: a tool's recorded output stands
 * (`decisions.md §75.2` R1).
 *
 * @property rootRunId The run the check is for.
 * @property header The run's header.
 * @property visits Every node visit of the run tree, sub-pipelines in place.
 * @property cloudCalls How many calls went to a cloud model; skipped.
 * @property recordedModelMs How long the calls to repeat took in the run, or
 *   `null` when one of them was recorded without a duration — about what the
 *   check will take.
 */
data class VerificationPlan(
    val rootRunId: String,
    val header: RunHeader,
    val visits: List<PlannedVisit>,
    val cloudCalls: Int,
    val recordedModelMs: Long?,
) {
    /** How many on-device calls the check repeats. */
    val calls: Int get() = visits.sumOf { it.calls.size }
}

/**
 * One node visit of the run, as the check treats it.
 *
 * @property runId The run (root or sub-pipeline) the visit belongs to.
 * @property depth Pipeline-nesting level of that run.
 * @property nodeId The node.
 * @property nodeType The node's type name.
 * @property label The node's label in the pipeline, or its id when the pipeline
 *   no longer has it.
 * @property visit The node's zero-based visit index in its run.
 * @property kind What the check does with the visit.
 * @property calls The on-device calls the check repeats, in order; empty unless
 *   [kind] is [VisitKind.Repeat].
 */
data class PlannedVisit(
    val runId: String,
    val depth: Int,
    val nodeId: String,
    val nodeType: String,
    val label: String,
    val visit: Int,
    val kind: VisitKind,
    val calls: List<RunTraceRecord.LocalModelCall> = emptyList(),
)

/** What the check does with one node visit. */
sealed interface VisitKind {

    /** Its on-device calls are repeated and compared. */
    data object Repeat : VisitKind

    /**
     * It is not checked, for one reason.
     *
     * @property reason Why.
     */
    data class NotVerifiable(val reason: NotVerifiableReason) : VisitKind

    /** A `PIPELINE` visit: a heading for the sub-pipeline run listed after it, with no verdict of its own. */
    data object SubPipeline : VisitKind
}

/** Why a node visit is not checked. */
enum class NotVerifiableReason {
    /** A TOOL node: the tool is not run again, its recorded output stands. */
    TOOL_NOT_EXECUTED,

    /** A cloud model answered: it takes no seed the app controls. */
    CLOUD_MODEL,

    /** The node made no model call. */
    NO_MODEL_CALL,

    /** The call ran on the NPU, which was not measured to repeat. */
    NPU,

    /** The call read an image, which the record does not keep. */
    IMAGE_INPUT,

    /** The run did not record which model file, backend or window the call ran on. */
    MODEL_NOT_RECORDED,
}

/** Whether a run can be checked now, and the plan when it can. */
sealed interface VerifyAvailability {

    /**
     * The check can start.
     *
     * @property plan What it will do. [VerificationPlan.calls] may be `0` — every
     *   on-device call ran on the NPU — and the check then has nothing to repeat.
     */
    data class Available(val plan: VerificationPlan) : VerifyAvailability

    /** The run has not finished. */
    data object Busy : VerifyAvailability

    /** The run was recorded before the app kept seeds and model calls. */
    data object PreVersion : VerifyAvailability

    /** No answer in the run came from the on-device model. */
    data object NoLocalCalls : VerifyAvailability

    /**
     * Something the calls need differs on this device now; nothing is switched by
     * itself (`decisions.md §75.2` R9).
     *
     * @property mismatch What differs.
     */
    data class Mismatch(val mismatch: VerifyMismatch) : VerifyAvailability
}

/** What differs between a recorded call and this device now. */
sealed interface VerifyMismatch {

    /**
     * The app now runs on another backend.
     *
     * @property recorded The backend the run used.
     * @property current The backend the app uses now.
     */
    data class Backend(val recorded: LocalBackend, val current: LocalBackend) : VerifyMismatch

    /**
     * A GPU run's context window differs.
     *
     * @property recorded The window the run used.
     * @property current The window the app uses now.
     */
    data class Window(val recorded: Int, val current: Int) : VerifyMismatch

    /**
     * A GPU run: both the backend and the window differ.
     *
     * @property recordedBackend The backend the run used.
     * @property recordedWindow The window the run used.
     * @property currentBackend The backend the app uses now.
     */
    data class BackendAndWindow(
        val recordedBackend: LocalBackend,
        val recordedWindow: Int,
        val currentBackend: LocalBackend,
    ) : VerifyMismatch

    /**
     * The model file the run used is no longer on the device.
     *
     * @property modelName The model's name.
     */
    data class ModelMissing(val modelName: String) : VerifyMismatch

    /**
     * The file at the model's path is not the one the run used: its checksum differs.
     *
     * @property modelName The model's name.
     */
    data class ModelChanged(val modelName: String) : VerifyMismatch

    /**
     * The model file's checksum is still being computed.
     *
     * @property modelName The model's name.
     */
    data class HashPending(val modelName: String) : VerifyMismatch
}
