package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.Trigger
import app.knotwork.android.domain.models.TriggerEvaluationSource
import app.knotwork.android.domain.models.TriggerEvaluationVerdict
import app.knotwork.android.domain.models.TriggerSkipReason
import app.knotwork.android.domain.models.telemetryKind
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.NetworkStateRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PowerStateRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.services.DefaultNames
import app.knotwork.android.domain.services.ScheduledTaskConstraints
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import app.knotwork.android.domain.services.TaskScheduler
import app.knotwork.android.domain.services.TriggerScheduler
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of a single trigger-fire attempt, returned for logging and so the
 * worker can decide whether its watch should keep running.
 */
sealed interface TriggerFireOutcome {

    /**
     * Whether the background watch that produced this outcome should keep
     * running. `false` means the trigger no longer warrants a watch (gone,
     * disabled, unbound, or auto-disabled), so the worker cancels its own work.
     */
    val watchStillWarranted: Boolean

    /**
     * A background run of [pipelineId] was enqueued.
     *
     * @property pipelineId The pipeline that was scheduled.
     */
    data class Fired(val pipelineId: String) : TriggerFireOutcome {
        override val watchStillWarranted: Boolean get() = true
    }

    /** An event trigger's condition dropped, so the trigger was re-armed. */
    data object ReArmed : TriggerFireOutcome {
        override val watchStillWarranted: Boolean get() = true
    }

    /**
     * The trigger did not fire this time but its watch is still valid (condition
     * not yet met, or already fired this cycle).
     *
     * @property reason Why the evaluation skipped the trigger.
     */
    data class Skipped(val reason: TriggerSkipReason) : TriggerFireOutcome {
        override val watchStillWarranted: Boolean
            get() = reason == TriggerSkipReason.CONDITION_NOT_MET || reason == TriggerSkipReason.ALREADY_FIRED
    }

    /**
     * The trigger's bound pipeline no longer exists, so the trigger was
     * auto-disabled to stop it waking pointlessly.
     *
     * @property pipelineId The id of the now-missing pipeline.
     */
    data class Disabled(val pipelineId: String) : TriggerFireOutcome {
        override val watchStillWarranted: Boolean get() = false
    }

    /** No trigger row matched the id (deleted between wakeup and handling). */
    data object NotFound : TriggerFireOutcome {
        override val watchStillWarranted: Boolean get() = false
    }
}

/**
 * Handles a background wakeup for a single trigger: load it, decide via the pure
 * [EvaluateTriggerFiringUseCase], and act.
 *
 * On [TriggerFiringDecision.Fire] it does **not** run inference itself — it
 * delegates to the existing [TaskScheduler.scheduleOneTime] path so the run goes
 * through `AgentWorker` exactly like a scheduled task (foreground promotion,
 * completion notification and engine unload all reused), attributed to
 * [RunOrigin.TRIGGER].
 *
 * **Bound session.** Unlike a scheduled task — which inherits the chat session
 * it was scheduled from — a trigger has no originating conversation, so it
 * lazily owns one. On the first fire (or after the user deletes the previously
 * bound session) it mints a chat session named after the trigger and threads it
 * to the scheduler, so the results of recurring fires accumulate in one
 * conversation instead of spawning a fresh session each time. The binding
 * ([TriggerRepository.setSessionId]) is persisted only **after** the enqueue
 * succeeds, so a failed fire never strands the trigger on an empty session. A
 * "Trigger fired" notification (best-effort) deep-links the user straight into
 * that session.
 *
 * Safety behaviours enforced here:
 * - **Idempotency.** The fire-state writes ([TriggerRepository.markFired] and,
 *   for event triggers, the disarm) happen **before** the irreversible enqueue,
 *   so a process death or DB error on the worker retry path re-evaluates against
 *   the recorded state and cannot enqueue a duplicate run (the cost of the rare
 *   failure is a *missed* run, not a doubled one). Session resolution happens
 *   **before** those writes, so a failure to create the session re-evaluates and
 *   fires fresh rather than burning the cycle; a freshly-minted session is rolled
 *   back if the enqueue itself then fails. The "Trigger fired" notification is
 *   posted best-effort so a notification failure cannot trigger a worker retry
 *   (which, past the completed enqueue, would double-schedule the run).
 * - **Edge firing.** Event conditions (charging / network) fire once per
 *   transition into the satisfied state; the disarm here plus the
 *   [TriggerFiringDecision.ReArm] path implement that latch.
 * - **Deleted bound pipeline.** Auto-disabled rather than firing into nothing.
 *
 * **Journal — no silent skips.** Every evaluated trigger writes exactly one
 * [app.knotwork.android.domain.models.TriggerEvaluation] recording its verdict,
 * atomically with the decision, via [recordTriggerEvaluation]. A skip records
 * [TriggerEvaluationVerdict.Skipped] with the reason, a re-arm records
 * [TriggerEvaluationVerdict.ReArmed] (making the consumed-on-fire → re-arm window
 * visible rather than a mystery), and a fire records [TriggerEvaluationVerdict.Fired]
 * with the run id **before** the run exists — so the run's terminal outcome is later
 * attributed straight back onto this row (see [triggerRunOutcomeForTerminal]). The
 * only evaluation that writes no row is [TriggerFireOutcome.NotFound]: the trigger
 * was deleted, so there is nothing to attribute a row to. Recording is best-effort
 * (the journal store absorbs and logs its own failures) and never alters or aborts
 * the fire it observes.
 *
 * A chat the trigger creates is named after the trigger, else after its prompt, else
 * with [DefaultNames.triggerChat] in the interface language.
 */
