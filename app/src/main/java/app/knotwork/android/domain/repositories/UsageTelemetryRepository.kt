package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.OnboardingJourney
import app.knotwork.android.domain.models.OnboardingMilestone
import app.knotwork.android.domain.models.PipelineRunStatus
import app.knotwork.android.domain.models.UsageTelemetrySummary
import kotlinx.coroutines.flow.Flow

/**
 * Fully **on-device** usage-statistics store backing the privacy-preserving
 * local telemetry feature.
 *
 * Records a small set of counters — terminal run outcomes (per pipeline and per
 * status), background trigger firings (per kind), and distinct active days — plus
 * the write-once [OnboardingMilestone] markers of the install → first-value
 * journey, so the Settings → Privacy → Usage statistics screen can show how the
 * app is used during dogfooding **without any data ever leaving the device**.
 * The onboarding markers are what makes the "< 10 minutes to first value"
 * measurement repeatable: they replace a stopwatch with recorded data. There is no
 * network dependency anywhere on this path; every figure is read straight back
 * out of the local (SQLCipher-encrypted) store.
 *
 * **Opt-in gating.** Recording is gated by the
 * [PrivacySettings.usageTelemetryEnabled] flag. When the user has turned
 * local statistics off, every `record…` method — counters and onboarding markers
 * alike — is a silent no-op (implementations check the flag), so nothing advances.
 * [reset] and [summary] are not gated — the user can always inspect or clear
 * whatever has already accumulated.
 *
 * **Best-effort contract.** Recording must never affect the run or trigger it
 * observes: implementations absorb storage failures (logged, no-op) and only
 * ever re-throw `CancellationException`.
 */
interface UsageTelemetryRepository {

    /**
     * Live aggregate of every local counter, including the derived weekly
     * retention figures ([UsageTelemetrySummary.retention]). Emits a fresh
     * [UsageTelemetrySummary] whenever any counter changes (and
     * [UsageTelemetrySummary.EMPTY] when nothing has been recorded yet or the
     * statistics were just reset).
     *
     * The retention window is anchored to the device-local "today" resolved when
     * a collector subscribes, so a long-lived collector keeps its anchor until it
     * re-subscribes.
     */
    val summary: Flow<UsageTelemetrySummary>

    /**
     * Whether local statistics recording is currently enabled (mirrors
     * [PrivacySettings.usageTelemetryEnabled]). Exposed so a hot recording
     * chokepoint can skip the work it would otherwise do to gather the data for
     * a recording that would then be discarded.
     *
     * @return `true` when recording is on; `false` when the user has opted out.
     */
    suspend fun isEnabled(): Boolean

    /**
     * Records the terminal outcome of one **root** pipeline run — the per-pipeline
     * and per-outcome tallies, the active day, and (when the pipeline is known)
     * the `(day, pipeline)` pair the weekly "live pipelines" figure is built from.
     *
     * Callers must pass only root-level runs (no nested sub-pipeline runs), so a
     * composed pipeline counts as a single run. A no-op when recording is
     * disabled or [status] is non-terminal.
     *
     * @param pipelineId Id of the pipeline that ran, or `null` when it was never
     *   resolved (a queued run swept to `INTERRUPTED` by the orphan sweep).
     * @param status The terminal [PipelineRunStatus] the run reached.
     * @param atMillis Wall-clock time of the terminal transition, epoch-millis;
     *   used only to derive the device-local active day. Injectable for tests.
     */
    suspend fun recordPipelineRunOutcome(pipelineId: String?, status: PipelineRunStatus, atMillis: Long)

    /**
     * Records one background trigger firing, tallied by its
     * [app.knotwork.android.domain.models.telemetryKind]. A no-op when recording
     * is disabled.
     *
     * @param kind The stable telemetry kind of the fired trigger's condition.
     * @param atMillis Wall-clock time of the firing, epoch-millis; used only to
     *   derive the device-local active day. Injectable for tests.
     */
    suspend fun recordTriggerFired(kind: String, atMillis: Long)

    /**
     * Records one write-once [OnboardingMilestone] on the install → first-value
     * path. A no-op when recording is disabled **or when the marker already
     * exists** — the store keeps the first occurrence, so re-entering onboarding
     * never moves an already-measured journey.
     *
     * @param milestone The marker being reached.
     * @param atMillis Wall-clock time of the marker, epoch-millis. Injectable for
     *   tests.
     * @param detail Optional payload carried by the marker — the materialised
     *   pipeline id for [OnboardingMilestone.SCENARIO_CHOSEN], `null` otherwise.
     */
    suspend fun recordOnboardingMilestone(milestone: OnboardingMilestone, atMillis: Long, detail: String? = null)

    /**
     * Records [OnboardingMilestone.FIRST_VALUE] for a run of [pipelineId] that
     * just reached `COMPLETED`, provided that run terminates the measured
     * journey (see [OnboardingJourney.acceptsFirstValueFrom]). A no-op when
     * recording is disabled, when onboarding was never started, when first value
     * is already recorded, or when a scenario was set up and [pipelineId] is not
     * its pipeline.
     *
     * @param pipelineId Id of the pipeline whose root run completed.
     * @param atMillis Wall-clock time of the terminal transition, epoch-millis.
     */
    suspend fun recordOnboardingFirstValue(pipelineId: String, atMillis: Long)

    /**
     * Clears every recorded counter and onboarding marker, returning the
     * statistics to the empty state. Not gated by the opt-in flag — the user may
     * clear accumulated data regardless of the current toggle state.
     */
    suspend fun reset()
}
