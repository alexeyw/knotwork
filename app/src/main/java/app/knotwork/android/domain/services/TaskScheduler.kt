package app.knotwork.android.domain.services

import app.knotwork.android.domain.models.RunOrigin

/**
 * Domain-level constraints a scheduled agent task must satisfy before the
 * background runtime is allowed to execute it.
 *
 * Modelled as a pure-domain value so the scheduling policy (e.g. "do not run
 * while the battery is low") lives in the `domain` layer, while the mapping to
 * the concrete background-execution constraints is the implementation's
 * concern. Today only the battery guard is expressed; the type is the single
 * extension point for any future constraint (network, charging, idle).
 *
 * @property requiresBatteryNotLow When `true`, the task is held back while the
 *   device reports a low-battery state and released once charge recovers.
 */
data class ScheduledTaskConstraints(val requiresBatteryNotLow: Boolean)

/**
 * Domain port that abstracts the scheduling of future and recurring agent
 * tasks away from any concrete background-execution framework.
 *
 * Introduced so [app.knotwork.android.domain.usecases.ScheduleTaskUseCase] can
 * decide *what* to schedule (one-time vs periodic, with which prompt, session
 * and constraints) without importing `androidx.work` or reaching into the
 * `data` layer — preserving the Clean Architecture invariant that `domain`
 * carries zero Android/framework dependencies and never depends on `data`.
 *
 * The implementation (`data/services/WorkManagerTaskScheduler`) maps these
 * domain calls onto `WorkManager` requests. The methods operate purely on
 * domain types; framework specifics (request builders, the unique-work key,
 * the existing-work policy) stay behind this boundary.
 *
 * **A prompt never reaches the runtime.** The runtime stores a request's input,
 * tags and unique name in its own unencrypted database; the scheduling methods
 * keep the prompt in the encrypted store
 * ([app.knotwork.android.domain.repositories.BackgroundPromptRepository]) and
 * hand the runtime an id — which is why they suspend.
 */
interface TaskScheduler {

    /**
     * Schedules a task to run once.
     *
     * @param prompt The prompt the agent should execute when the task fires.
     * @param delayMinutes Delay before the task becomes eligible to run, in
     *   minutes. `0` (or less) means "as soon as the constraints allow".
     * @param sessionId Id of the chat session the run should land its result
     *   in, sourced from the engine-side execution context (never from LLM
     *   arguments). `null` lets the worker create a fresh auto-named session.
     * @param constraints Domain constraints the runtime must honour before
     *   executing the task.
     * @param pipelineId Id of the pipeline the run must execute against. `null`
     *   defers to the application default (the `schedule_task` tool path);
     *   entry surfaces (e.g. the Quick Settings tile) pass an explicit binding
     *   so the run uses the user's chosen pipeline regardless of the default.
     * @param origin What triggered the run, recorded on the persistent run
     *   record. Defaults to [RunOrigin.SCHEDULER] for the scheduler tool; the
     *   tile passes [RunOrigin.QUICK_TILE].
     * @param runId Pre-minted id for the run this task starts (equal to the
     *   eventual `PipelineRun` / `AgentTask` id). `null` — the default for every
     *   caller that does not need to know the id up front — lets the runtime mint
     *   a fresh one. A trigger fire supplies it so it can write the run id onto
     *   its two-phase journal row **before** the run exists, then attribute the
     *   run's terminal outcome back by the very same id.
     */
    suspend fun scheduleOneTime(
        prompt: String,
        delayMinutes: Long,
        sessionId: String?,
        constraints: ScheduledTaskConstraints,
        pipelineId: String? = null,
        origin: RunOrigin = RunOrigin.SCHEDULER,
        runId: String? = null,
    )

    /**
     * Schedules a task to run repeatedly on a fixed interval.
     *
     * Recurring tasks are de-duplicated by the `(intervalHours, sessionId,
     * trimmed prompt)` triple: scheduling the same recurring task into the same
     * session again keeps the existing schedule rather than stacking a
     * duplicate. The prompt is matched after whitespace-trimming (so a stray
     * leading/trailing space no longer slips past the de-dup), while the agent
     * still executes the verbatim prompt. [sessionId] is part of the key, so two
     * schedules with the same prompt and interval but different bound sessions
     * stay independent; a `null` session collapses with other `null`-session
     * schedules of the same prompt and interval. This contract is honoured by
     * the implementation.
     *
     * @param prompt The prompt the agent should execute on every run.
     * @param intervalHours The repeat interval, in hours. Must be positive;
     *   callers gate the one-time vs periodic choice before invoking this.
     * @param sessionId Id of the chat session each run should land its result
     *   in (see [scheduleOneTime]). `null` defers to the worker's fallback.
     * @param constraints Domain constraints the runtime must honour before
     *   executing each run.
     */
    suspend fun schedulePeriodic(
        prompt: String,
        intervalHours: Long,
        sessionId: String?,
        constraints: ScheduledTaskConstraints,
    )

    /**
     * Cancels every task scheduled through [scheduleOneTime] / [schedulePeriodic]
     * — the user's escape hatch from a task that keeps re-scheduling itself.
     *
     * Deliberately scoped to tasks created by the scheduling tool (matched by
     * [ScheduledTaskTag.MARKER]): automation triggers, the Quick-Settings tile
     * and model downloads run on the same background runtime and must survive
     * this, or the only way out of a runaway task would again be a blunt
     * instrument. A task already executing is cancelled mid-run like any other
     * stopped run; nothing re-schedules it afterwards.
     */
    fun cancelAllScheduled()

    /**
     * Cancels every queued or running background run, whatever started it, and
     * clears the runtime's record of finished ones — the part of *Erase data*
     * the database wipe cannot reach, because the runtime keeps its own store.
     */
    suspend fun cancelAllBackgroundRuns()

    /**
     * Drops the stored prompts that no unfinished background run carries any
     * more — requests cancelled before they ran leave theirs behind.
     *
     * @return How many prompts were dropped.
     */
    suspend fun pruneOrphanPrompts(): Int
}
