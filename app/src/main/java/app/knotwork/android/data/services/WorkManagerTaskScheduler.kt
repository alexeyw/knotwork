package app.knotwork.android.data.services

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.await
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.repositories.BackgroundPromptRepository
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.ScheduledTaskKind
import app.knotwork.android.domain.services.ScheduledTaskTag
import app.knotwork.android.domain.services.TaskScheduler
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TaskScheduler] implementation backed by Jetpack `WorkManager`.
 *
 * Translates the domain scheduling calls into `WorkManager` requests targeting
 * [AgentWorker]: it stores the prompt, builds the input [Data] (the prompt's id
 * plus the bound session and run metadata), maps [ScheduledTaskConstraints] onto
 * a `WorkManager` [Constraints], and applies the recurring-work de-duplication
 * policy. All `androidx.work` knowledge lives here so that
 * [app.knotwork.android.domain.usecases.ScheduleTaskUseCase] stays framework-free.
 *
 * **Nothing the runtime stores carries the prompt.** `WorkManager` keeps each
 * request's input, tags and unique name in its own database, unencrypted, and
 * keeps a finished request's row for a day. The prompt goes to
 * [BackgroundPromptRepository] (the encrypted database) and the request carries
 * only its id — in the input, and in a tag ([PROMPT_TAG_PREFIX]) so orphaned
 * prompts can be pruned; a recurring task's unique name is a hash.
 */
