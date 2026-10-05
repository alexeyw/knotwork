package app.knotwork.design.components.console

import androidx.compose.runtime.Immutable

/**
 * What the check's sheet shows: the run it checks, where the check stands, and
 * every node visit of the run in run order with its verdict.
 *
 * @property seed The run's seed, grouped for reading.
 * @property backend The backend the check runs on.
 * @property model The model it runs.
 * @property stage Where the check stands.
 * @property rows Every node visit, sub-pipelines in place.
 */
@Immutable
data class VerificationUi(
    val seed: String,
    val backend: String,
    val model: String,
    val stage: VerificationStageUi,
    val rows: List<VerdictRowUi>,
)

/** Where the check stands: running, or one of its endings. */
sealed interface VerificationStageUi {

    /**
     * Calls are being repeated.
     *
     * @property done Calls repeated so far.
     * @property total Calls to repeat.
     * @property left About how long is left, or `null` when unknown.
     */
    data class Running(val done: Int, val total: Int, val left: String?) : VerificationStageUi

    /**
     * Every repeated call matched.
     *
     * @property calls Calls repeated.
     * @property notVerifiable Visits that could not be checked.
     */
    data class AllMatched(val calls: Int, val notVerifiable: Int) : VerificationStageUi

    /**
     * Some visits gave a different answer.
     *
     * @property nodes How many visits differed.
     * @property firstNode The first that differed.
     * @property call Its first differing call, one-based.
     * @property of Its call count.
     */
    data class SomeDiverged(val nodes: Int, val firstNode: String, val call: Int, val of: Int) : VerificationStageUi

    /** No call could be repeated. */
    data object NothingVerifiable : VerificationStageUi

    /**
     * The user stopped the check.
     *
     * @property done Calls repeated before.
     * @property total Calls there were.
     * @property allMatched Whether every call repeated so far matched.
     */
    data class Cancelled(val done: Int, val total: Int, val allMatched: Boolean) : VerificationStageUi

    /**
     * Something changed the loaded model, backend or window during the check.
     *
     * @property done Calls repeated before.
     * @property total Calls there were.
     * @property reason What changed, with both values.
     */
    data class Stopped(val done: Int, val total: Int, val reason: RunMismatchUi) : VerificationStageUi

    /**
     * The model did not load.
     *
     * @property model The model.
     * @property reason The loader's reason.
     */
    data class Failed(val model: String, val reason: String) : VerificationStageUi
}

/**
 * One node visit of the checked run.
 *
 * @property label The node's label.
 * @property type The node's type name.
 * @property depth The sub-pipeline depth.
 * @property verdict What the check made of it.
 */
@Immutable
data class VerdictRowUi(val label: String, val type: String, val depth: Int, val verdict: VerdictUi)

/** A node visit's verdict. */
sealed interface VerdictUi {

    /** A sub-pipeline heading: its nodes carry the verdicts. */
    data object Group : VerdictUi

    /** Not reached yet. */
    data object Waiting : VerdictUi

    /** Not checked: the check was cancelled or could not load the model. */
    data object Unchecked : VerdictUi

    /**
     * Being repeated.
     *
     * @property call The call being repeated, one-based.
     * @property of The visit's call count.
     */
    data class Checking(val call: Int, val of: Int) : VerdictUi

    /**
     * Every call matched.
     *
     * @property calls The visit's call count.
     */
    data class Matched(val calls: Int) : VerdictUi

    /**
     * A call gave a different answer.
     *
     * @property call The first differing call, one-based.
     * @property of The visit's call count.
     * @property recordedSha256 The recorded answer's hash.
     * @property replayedSha256 The repeated answer's hash.
     */
    data class Diverged(val call: Int, val of: Int, val recordedSha256: String, val replayedSha256: String) :
        VerdictUi

    /**
     * Not repeated, for one reason.
     *
     * @property reason Why.
     */
    data class NotVerifiable(val reason: NotVerifiableUi) : VerdictUi
}

/** Why a node visit is not repeated. */
enum class NotVerifiableUi {
    /** A tool: its recorded output stands. */
    TOOL,

    /** A cloud model answered. */
    CLOUD,

    /** The node made no model call. */
    NO_CALL,

    /** The call ran on the NPU. */
    NPU,

    /** The call read an image. */
    IMAGE,

    /** The model file had no checksum when the call ran. */
    MODEL_NOT_RECORDED,
}
