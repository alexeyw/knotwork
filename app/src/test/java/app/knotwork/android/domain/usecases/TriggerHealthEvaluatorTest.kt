package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerCondition
import app.knotwork.android.domain.models.TriggerHealthInputs
import app.knotwork.android.domain.models.TriggerHealthStatus
import app.knotwork.android.domain.models.TriggerRunOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [TriggerHealthEvaluator]: the pure derivation of a trigger's
 * health badge from its journal facts and the current time, covering the inactive
 * short-circuits, the staleness threshold per condition, the last-run-error
 * signal, and the stale-over-errored precedence.
 */
class TriggerHealthEvaluatorTest {

    private val evaluator = TriggerHealthEvaluator()

    private val now = 1_000_000_000L
    private val minute = 60_000L

    private fun trigger(
        condition: TriggerCondition = TriggerCondition.IntervalSchedule(30L),
        pipelineId: String? = "pipeline-1",
        enabled: Boolean = true,
        createdAt: Long = now - 10L * 24 * 60 * minute,
        activatedAt: Long? = null,
    ): Trigger = Trigger(
        id = "trig-1",
        name = "Inbox triage",
        condition = condition,
        pipelineId = pipelineId,
        prompt = "",
        enabled = enabled,
        createdAt = createdAt,
        activatedAt = activatedAt,
    )

    // ── Inactive short-circuits ──────────────────────────────────────────────

    @Test
    fun `given an unbound trigger when evaluated then no health`() {
        val result = evaluator.evaluate(trigger(pipelineId = null), null, now)
        assertNull(result)
    }

    @Test
    fun `given a disabled trigger when evaluated then no health`() {
        val result = evaluator.evaluate(trigger(enabled = false), null, now)
        assertNull(result)
    }

    // ── No journal rows yet ──────────────────────────────────────────────────

    @Test
    fun `given no inputs and a freshly created trigger when evaluated then healthy`() {
        // Created just now — the first poll hasn't happened, so not yet stale.
        val result = evaluator.evaluate(trigger(createdAt = now - minute), null, now)
        assertEquals(TriggerHealthStatus.HEALTHY, result)
    }

    @Test
    fun `given no inputs and a long-created trigger when evaluated then healthy not stale`() {
        // A never-evaluated trigger reads HEALTHY regardless of age: createdAt is
        // not the activation moment, so an old-but-just-bound trigger must not be
        // mis-flagged as Overdue the instant it goes active.
        val result = evaluator.evaluate(trigger(createdAt = now - 180L * minute), null, now)
        assertEquals(TriggerHealthStatus.HEALTHY, result)
    }

