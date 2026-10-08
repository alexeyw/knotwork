package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.HardwareAccelerationProbe
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.repositories.GenerationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Picks the on-device execution backend for a first-time install and warms the
 * inference handle on it.
 *
 * Why this exists: the persisted default is [LocalBackend.CPU], so without this
 * step the very first run after onboarding's model download — the moment the
 * product is judged on — takes the slowest available path. On the reference
 * device the GPU backend is roughly five times faster on decode, which is the
 * difference between a snappy first answer and a wait.
 *
 * Flipping the default outright is not an option: LiteRT-LM exposes no
 * availability API, `Backend.GPU()` constructs happily on devices with no
 * OpenCL implementation, and the eventual native failure can abort the process
 * before Kotlin ever sees it. So the decision is made in three guarded steps:
 *
 *  1. **Never override the user.** A stored preference — even one equal to the
 *     default — means the choice has been made; this use case only warms up.
 *  2. **Static probe first** ([HardwareAccelerationProbe]). No OpenCL, no GPU
 *     attempt: the decision is recorded as CPU so the probe never runs again.
 *  3. **Verify by actually generating.** A device can ship OpenCL and still
 *     fail to run the model, and an init that merely *succeeds* proves nothing
 *     (the documented failure mode surfaces on the first generation). The probe
 *     therefore reuses [TestBackendUseCase] — a real load plus a short fixed
 *     prompt — and any failure silently reverts to CPU.
 *
 * The engine is [LlmInferenceEngine.unload]ed before the CPU retry because the
 * engine reuses a loaded handle keyed by model path and modalities alone: a
 * reload after a *successful* GPU init but failed generation would otherwise be
 * a no-op and leave the run on the very backend that just failed.
 *
 * The residual risk — a GPU init that aborts the process natively — is covered
 * by the pre-existing `lastInitBackendAttempt` breadcrumb, which forces CPU on
 * the next start. That machinery is deliberately left in the engine's hands
 * rather than duplicated here.
 *
 * @property generationSettings Reads the raw backend preference and records the
 *   resolved decision.
 * @property accelerationProbe Static, crash-safe GPU plausibility check.
 * @property loadModelUseCase Plain warm-up path (no measurement).
 * @property testBackendUseCase Load + short generation; doubles as the warm-up
 *   on the GPU path and persists its numbers to the Settings probe row.
 * @property llmInferenceEngine Torn down before a fallback reload so the new
 *   backend is actually applied.
 */
class PrepareInferenceBackendUseCase @Inject constructor(
    private val generationSettings: GenerationSettings,
    private val accelerationProbe: HardwareAccelerationProbe,
    private val loadModelUseCase: LoadModelUseCase,
    private val testBackendUseCase: TestBackendUseCase,
    private val llmInferenceEngine: LlmInferenceEngine,
) {

    /**
     * Terminal outcome of the preparation, as far as the caller's UI cares.
     */
    sealed interface Outcome {

        /**
         * The model is loaded and ready to answer.
         *
         * @property backend The backend the handle ended up running on.
         */
        data class Warmed(val backend: LocalBackend) : Outcome

        /**
         * Warm-up failed; the caller surfaces [message] and offers a retry.
         *
         * @property message Human-readable failure text, or `null` when the
         *   underlying error carried none.
         */
        data class Failed(val message: String?) : Outcome
    }

    /**
     * Resolves the backend (when it has never been chosen) and warms the model.
     *
     * @param modelPath Absolute path of the model to warm up.
     * @param onAccelerationCheckStarted Invoked — off the main thread — just
     *   before the GPU verification generation begins, so the caller can show
     *   an honest "checking acceleration" state for the extra second it costs.
     *   Never invoked when no verification happens.
     * @return [Outcome.Warmed] with the backend actually in use, or
     *   [Outcome.Failed] when even the CPU path could not load the model.
     */
    suspend operator fun invoke(modelPath: String, onAccelerationCheckStarted: () -> Unit = {}): Outcome =
        withContext(Dispatchers.IO) {
            try {
                resolveAndWarmUp(modelPath, onAccelerationCheckStarted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deciding must never end worse than not deciding at all. The
                // decision path writes to settings, which can fail on its own
                // (a full or unwritable data dir) — and this runs inside the
                // onboarding warm-up, where an escaping exception would take
                // the user's very first run down with it.
                Timber.w(e, "Backend preparation failed — falling back to CPU.")
                fallBackToCpu(modelPath)
            }
        }

    private suspend fun resolveAndWarmUp(modelPath: String, onAccelerationCheckStarted: () -> Unit): Outcome {
        val stored = generationSettings.localModelBackendPreference.first()
        if (stored != null) {
            return warmUp(modelPath, LocalBackend.runnableFromKey(stored))
        }

        if (!accelerationProbe.isGpuAvailable()) {
            generationSettings.setLocalModelBackend(LocalBackend.CPU.key)
            return warmUp(modelPath, LocalBackend.CPU)
        }

        onAccelerationCheckStarted()
        generationSettings.setLocalModelBackend(LocalBackend.GPU.key)
        val probe = testBackendUseCase(modelPath)
        if (probe.success) {
            Timber.i(
                "GPU backend verified in %d ms (%d tokens) — keeping it as the default.",
                probe.durationMs,
                probe.tokensGenerated,
            )
            return Outcome.Warmed(LocalBackend.GPU)
        }

        Timber.w("GPU backend verification failed (%s) — falling back to CPU.", probe.errorMessage)
        return fallBackToCpu(modelPath)
    }

    /**
     * Records CPU, drops any live handle, and warms up again.
     *
     * The teardown is load-bearing: the engine may hold a handle built on the
     * backend that just failed, and its reuse check compares model path and
     * modalities but not the backend — so without it the reload would be a
     * no-op and leave the run exactly where it broke.
     */
    private suspend fun fallBackToCpu(modelPath: String): Outcome {
        persistBackendQuietly(LocalBackend.CPU)
        llmInferenceEngine.unload()
        return warmUp(modelPath, LocalBackend.CPU)
    }

    /**
     * Records the resolved backend, absorbing a storage failure: by this point
     * the fallback is already happening, and failing to *write down* the safe
     * choice is no reason to deny the user a working handle. The cost of the
     * lost write is one repeated probe on the next journey.
     */
    private suspend fun persistBackendQuietly(backend: LocalBackend) {
        try {
            generationSettings.setLocalModelBackend(backend.key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not persist the resolved backend.")
        }
    }

    private suspend fun warmUp(modelPath: String, backend: LocalBackend): Outcome =
        when (val result = loadModelUseCase(modelPath)) {
            is Result.Success -> Outcome.Warmed(backend)
            is Result.Error -> Outcome.Failed(result.message)
        }
}