@Singleton
class FireTriggerUseCase @Inject constructor(
    private val triggerRepository: TriggerRepository,
    private val pipelineRepository: PipelineRepository,
    private val powerStateRepository: PowerStateRepository,
    private val networkStateRepository: NetworkStateRepository,
    private val evaluateTriggerFiring: EvaluateTriggerFiringUseCase,
    private val taskScheduler: TaskScheduler,
    private val chatRepository: ChatRepository,
    private val scheduledTaskNotifier: ScheduledTaskNotifier,
    private val triggerScheduler: TriggerScheduler,
    private val usageTelemetry: UsageTelemetryRepository,
    private val recordTriggerEvaluation: RecordTriggerEvaluationUseCase,
    private val defaultNames: DefaultNames,
) {

    /**
     * Evaluates and, if warranted, fires the trigger identified by [triggerId].
     *
     * @param triggerId The id of the trigger that woke the background runtime.
     * @param source Where this evaluation originated (which background surface
     *   woke the runtime), stamped on the journal row so a gap in one source is
     *   distinguishable from a gap in another.
     * @param nowMillis Current wall-clock time, epoch-millis (injectable for tests).
     * @return The [TriggerFireOutcome] describing what happened.
     */
    suspend operator fun invoke(
        triggerId: String,
        source: TriggerEvaluationSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): TriggerFireOutcome {
        val trigger = triggerRepository.getTriggerById(triggerId)
        if (trigger == null) {
            // No trigger row to attribute a journal entry to — nothing to record.
            Timber.tag(TAG).d("Trigger %s no longer exists; nothing to fire.", triggerId)
            return TriggerFireOutcome.NotFound
        }

        val decision = evaluateTriggerFiring(
            trigger = trigger,
            power = powerStateRepository.powerState.value,
            network = networkStateRepository.networkState.value,
            nowMillis = nowMillis,
        )

        return when (decision) {
            is TriggerFiringDecision.Skip -> {
                recordTriggerEvaluation(
                    triggerId = triggerId,
                    source = source,
                    verdict = TriggerEvaluationVerdict.Skipped(decision.reason),
                    evaluatedAt = nowMillis,
                )
                Timber.tag(TAG).d("Trigger %s skipped: %s", triggerId, decision.reason)
                TriggerFireOutcome.Skipped(decision.reason)
            }

            TriggerFiringDecision.ReArm -> {
                triggerRepository.setArmed(triggerId, true)
                // Re-arming means the event condition just dropped (e.g. unplugged).
                // Re-register so the consumed charging one-shot watch is recreated,
                // ready to fire instantly on the next charge edge.
                triggerScheduler.register(trigger)
                recordTriggerEvaluation(
                    triggerId = triggerId,
                    source = source,
                    verdict = TriggerEvaluationVerdict.ReArmed,
                    evaluatedAt = nowMillis,
                )
                Timber.tag(TAG).d("Trigger %s re-armed.", triggerId)
                TriggerFireOutcome.ReArmed
            }

            is TriggerFiringDecision.Fire -> fire(trigger, decision, source, nowMillis)
        }
    }

    /**
     * Enqueues the bound pipeline run after a final existence check, recording
     * the fire/disarm state **before** the enqueue, or disables the trigger when
     * the bound pipeline has been deleted.
     */
    private suspend fun fire(
        trigger: Trigger,
        decision: TriggerFiringDecision.Fire,
        source: TriggerEvaluationSource,
        nowMillis: Long,
    ): TriggerFireOutcome {
        if (pipelineRepository.getPipelineById(decision.pipelineId) == null) {
            triggerRepository.setEnabled(trigger.id, false)
            // No run is enqueued, so the journal records this as a skip: the
            // bound pipeline is gone, which reads as UNBOUND after the fact.
            recordTriggerEvaluation(
                triggerId = trigger.id,
                source = source,
                verdict = TriggerEvaluationVerdict.Skipped(TriggerSkipReason.UNBOUND),
                evaluatedAt = nowMillis,
            )
            Timber.tag(TAG).w("Trigger %s bound pipeline %s missing; auto-disabled.", trigger.id, decision.pipelineId)
            return TriggerFireOutcome.Disabled(decision.pipelineId)
        }

        // Resolve the bound session before any suppression write: a failure to
        // create it must re-evaluate and fire fresh, not burn the cycle.
        val session = resolveBoundSession(trigger, nowMillis)

        // Mint the run id up front so the journal row (written after a successful
        // enqueue, below) carries the exact id the eventual `PipelineRun` will
        // have — the key the run's terminal outcome is later attributed back by.
        val runId = UUID.randomUUID().toString()

        // Record the suppression state first: if the enqueue or process dies
        // after this point, the next wake re-evaluates against the recorded
        // state and will not double-fire.
        triggerRepository.markFired(trigger.id, nowMillis)
        if (trigger.condition.isEventTriggered) {
            triggerRepository.setArmed(trigger.id, false)
        }

        try {
            taskScheduler.scheduleOneTime(
                prompt = decision.prompt,
                delayMinutes = 0,
                sessionId = session.id,
                constraints = ScheduledTaskConstraints(requiresBatteryNotLow = true),
                pipelineId = decision.pipelineId,
                origin = RunOrigin.TRIGGER,
                runId = runId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The enqueue failed. Drop a freshly-minted session so a failed fire
            // never leaves an empty orphan chat behind; the binding has not been
            // persisted yet, so nothing dangles. No journal row is written for a
            // fire that never enqueued a run — the worker retry re-evaluates and
            // records the resulting verdict (an ALREADY_FIRED skip, since the fire
            // state was already stamped). Re-throw so the worker retries.
            if (session.isNewlyCreated) chatRepository.deleteSession(session.id)
            throw e
        }

        // Open the two-phase journal row the instant the run is really enqueued —
        // before any further work — so the window in which the run exists but its
        // Fired row does not is as narrow as the DB write itself. Written after
        // the enqueue (never before) so a failed enqueue leaves no phantom Fired
        // row whose outcome could never settle. Outcome is filled in later, by run
        // id, when the run reaches a terminal state.
        recordTriggerEvaluation(
            triggerId = trigger.id,
            source = source,
            verdict = TriggerEvaluationVerdict.Fired,
            runId = runId,
            evaluatedAt = nowMillis,
        )

        // Persist the binding only once the run is really enqueued, so a
        // scheduling failure cannot strand a trigger pointing at a session that
        // never received a run.
        if (session.isNewlyCreated) triggerRepository.setSessionId(trigger.id, session.id)

        notifyTriggerFired(session.id, trigger.name)
        // Tally the firing into the privacy-preserving local usage statistics
        // (best-effort, opt-in gated and self-absorbing inside the repository).
        usageTelemetry.recordTriggerFired(trigger.condition.telemetryKind, nowMillis)
        Timber.tag(TAG).d("Trigger %s fired pipeline %s into session %s.", trigger.id, decision.pipelineId, session.id)
        return TriggerFireOutcome.Fired(decision.pipelineId)
    }

    /**
     * Returns the chat session the trigger's run should land in: the persisted
     * binding when its session still exists, otherwise a freshly-minted session
     * named after the trigger. The caller persists the binding only after a
     * successful enqueue (so a failed fire leaves nothing dangling) and re-mints
     * here on the next fire if the user has deleted the bound session.
     *
     * The session carries no pipeline binding (`pipelineId = null`): it is a
     * result log, so a manual follow-up the user types into it uses the
     * application-default pipeline rather than a stale copy of whatever pipeline
     * the trigger happened to run when the session was first created.
     */
    private suspend fun resolveBoundSession(trigger: Trigger, nowMillis: Long): ResolvedSession {
        val bound = trigger.sessionId
        if (bound != null && chatRepository.sessionExists(bound)) {
            return ResolvedSession(id = bound, isNewlyCreated = false)
        }
        val session = ChatSession.create(name = sessionName(trigger), now = nowMillis)
        chatRepository.saveSession(session)
        Timber.tag(TAG).d("Trigger %s bound to new session %s.", trigger.id, session.id)
        return ResolvedSession(id = session.id, isNewlyCreated = true)
    }

    /**
     * Derives a non-blank session title from the trigger. The editor enforces a
     * name, so the prompt / constant fallbacks are purely defensive against a
     * raw persisted row read off the background path.
     */
    private fun sessionName(trigger: Trigger): String =
        trigger.name.ifBlank { trigger.prompt.take(SESSION_NAME_FALLBACK_LENGTH) }
            .ifBlank { defaultNames.triggerChat() }

    /**
     * Posts the "Trigger fired" notification best-effort: a notification failure
     * must never propagate to the worker's blanket `catch -> Result.retry()`,
     * which (past the already-completed enqueue) could re-fire and double-schedule
     * the run. Mirrors the best-effort contract `AgentWorker` applies to its own
     * outcome notifications.
     */
    private suspend fun notifyTriggerFired(sessionId: String, triggerName: String) {
        try {
            scheduledTaskNotifier.notifyTriggerFired(sessionId, triggerName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to post the trigger-fired notification for session %s.", sessionId)
        }
    }

    /**
     * A resolved bound session plus whether this call created it (so the caller
     * can persist the binding only on success and roll the session back on a
     * scheduling failure).
     */
    private data class ResolvedSession(val id: String, val isNewlyCreated: Boolean)

    private companion object {
        const val TAG = "Trigger"

        /** Max prompt characters used as a defensive session-name fallback. */
        const val SESSION_NAME_FALLBACK_LENGTH = 40
    }
}
