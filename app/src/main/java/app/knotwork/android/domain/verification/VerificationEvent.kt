package app.knotwork.android.domain.verification

/**
 * What a running check reports, in order: one [CallChecked] per repeated call,
 * one [VisitSettled] per visit whose calls are all done, and one terminal event —
 * [Finished], [Stopped] or [Failed]. A cancelled check simply ends: the visits
 * settled so far keep their verdicts, the rest were not checked.
 */
sealed interface VerificationEvent {

    /**
     * One call was repeated and compared.
     *
     * @property visitIndex Index of the visit in [VerificationPlan.visits].
     * @property call The call's one-based position among the visit's repeated calls.
     * @property of How many calls the visit repeats.
     * @property matched Whether the answer is byte for byte the recorded one.
     * @property recordedSha256 SHA-256 of the recorded answer.
     * @property replayedSha256 SHA-256 of the repeated answer.
     * @property done Calls repeated so far, this one included.
     * @property total Calls the check repeats.
     */
    data class CallChecked(
        val visitIndex: Int,
        val call: Int,
        val of: Int,
        val matched: Boolean,
        val recordedSha256: String,
        val replayedSha256: String,
        val done: Int,
        val total: Int,
    ) : VerificationEvent

    /**
     * Every call of a visit was repeated.
     *
     * @property visitIndex Index of the visit in [VerificationPlan.visits].
     * @property verdict What the repeats found.
     */
    data class VisitSettled(val visitIndex: Int, val verdict: NodeVerdict) : VerificationEvent

    /**
     * The check ran to the end.
     *
     * @property summary What it found.
     */
    data class Finished(val summary: VerificationSummary) : VerificationEvent

    /**
     * The check stopped before a call because what the call needs changed while
     * it ran — a run loaded another model, or the engine fell back to another
     * backend. Nothing was compared on the wrong model.
     *
     * @property mismatch What differs.
     * @property done Calls repeated before the stop.
     * @property total Calls the check repeats.
     */
    data class Stopped(val mismatch: VerifyMismatch, val done: Int, val total: Int) : VerificationEvent

    /**
     * The on-device model could not load, or a generation failed.
     *
     * @property modelName The model that failed.
     * @property reason The loader's or the engine's reason.
     * @property done Calls repeated before the failure.
     * @property total Calls the check repeats.
     */
    data class Failed(val modelName: String, val reason: String, val done: Int, val total: Int) : VerificationEvent
}

/** What the repeats of one visit found. */
sealed interface NodeVerdict {

    /**
     * Every repeated call gave the recorded answer.
     *
     * @property calls How many calls were repeated.
     */
    data class Matched(val calls: Int) : NodeVerdict

    /**
     * A repeated call gave another answer.
     *
     * @property call The one-based position of the first differing call.
     * @property of How many calls the visit repeats.
     * @property recordedSha256 SHA-256 of the recorded answer.
     * @property replayedSha256 SHA-256 of the repeated answer.
     */
    data class Diverged(val call: Int, val of: Int, val recordedSha256: String, val replayedSha256: String) :
        NodeVerdict
}

/** What a finished check found, for the summary. */
sealed interface VerificationSummary {

    /**
     * Every repeated call matched.
     *
     * @property calls How many calls were repeated.
     * @property notVerifiableVisits How many visits could not be checked; each says why.
     */
    data class AllMatched(val calls: Int, val notVerifiableVisits: Int) : VerificationSummary

    /**
     * Some visits gave other answers.
     *
     * @property divergedVisits How many.
     * @property firstVisitIndex Index of the first in [VerificationPlan.visits].
     * @property call The first differing call's position in that visit.
     * @property of How many calls that visit repeats.
     */
    data class SomeDiverged(val divergedVisits: Int, val firstVisitIndex: Int, val call: Int, val of: Int) :
        VerificationSummary

    /** No call of the run could be repeated; each visit says why. */
    data object NothingVerifiable : VerificationSummary
}
