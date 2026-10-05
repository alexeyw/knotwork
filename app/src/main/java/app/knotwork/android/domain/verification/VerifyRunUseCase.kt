package app.knotwork.android.domain.verification

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.TraceHashing
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.usecases.LoadModelUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import javax.inject.Inject

/**
 * Checks a finished run by repeating its on-device model calls.
 *
 * Each call of the [VerificationPlan] is sent again **from its own recorded
 * prompt**, with its recorded sampler and seed, on the model file it ran on, and
 * the answer is compared with the recorded one by SHA-256. Nothing else runs:
 * not the graph, not a tool, not a cloud model. That is the whole guarantee the
 * check rests on — a tool's side effect must never happen twice because someone
 * asked whether a run repeats — so this class reaches nothing that could run a
 * tool, and `VerificationToolIsolationKonsistTest` keeps it that way.
 *
 * Before each call the model is loaded (another run may have loaded another),
 * and the engine is checked against what the call ran on: the file, the backend
 * the engine actually runs on after any fallback, and on GPU the context window.
 * A difference stops the check; nothing is compared on the wrong model. The same
 * check runs after the call, which catches a run that loaded another model in
 * between.
 *
 * @property loadModel Loads the model file a call ran on.
 * @property engine The on-device engine the calls are repeated on.
 * @property localModelRepository The model registry, for a model's name.
 */
class VerifyRunUseCase @Inject constructor(
    private val loadModel: LoadModelUseCase,
    private val engine: LlmInferenceEngine,
    private val localModelRepository: LocalModelRepository,
) {

    /**
     * Runs the check of [plan].
     *
     * @param plan What to repeat, from [PlanRunVerificationUseCase].
     * @return The check's events; cold, so collecting starts the check and
     *   cancelling the collection stops it between chunks.
     */
    operator fun invoke(plan: VerificationPlan): Flow<VerificationEvent> = flow {
        val total = plan.calls
        var done = 0
        val verdicts = mutableMapOf<Int, NodeVerdict>()
        for ((visitIndex, visit) in plan.visits.withIndex()) {
            if (visit.kind != VisitKind.Repeat) continue
            var verdict: NodeVerdict? = null
            for ((position, call) in visit.calls.withIndex()) {
                val outcome = repeat(call)
                if (outcome is Repeat.Halted) {
                    emit(outcome.event(done, total))
                    return@flow
                }
                val replayed = (outcome as Repeat.Answered).sha256
                done++
                val matched = replayed == call.outputSha256
                emit(
                    VerificationEvent.CallChecked(
                        visitIndex = visitIndex,
                        call = position + 1,
                        of = visit.calls.size,
                        matched = matched,
                        recordedSha256 = call.outputSha256,
                        replayedSha256 = replayed,
                        done = done,
                        total = total,
                    ),
                )
                if (!matched && verdict == null) {
                    verdict = NodeVerdict.Diverged(position + 1, visit.calls.size, call.outputSha256, replayed)
                }
            }
            val settled = verdict ?: NodeVerdict.Matched(visit.calls.size)
            verdicts[visitIndex] = settled
            emit(VerificationEvent.VisitSettled(visitIndex, settled))
        }
        emit(VerificationEvent.Finished(summaryOf(plan, verdicts)))
    }

    /**
     * Repeats one call on the model it ran on.
     *
     * @return The answer's hash, or why the check halts before or after it.
     */
    private suspend fun repeat(call: RunTraceRecord.LocalModelCall): Repeat {
        // A repeated call always names its file (the plan repeats no other kind).
        val path = call.modelPath.orEmpty()
        val name = localModelRepository.findByPath(path)?.name ?: path.substringAfterLast('/')
        return try {
            val loaded = loadModel(path)
            if (loaded is Result.Error) return Repeat.Halted.LoadFailed(name, loaded.message ?: "unknown error")
            engineMismatch(call, name)?.let { return Repeat.Halted.Changed(it) }
            val answer = engine.generateResponseStream(call.prompt, null, call.sampling).toList().joinToString("")
            engineMismatch(call, name)?.let { return Repeat.Halted.Changed(it) }
            Repeat.Answered(TraceHashing.sha256Hex(answer))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repeat.Halted.LoadFailed(name, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** How the engine now differs from what [call] ran on, if it does. */
    private fun engineMismatch(call: RunTraceRecord.LocalModelCall, name: String): VerifyMismatch? {
        val recordedBackend = call.backend
        val recordedWindow = call.contextWindow
        val currentBackend = engine.activeBackend
        val windowDiffers = RunReproducibilityPolicy.windowMatters(call) && engine.activeContextLength != recordedWindow
        return when {
            engine.currentModelPath != call.modelPath -> VerifyMismatch.ModelChanged(name)
            recordedBackend == null || recordedWindow == null || currentBackend == null -> VerifyMismatch.ModelChanged(
                name,
            )
            currentBackend != recordedBackend && windowDiffers ->
                VerifyMismatch.BackendAndWindow(recordedBackend, recordedWindow, currentBackend)
            currentBackend != recordedBackend -> VerifyMismatch.Backend(recordedBackend, currentBackend)
            windowDiffers -> VerifyMismatch.Window(recordedWindow, engine.activeContextLength ?: 0)
            else -> null
        }
    }

    /** What the finished check found. */
    private fun summaryOf(plan: VerificationPlan, verdicts: Map<Int, NodeVerdict>): VerificationSummary {
        val diverged = verdicts.filterValues { it is NodeVerdict.Diverged }.toSortedMap()
        val notVerifiable = plan.visits.count { it.kind is VisitKind.NotVerifiable }
        return when {
            verdicts.isEmpty() -> VerificationSummary.NothingVerifiable
            diverged.isNotEmpty() -> {
                val (first, verdict) = diverged.entries.first()
                verdict as NodeVerdict.Diverged
                VerificationSummary.SomeDiverged(diverged.size, first, verdict.call, verdict.of)
            }
            else -> VerificationSummary.AllMatched(plan.calls, notVerifiable)
        }
    }

    /** What one repeat came to. */
    private sealed interface Repeat {

        /**
         * The call was repeated.
         *
         * @property sha256 SHA-256 of the repeated answer.
         */
        data class Answered(val sha256: String) : Repeat

        /** The check halts at this call. */
        sealed interface Halted : Repeat {

            /** The terminal event for this halt, after [done] of [total] calls. */
            fun event(done: Int, total: Int): VerificationEvent

            /**
             * The engine no longer matches the call.
             *
             * @property mismatch What differs.
             */
            data class Changed(val mismatch: VerifyMismatch) : Halted {
                override fun event(done: Int, total: Int) = VerificationEvent.Stopped(mismatch, done, total)
            }

            /**
             * The model could not load, or the generation failed.
             *
             * @property modelName The model.
             * @property reason Why.
             */
            data class LoadFailed(val modelName: String, val reason: String) : Halted {
                override fun event(done: Int, total: Int) = VerificationEvent.Failed(modelName, reason, done, total)
            }
        }
    }
}