@Singleton
class WorkManagerTaskScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val backgroundPrompts: BackgroundPromptRepository,
) : TaskScheduler {

    override suspend fun scheduleOneTime(
        prompt: String,
        delayMinutes: Long,
        sessionId: String?,
        constraints: ScheduledTaskConstraints,
        pipelineId: String?,
        origin: RunOrigin,
        runId: String?,
    ) {
        val promptId = UUID.randomUUID().toString()
        backgroundPrompts.store(promptId, prompt)
        val requestBuilder = OneTimeWorkRequestBuilder<AgentWorker>()
            .setInputData(buildInputData(promptId, sessionId, pipelineId, origin, runId, reusedPrompt = false))
            .setConstraints(constraints.toWorkConstraints())
            .addTag(promptTag(promptId))
        // Only the scheduling tool's own tasks are marked. A trigger fire and a
        // Quick-Settings launch reach this same method, and marking them would
        // put them inside the blast radius of "stop all scheduled tasks" — an
        // automation the user never asked to stop, silently killed by a recovery
        // action aimed at something else.
        if (origin == RunOrigin.SCHEDULER) {
            tagAsScheduledTask(requestBuilder, ScheduledTaskKind.ONE_TIME, intervalHours = 0, sessionId, promptId)
        }

        if (delayMinutes > 0) {
            requestBuilder.setInitialDelay(delayMinutes, TimeUnit.MINUTES)
        }

        workManager.enqueue(requestBuilder.build())
    }

    override suspend fun schedulePeriodic(
        prompt: String,
        intervalHours: Long,
        sessionId: String?,
        constraints: ScheduledTaskConstraints,
    ) {
        enqueuePeriodic(prompt, intervalHours, sessionId, constraints, initialDelayHours = 0)
    }

    /**
     * Re-enqueues a recurring task scheduled by a release that put the prompt in
     * the request itself, so its prompt moves to the encrypted store.
     *
     * Called by [AgentWorker] from the legacy request's own run: the runtime does
     * not expose a queued request's input, so the request is the only place the
     * prompt can be read from. The replacement waits one interval before its
     * first run — the legacy request is running now — and the caller cancels the
     * legacy request afterwards.
     *
     * @param prompt The prompt the legacy request carried.
     * @param intervalHours Its repeat interval.
     * @param sessionId Its bound session, or `null`.
     */
    suspend fun migrateLegacyPeriodic(prompt: String, intervalHours: Long, sessionId: String?) {
        enqueuePeriodic(prompt, intervalHours, sessionId, LEGACY_CONSTRAINTS, initialDelayHours = intervalHours)
    }

    override fun cancelAllScheduled() {
        workManager.cancelAllWorkByTag(ScheduledTaskTag.MARKER)
    }

    override suspend fun cancelAllBackgroundRuns() {
        // The runtime tags every request with its worker's class name, so this
        // also reaches requests enqueued by a release that added no tag of its own.
        workManager.cancelAllWorkByTag(AgentWorker::class.java.name).await()
        // Cancelled and finished rows keep their input for a day; prune now.
        workManager.pruneWork().await()
    }

    override suspend fun pruneOrphanPrompts(): Int {
        val live = workManager.getWorkInfosByTagFlow(AgentWorker::class.java.name).first()
            .filterNot { it.state.isFinished }
            .mapNotNullTo(HashSet()) { info -> info.tags.firstNotNullOfOrNull(::promptIdOf) }
        // A prompt is stored before its request is enqueued; the grace keeps one
        // this pass could otherwise catch between the two.
        return backgroundPrompts.retainOnly(live, storedBefore = System.currentTimeMillis() - PRUNE_GRACE_MS)
    }

    /**
     * Stores the prompt of a recurring task and enqueues it, keyed so the same
     * task scheduled again keeps the existing schedule.
     */
    private suspend fun enqueuePeriodic(
        prompt: String,
        intervalHours: Long,
        sessionId: String?,
        constraints: ScheduledTaskConstraints,
        initialDelayHours: Long,
    ) {
        // One key names the prompt, the tag and the unique work, so scheduling
        // the same task again rewrites the same stored prompt instead of leaving
        // a second copy behind the schedule `KEEP` preserves.
        val key = periodicKey(prompt, intervalHours, sessionId)
        val promptId = "periodic-$key"
        backgroundPrompts.store(promptId, prompt)
        val requestBuilder = PeriodicWorkRequestBuilder<AgentWorker>(intervalHours, TimeUnit.HOURS)
            .setInputData(
                buildInputData(
                    promptId,
                    sessionId,
                    pipelineId = null,
                    RunOrigin.SCHEDULER,
                    runId = null,
                    reusedPrompt = true,
                ),
            )
            .setConstraints(constraints.toWorkConstraints())
            .addTag(promptTag(promptId))
        if (initialDelayHours > 0) requestBuilder.setInitialDelay(initialDelayHours, TimeUnit.HOURS)
        tagAsScheduledTask(requestBuilder, ScheduledTaskKind.PERIODIC, intervalHours, sessionId, promptId)

        // Keyed by (intervalHours, sessionId, trimmed prompt) so re-scheduling
        // the same recurring agent task into the same session does not stack
        // duplicate PeriodicWorkRequests, while two schedules bound to different
        // sessions stay independent. `KEEP` means the first schedule wins; a
        // later identical call short-circuits.
        workManager.enqueueUniquePeriodicWork(
            "$PERIODIC_NAME_PREFIX$key",
            ExistingPeriodicWorkPolicy.KEEP,
            requestBuilder.build(),
        )
    }

    /**
     * Attaches the scheduled-task marker and the label to [builder].
     *
     * Both are needed because the runtime does not expose a queued request's
     * input data: the marker is what "cancel every scheduled task" matches on
     * (and what keeps trigger / tile work out of that blast radius), while the
     * label is the only way the task monitor can say which task a row is.
     *
     * @param builder The request under construction.
     * @param kind One-shot or repeating.
     * @param intervalHours Repeat interval in hours; `0` for a one-time task.
     * @param sessionId Bound chat session, or `null`.
     * @param promptId Id of the task's stored prompt.
     */
    private fun tagAsScheduledTask(
        builder: WorkRequest.Builder<*, *>,
        kind: ScheduledTaskKind,
        intervalHours: Long,
        sessionId: String?,
        promptId: String,
    ) {
        builder.addTag(ScheduledTaskTag.MARKER)
        builder.addTag(ScheduledTaskTag.encode(kind, intervalHours, sessionId, promptId))
    }

    /**
     * Builds the worker input data carrying the prompt's id, the (optional)
     * bound session id, the (optional) explicit pipeline binding, the run origin
     * and the (optional) pre-minted run id read by [AgentWorker].
     *
     * @param reusedPrompt `true` for a recurring task, whose stored prompt serves
     *   every run; a one-time run's prompt is dropped once the run is enqueued.
     */
    private fun buildInputData(
        promptId: String,
        sessionId: String?,
        pipelineId: String?,
        origin: RunOrigin,
        runId: String?,
        reusedPrompt: Boolean,
    ): Data = Data.Builder()
        .putString(AgentWorker.KEY_PROMPT_ID, promptId)
        .putBoolean(AgentWorker.KEY_PROMPT_REUSED, reusedPrompt)
        .putString(AgentWorker.KEY_SESSION_ID, sessionId)
        .putString(AgentWorker.KEY_PIPELINE_ID, pipelineId)
        .putString(AgentWorker.KEY_ORIGIN, origin.name)
        .putString(AgentWorker.KEY_RUN_ID, runId)
        .build()

    /**
     * Maps the domain [ScheduledTaskConstraints] onto a `WorkManager`
     * [Constraints] instance.
     */
    private fun ScheduledTaskConstraints.toWorkConstraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(requiresBatteryNotLow)
        .build()

    companion object {
        /** Prefix of the tag carrying a request's prompt id; see [promptTag]. */
        const val PROMPT_TAG_PREFIX: String = "knotwork-prompt|"

        /** How old an unreferenced prompt must be before the prune removes it: one hour. */
        const val PRUNE_GRACE_MS: Long = 60L * 60L * 1000L

        /** Prefix of a recurring task's unique-work name. */
        private const val PERIODIC_NAME_PREFIX = "agent-periodic2|"

        /**
         * Constraints for a recurring task re-enqueued from a legacy request, whose
         * own constraints the runtime does not expose: the scheduling tool's default.
         */
        private val LEGACY_CONSTRAINTS = ScheduledTaskConstraints(requiresBatteryNotLow = true)

        /** The tag naming the stored prompt a request carries. */
        fun promptTag(promptId: String): String = "$PROMPT_TAG_PREFIX$promptId"

        /** The prompt id a tag names, or `null` when it is not a prompt tag. */
        fun promptIdOf(tag: String): String? = tag.removePrefix(PROMPT_TAG_PREFIX).takeIf { it != tag }

        /**
         * The de-duplication key of a recurring task: a hash of its interval, bound
         * session and trimmed prompt — unambiguous, and nothing the runtime stores
         * can be read back into the prompt.
         *
         * The prompt is whitespace-trimmed so cosmetically different strings
         * (e.g. a stray trailing space) collapse to one schedule; the verbatim
         * prompt is still what the worker executes. A `null` session maps to the
         * empty string (the legacy fresh-session path).
         */
        fun periodicKey(prompt: String, intervalHours: Long, sessionId: String?): String {
            val material = "${intervalHours}h|${sessionId.orEmpty()}|${prompt.trim()}"
            val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
