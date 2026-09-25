package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.TaskScheduler
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case to schedule a future or periodic task for the agent.
 *
 * Decides between a one-time and a recurring schedule based on the interval and
 * delegates the actual enqueueing to the [TaskScheduler] domain port, keeping
 * this use case free of any background-execution framework dependency.
 *
 * **Runaway guard.** A scheduled run whose prompt tells the agent to schedule
 * the next one forms a chain that never ends and never grows: exactly one task
 * is queued at any moment, so nothing about the queue looks wrong while the
 * agent runs continuously in the background. The tell is the *rate* of scheduled
 * runs, not the depth of the queue, so scheduling is refused once
 * [MAX_SCHEDULED_RUNS_PER_HOUR] scheduled runs have already started within the
 * last hour. A legitimate cadence (hourly or slower, which is also all the
 * background runtime honours for repeating work) never approaches the limit;
 * a self-re-scheduling loop crosses it within minutes. The refusal is returned
 * to the model as the tool's result, worded so that retrying is visibly not the
 * answer.
 */
@Singleton
class ScheduleTaskUseCase @Inject constructor(
    private val taskScheduler: TaskScheduler,
    private val pipelineRunRepository: PipelineRunRepository,
) {

    /**
     * Schedules a task.
     *
     * @param prompt The prompt or task description for the agent to execute.
     * @param intervalHours The interval in hours. If > 0, it schedules a periodic task.
     *                      If 0, it schedules a one-time task to execute immediately (or later if we added delay).
     * @param delayMinutes Optional delay in minutes for one-time tasks.
     * @param sessionId Id of the chat session the scheduled run should land its
     *   result in. Comes from the engine-side `ToolExecutionContext` (never from
     *   LLM arguments). `null` — e.g. for work enqueued before this parameter
     *   existed — makes `AgentWorker` create a fresh auto-named session per run.
     * @param nowMillis Current time, epoch-millis (injectable for tests).
     * @return A success message, the runaway-guard refusal, or an error message.
     */
    suspend operator fun invoke(
        prompt: String,
        intervalHours: Long = 0,
        delayMinutes: Long = 0,
        sessionId: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = try {
        val recentRuns = pipelineRunRepository.countRunsByOriginSince(
            origin = CEILING.origin,
            sinceEpochMs = CEILING.windowStart(nowMillis),
        )
        if (CEILING.isExceededBy(recentRuns)) {
            Timber.w("Refusing to schedule: %d scheduled runs in the last hour", recentRuns)
            REFUSAL_MESSAGE
        } else {
            schedule(prompt, intervalHours, delayMinutes, sessionId)
        }
    } catch (e: CancellationException) {
        // The guard's history read suspends: a cancelled caller must not be
        // reported back to the model as a scheduling failure.
        throw e
    } catch (e: Exception) {
        Timber.e(e, "Failed to schedule task")
        "Failed to schedule task: ${e.message}"
    }

    /**
     * Enqueues the task once the runaway guard has allowed it.
     *
     * @return The user-facing confirmation for the tool result.
     */
    private fun schedule(prompt: String, intervalHours: Long, delayMinutes: Long, sessionId: String?): String {
        val constraints = ScheduledTaskConstraints(requiresBatteryNotLow = true)

        return if (intervalHours > 0) {
            taskScheduler.schedulePeriodic(prompt, intervalHours, sessionId, constraints)
            Timber.d("Scheduled periodic task every $intervalHours hours with prompt: $prompt")
            "Task successfully scheduled to run every $intervalHours hours."
        } else {
            taskScheduler.scheduleOneTime(prompt, delayMinutes, sessionId, constraints)
            Timber.d("Scheduled one-time task with delay $delayMinutes minutes. Prompt: $prompt")
            "One-time task successfully scheduled with $delayMinutes minutes delay."
        }
    }

    /** Runaway-guard constants. */
    companion object {
        /**
         * The rate ceiling this guard enforces — the scheduler's instance of the
         * shared [RunRateCeiling] mechanism.
         */
        val CEILING: RunRateCeiling = RunRateCeiling.SCHEDULED

        /**
         * How many scheduled runs may start within one hour before further
         * scheduling is refused. Kept as a named constant because it is the
         * number the public extension documentation quotes; the value itself
         * lives on [CEILING], so the guard and the documented number cannot
         * drift apart.
         */
        val MAX_SCHEDULED_RUNS_PER_HOUR: Int = CEILING.limitPerWindow

        /**
         * Returned instead of scheduling when the guard trips. Phrased as a
         * settled outcome rather than a transient error, so the model reports it
         * instead of retrying, and it names the surface where the user can see
         * and stop the tasks already queued.
         */
        const val REFUSAL_MESSAGE: String =
            "Not scheduled: too many scheduled runs have already started in the last hour, which is what a task " +
                "that keeps re-scheduling itself looks like. Do not try again — tell the user to review or cancel " +
                "the existing scheduled tasks under More > Active tasks first."
    }
}
