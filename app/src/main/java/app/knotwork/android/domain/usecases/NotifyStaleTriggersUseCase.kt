package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.TriggerHealthStatus
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.services.ScheduledTaskNotifier
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Tells the user about every active trigger the phone has stopped checking.
 *
 * A trigger reads [overdue][TriggerHealthStatus.STALE] when the background runtime
 * has not evaluated it for more than twice its expected cadence — the mark of a
 * battery saver or deep idle holding the app back. Without this pass that shows only
 * as a badge on the Triggers screen, which nobody opens while waiting for a report
 * that never comes. This pass is run by a periodic background job separate from the
 * triggers' own polling, so it can speak when they cannot — though if the system
 * holds back every background job of the app, this one is held back too.
 *
 * One notice per silence: the trigger keeps the last sign of life the notice was
 * about ([app.knotwork.android.domain.models.Trigger.staleNoticeFor]), and a later
 * evaluation or activation starts the next silence.
 *
 * @property triggerRepository Source of the active triggers; keeps which silence was announced.
 * @property observeHealthInputs The journal facts the health is derived from.
 * @property evaluator The health rule the Triggers screen uses too.
 * @property notifier Posts the notice.
 */
class NotifyStaleTriggersUseCase @Inject constructor(
    private val triggerRepository: TriggerRepository,
    private val observeHealthInputs: ObserveTriggerHealthInputsUseCase,
    private val evaluator: TriggerHealthEvaluator,
    private val notifier: ScheduledTaskNotifier,
) {

    /**
     * Runs one pass.
     *
     * @param nowMillis The current wall-clock time.
     * @return How many triggers a notice was sent for.
     */
    suspend operator fun invoke(nowMillis: Long = System.currentTimeMillis()): Int {
        val inputs = observeHealthInputs().first()
        var noticed = 0
        for (trigger in triggerRepository.observeActiveTriggers().first()) {
            val facts = inputs[trigger.id]
            if (evaluator.evaluate(trigger, facts, nowMillis) != TriggerHealthStatus.STALE) continue
            val signOfLife = evaluator.lastSignOfLife(trigger, facts) ?: continue
            if (trigger.staleNoticeFor == signOfLife) continue
            notifier.notifyTriggerStale(trigger.id, trigger.name, signOfLife)
            triggerRepository.markStaleNoticed(trigger.id, signOfLife)
            noticed++
        }
        return noticed
    }
}
