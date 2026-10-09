package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.models.TriggerHealthInputs
import app.knotwork.android.domain.models.TriggerHealthStatus
import app.knotwork.android.domain.models.TriggerRunOutcome
import javax.inject.Inject
import kotlin.math.max

/**
 * Pure derivation of a [Trigger]'s [TriggerHealthStatus] from its journal-derived
 * [TriggerHealthInputs] and the current wall-clock time.
 *
 * Stateless and side-effect-free — it reads no clock and touches no storage of
 * its own; the caller supplies `nowMillis` — so it is fully deterministic and
 * unit-testable. The list ViewModel calls it once per row on every emission of
 * the trigger list, the health-inputs map, or a clock tick.
 *
 * **Precedence.** A trigger can be simultaneously overdue *and* have a failed
 * last run; the badge shows exactly one state, so the order is fixed:
 * 1. [TriggerHealthStatus.STALE] — "not being checked at all" supersedes a past
 *    run result, because a stale trigger is not producing new runs to judge.
 * 2. [TriggerHealthStatus.ERRORED] — the background is alive but the most recent
 *    fired run failed.
 * 3. [TriggerHealthStatus.HEALTHY] — evaluated on cadence and the last run (if
 *    any) succeeded.
 *
 * **Inactive triggers have no health.** A trigger that is unbound
 * ([Trigger.pipelineId] `== null`) *or* disabled is not registered with the
 * background runtime and is never evaluated, so it has no meaningful health
 * signal and this returns `null` for it (the row shows no badge — a deliberately
 * off trigger must not read as "overdue").
 */
class TriggerHealthEvaluator @Inject constructor() {

    /**
     * Derives the health badge state for [trigger].
     *
     * @param trigger The trigger to classify.
     * @param inputs The trigger's collapsed journal facts, or `null` when the
     *   trigger has no journal rows at all (never evaluated yet, or every row aged
     *   out by retention).
     * @param nowMillis Current wall-clock time, epoch-millis.
     * @return The health state, or `null` when [trigger] is inactive (unbound or
     *   disabled) and therefore carries no health signal.
     */
    fun evaluate(trigger: Trigger, inputs: TriggerHealthInputs?, nowMillis: Long): TriggerHealthStatus? {
        // Inactive triggers (unbound or disabled) are never registered with the
        // background runtime, so they are never evaluated and have no health to show.
        if (!trigger.isActive) return null

        // Silence is measured from the last sign of life: the latest evaluation, or
        // the moment the trigger was switched on, whichever is later. A trigger
        // re-enabled after a long pause is not overdue on account of the pause, and
        // one switched on and never polled since becomes overdue after the same
        // grace as any other. With neither (an evaluation-less trigger last switched
        // on before activation was recorded) there is nothing to measure from, and a
        // false "Overdue" erodes the badge more than a missed one: it reads HEALTHY,
        // and the detail screen's empty journal ("not checked yet") says the rest.
        val signOfLife = lastSignOfLife(trigger, inputs) ?: return TriggerHealthStatus.HEALTHY
        if (nowMillis - signOfLife > staleThresholdMillis(trigger.condition)) return TriggerHealthStatus.STALE

        return if (inputs?.latestFiredOutcome.isError()) {
            TriggerHealthStatus.ERRORED
        } else {
            TriggerHealthStatus.HEALTHY
        }
    }

    /**
     * The latest moment [trigger] is known to have been alive: its latest
     * evaluation or its [activation][Trigger.activatedAt], whichever is later.
     *
     * @param trigger The trigger.
     * @param inputs Its journal facts, or `null` when it has no journal rows.
     * @return The moment, or `null` when neither is known.
     */
    fun lastSignOfLife(trigger: Trigger, inputs: TriggerHealthInputs?): Long? =
        listOfNotNull(inputs?.latestEvaluatedAt, trigger.activatedAt).maxOrNull()

    /**
     * The maximum silence, in millis, tolerated before a trigger is considered
     * overdue: its expected evaluation cadence multiplied by [STALE_GRACE_FACTOR].
     * The grace factor absorbs WorkManager's flex window and ordinary Doze
     * maintenance gaps so normal background behaviour is not mistaken for the
     * platform starving the poll.
     */
    private fun staleThresholdMillis(condition: TriggerCondition): Long =
        expectedCadenceMinutes(condition) * STALE_GRACE_FACTOR * MILLIS_PER_MINUTE

    /**
     * The cadence, in minutes, at which the background runtime is expected to
     * evaluate a trigger of this [condition]. Mirrors the periods chosen by the
     * data-layer scheduler:
     * - an interval schedule polls on its own interval, floored at the platform
     *   periodic-work minimum ([MIN_BACKGROUND_CADENCE_MINUTES]),
     * - a daily schedule is evaluated once per day,
     * - event conditions (charging / network) are re-checked by the periodic
     *   watch at the platform floor.
     */
    private fun expectedCadenceMinutes(condition: TriggerCondition): Long = when (condition) {
        is TriggerCondition.IntervalSchedule ->
            max(condition.intervalMinutes, MIN_BACKGROUND_CADENCE_MINUTES)
        is TriggerCondition.DailySchedule -> MINUTES_PER_DAY
        TriggerCondition.Charging, is TriggerCondition.NetworkConnected -> MIN_BACKGROUND_CADENCE_MINUTES
    }

    /**
     * Whether a settled outcome represents a run that did not complete cleanly.
     *
     * [TriggerRunOutcome.StoppedByCeiling] is deliberately **not** an error. A
     * run stopped by the ceiling the user configured is the safety mechanism
     * working, and colouring the trigger's health badge for it would report a
     * working guard as a broken automation — which is precisely the misreading
     * the typed termination reason exists to prevent. The stop is still visible
     * in the journal row; it just does not count against the trigger.
     */
    private fun TriggerRunOutcome?.isError(): Boolean = when (this) {
        null, TriggerRunOutcome.Success, TriggerRunOutcome.StoppedByCeiling -> false
        is TriggerRunOutcome.Failure,
        TriggerRunOutcome.CancelledBySystem,
        TriggerRunOutcome.HitlTimeout,
        TriggerRunOutcome.Cancelled,
        -> true
    }

    /** Tunable health thresholds shared with the health documentation and tests. */
    companion object {
        /**
         * Multiplier applied to a trigger's expected evaluation cadence to obtain
         * the "overdue" threshold. The single product-tunable knob of the health
         * signal: at `2` a trigger reads as overdue only after missing two full
         * expected cadences, which tolerates ordinary Doze flex while still
         * catching a genuinely starved poll.
         */
        const val STALE_GRACE_FACTOR: Long = 2L

        /**
         * The floor expected cadence, in minutes, mirroring the platform periodic
         * WorkManager minimum the scheduler clamps to. Event and short-interval
         * triggers can never be evaluated more often than this.
         */
        const val MIN_BACKGROUND_CADENCE_MINUTES: Long = 15L

        private const val MINUTES_PER_DAY: Long = 24L * 60L
        private const val MILLIS_PER_MINUTE: Long = 60_000L
    }
}
