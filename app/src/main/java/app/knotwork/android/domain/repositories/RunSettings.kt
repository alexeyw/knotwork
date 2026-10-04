package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.Flow

/**
 * How far a run may go, and how a run nobody is watching behaves: the step and token ceilings
 * (interactive and background), the PIPELINE-node nesting depth, the structured-output repair
 * budget, the resume and background-approval windows, and the scheduled-task result notifications.
 *
 * One of the sections [SettingsRepository] is made of.
 */
interface RunSettings {

    /**
     * A [Flow] representing the maximum number of pipeline execution steps.
     * Prevents infinite loops in pipeline graphs. Valid range: 5–100.
     */
    val pipelineMaxSteps: Flow<Int>

    /**
     * Updates the maximum number of pipeline execution steps.
     *
     * @param steps The new limit. Will be coerced to the range 5–100.
     */
    suspend fun setPipelineMaxSteps(steps: Int)

    /**
     * A [Flow] representing the maximum number of pipeline execution steps for
     * a run nobody is watching — the background origins (scheduler, quick tile,
     * trigger, external automation). Valid range: 5–100.
     *
     * Until this setting existed, [pipelineMaxSteps] governed every origin.
     * While it has never been set, it therefore reports whatever
     * [pipelineMaxSteps] is configured to, so an upgrade cannot quietly shrink
     * the budget of an automation the user had already widened.
     */
    val pipelineMaxStepsBackground: Flow<Int>

    /**
     * Whether the background step ceiling has ever been set independently.
     *
     * `false` means the value [pipelineMaxStepsBackground] reports is inherited
     * from [pipelineMaxSteps] rather than chosen — a real, reachable state that
     * a surface showing both numbers has to be able to explain, because
     * otherwise it renders two identical figures and gives no hint that moving
     * the first one also moves the second.
     *
     * Exposed rather than derived by comparing the two flows: they are equal by
     * default, so equality cannot distinguish "inherited" from "deliberately
     * set to the same number", and only the presence of the stored key can.
     */
    val pipelineMaxStepsBackgroundIsSet: Flow<Boolean>

    /**
     * Updates the background step ceiling.
     *
     * @param steps The new limit. Will be coerced to the range 5–100.
     */
    suspend fun setPipelineMaxStepsBackground(steps: Int)

    /**
     * A [Flow] representing the token ceiling for an interactive run, counted
     * across the whole run tree. Valid range: 10 000–10 000 000.
     */
    val runMaxTokens: Flow<Int>

    /**
     * Updates the interactive token ceiling.
     *
     * @param tokens The new limit. Will be coerced to the range 10 000–10 000 000.
     */
    suspend fun setRunMaxTokens(tokens: Int)

    /**
     * A [Flow] representing the token ceiling for a background run, counted
     * across the whole run tree. Valid range: 10 000–10 000 000.
     */
    val runMaxTokensBackground: Flow<Int>

    /**
     * Updates the background token ceiling.
     *
     * @param tokens The new limit. Will be coerced to the range 10 000–10 000 000.
     */
    suspend fun setRunMaxTokensBackground(tokens: Int)

    /**
     * A [Flow] representing the maximum nesting depth allowed for PIPELINE-node
     * composition (how many levels of sub-pipeline a run may descend into).
     * Enforced statically by `PipelineCompositionValidator` and at runtime by
     * `PipelineNodeExecutor`. Valid range: 1–5.
     */
    val pipelineMaxNestingDepth: Flow<Int>

    /**
     * Updates the maximum PIPELINE-node nesting depth.
     *
     * @param depth The new limit. Will be coerced to the range 1–5.
     */
    suspend fun setPipelineMaxNestingDepth(depth: Int)

    /**
     * A [Flow] representing the number of corrective re-inferences the
     * structured-output gate may spend on a single node's malformed output
     * before giving up. Consumed by the gate's engine-side integration; the gate
     * treats `0` as "validate once, never repair". Valid range: 0–4.
     */
    val structuredOutputMaxRepairs: Flow<Int>

    /**
     * Updates the structured-output repair budget.
     *
     * @param count The new repair ceiling. Will be coerced to the range 0–4.
     */
    suspend fun setStructuredOutputMaxRepairs(count: Int)

    /**
     * A [Flow] representing the window, in hours, during which an interrupted
     * pipeline run can still be resumed from its checkpoint. Interrupted runs
     * older than this only offer the regular discard path — their recorded
     * context grows stale with time. Valid range: 1–168.
     */
    val resumeMaxAgeHours: Flow<Int>

    /**
     * Updates the checkpoint-resume window.
     *
     * @param hours The new window in hours. Will be coerced to the range 1–168.
     */
    suspend fun setResumeMaxAgeHours(hours: Int)

    /**
     * A [Flow] representing the window, in hours, during which a run parked
     * on a persistent HITL request (background approval or clarification)
     * waits for the user's response. Counted from the moment the live
     * in-process waiting phase timed out; once elapsed, the maintenance pass
     * fails the run with an "Approval window expired" message. Valid range:
     * 1–168.
     */
    val backgroundApprovalWindowHours: Flow<Int>

    /**
     * Updates the background-approval window.
     *
     * @param hours The new window in hours. Will be coerced to the range 1–168.
     */
    suspend fun setBackgroundApprovalWindowHours(hours: Int)

    /**
     * `true` when the user wants a system notification announcing the outcome
     * of a scheduled background run ("Task completed" / "Task failed").
     * Drives the "Scheduled task results" toggle in the Notifications card —
     * gates `ScheduledTaskNotifier`, which posts to the dedicated
     * default-importance channel with a deep-link into the session the run
     * landed in. Defaults to `true`.
     */
    val scheduledTaskNotificationsEnabled: Flow<Boolean>

    /**
     * Persists the scheduled-task result notifications toggle.
     *
     * @param enabled `true` to announce scheduled run outcomes.
     */
    suspend fun setScheduledTaskNotificationsEnabled(enabled: Boolean)
}