    @Test
    fun `given a just-bound long-existing trigger with no evaluations when evaluated then healthy`() {
        // The reported false-positive: bind a pipeline to a trigger created days
        // ago → no journal rows yet → must be Healthy, never Overdue.
        val t = trigger(createdAt = now - 10L * 24 * 60 * minute, pipelineId = "pipeline-1")
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(t, null, now))
    }

    // ── With journal inputs ──────────────────────────────────────────────────

    @Test
    fun `given a recent evaluation and a successful last run when evaluated then healthy`() {
        val inputs =
            TriggerHealthInputs(latestEvaluatedAt = now - minute, latestFiredOutcome = TriggerRunOutcome.Success)
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given a recent evaluation and no fired run when evaluated then healthy`() {
        val inputs = TriggerHealthInputs(latestEvaluatedAt = now - minute, latestFiredOutcome = null)
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given a recent evaluation and a failed last run when evaluated then errored`() {
        val inputs = TriggerHealthInputs(
            latestEvaluatedAt = now - minute,
            latestFiredOutcome = TriggerRunOutcome.Failure("model timed out"),
        )
        assertEquals(TriggerHealthStatus.ERRORED, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given a recent evaluation and a system-cancelled last run when evaluated then errored`() {
        val inputs = TriggerHealthInputs(
            latestEvaluatedAt = now - minute,
            latestFiredOutcome = TriggerRunOutcome.CancelledBySystem,
        )
        assertEquals(TriggerHealthStatus.ERRORED, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given a recent evaluation and a hitl-timeout last run when evaluated then errored`() {
        val inputs = TriggerHealthInputs(
            latestEvaluatedAt = now - minute,
            latestFiredOutcome = TriggerRunOutcome.HitlTimeout,
        )
        assertEquals(TriggerHealthStatus.ERRORED, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given a recent evaluation and a user-cancelled last run when evaluated then errored`() {
        val inputs = TriggerHealthInputs(
            latestEvaluatedAt = now - minute,
            latestFiredOutcome = TriggerRunOutcome.Cancelled,
        )
        assertEquals(TriggerHealthStatus.ERRORED, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given a recent evaluation and a ceiling-stopped last run when evaluated then healthy`() {
        // The one non-Success outcome that is NOT an error. A run stopped by the
        // ceiling the user configured is the guard working; reddening the badge
        // for it would teach the user to distrust an indicator that is telling
        // the truth. The stop is still visible in the journal row.
        val inputs = TriggerHealthInputs(
            latestEvaluatedAt = now - minute,
            latestFiredOutcome = TriggerRunOutcome.StoppedByCeiling,
        )
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(trigger(), inputs, now))
    }

    // ── Staleness threshold and precedence ───────────────────────────────────

    @Test
    fun `given an overdue evaluation and a failed last run when evaluated then stale wins`() {
        // Not evaluated for 2h (> 60m threshold): stale supersedes the past error.
        val inputs = TriggerHealthInputs(
            latestEvaluatedAt = now - 120L * minute,
            latestFiredOutcome = TriggerRunOutcome.Failure("boom"),
        )
        assertEquals(TriggerHealthStatus.STALE, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given an evaluation just inside the interval threshold when evaluated then not stale`() {
        // 30m interval → 60m threshold; 59m ago is still within tolerance.
        val inputs = TriggerHealthInputs(latestEvaluatedAt = now - 59L * minute)
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given an evaluation just past the interval threshold when evaluated then stale`() {
        val inputs = TriggerHealthInputs(latestEvaluatedAt = now - 61L * minute)
        assertEquals(TriggerHealthStatus.STALE, evaluator.evaluate(trigger(), inputs, now))
    }

    @Test
    fun `given a sub-floor interval when evaluated then the 15-minute floor applies`() {
        // A 5m interval is clamped to the 15m background floor → 30m threshold.
        val t = trigger(condition = TriggerCondition.IntervalSchedule(5L))
        val within = TriggerHealthInputs(latestEvaluatedAt = now - 29L * minute)
        val past = TriggerHealthInputs(latestEvaluatedAt = now - 31L * minute)
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(t, within, now))
        assertEquals(TriggerHealthStatus.STALE, evaluator.evaluate(t, past, now))
    }

    @Test
    fun `given a charging trigger when evaluated then the event floor threshold applies`() {
        // Event conditions poll at the 15m floor → 30m threshold.
        val t = trigger(condition = TriggerCondition.Charging)
        val within = TriggerHealthInputs(latestEvaluatedAt = now - 29L * minute)
        val past = TriggerHealthInputs(latestEvaluatedAt = now - 31L * minute)
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(t, within, now))
        assertEquals(TriggerHealthStatus.STALE, evaluator.evaluate(t, past, now))
    }

    @Test
    fun `given a daily trigger when evaluated then a two-day threshold applies`() {
        // Daily → 1440m cadence → 2-day threshold.
        val t = trigger(condition = TriggerCondition.DailySchedule(8, 0))
        val within = TriggerHealthInputs(latestEvaluatedAt = now - (47L * 60) * minute)
        val past = TriggerHealthInputs(latestEvaluatedAt = now - (49L * 60) * minute)
        assertEquals(TriggerHealthStatus.HEALTHY, evaluator.evaluate(t, within, now))
        assertEquals(TriggerHealthStatus.STALE, evaluator.evaluate(t, past, now))
    }

    // ── Activation as a sign of life ─────────────────────────────────────────

    @Test
    fun `given a trigger re-enabled after a long pause when evaluated then the pause does not make it overdue`() {
        // Last polled a week ago, switched back on ten minutes ago: the 30-minute
        // interval's grace (60 minutes) is measured from the switch, not the poll.
        val inputs = TriggerHealthInputs(latestEvaluatedAt = now - 7L * 24 * 60 * minute)

        val result = evaluator.evaluate(trigger(activatedAt = now - 10 * minute), inputs, now)

        assertEquals(TriggerHealthStatus.HEALTHY, result)
    }

    @Test
    fun `given a trigger switched on and never polled past its grace when evaluated then stale`() {
        val result = evaluator.evaluate(trigger(activatedAt = now - 61 * minute), null, now)

        assertEquals(TriggerHealthStatus.STALE, result)
    }

    @Test
    fun `given a trigger switched on and never polled within its grace when evaluated then healthy`() {
        val result = evaluator.evaluate(trigger(activatedAt = now - 59 * minute), null, now)

        assertEquals(TriggerHealthStatus.HEALTHY, result)
    }

    @Test
    fun `given an evaluation and an activation when the last sign of life is asked then the later one is it`() {
        val inputs = TriggerHealthInputs(latestEvaluatedAt = now - 5 * minute)

        assertEquals(now - 5 * minute, evaluator.lastSignOfLife(trigger(activatedAt = now - 9 * minute), inputs))
        assertEquals(now - 2 * minute, evaluator.lastSignOfLife(trigger(activatedAt = now - 2 * minute), inputs))
        assertEquals(null, evaluator.lastSignOfLife(trigger(), null))
    }
}
