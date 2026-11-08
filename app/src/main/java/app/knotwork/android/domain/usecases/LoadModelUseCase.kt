package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.repositories.LocalModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * UseCase for loading a model into the inference engine.
 * Supports loading a specific model by path, or the default active model.
 */
class LoadModelUseCase @Inject constructor(
    private val localModelRepository: LocalModelRepository,
    private val llmInferenceEngine: LlmInferenceEngine,
) {

    /** The error of a load that failed for a reason with no type of its own. */
    private object LlmSystemError : AppError.System

    /**
     * Retrieves the specified model or the currently active model from the repository, checks if the file exists on disk,
     * and initializes the LLM engine with the model path.
     *
     * If the requested model is already loaded in the engine — and already in the
     * vision/audio modes the caller needs — it returns [Result.Success]
     * immediately. A modality-carrying run ([requireVision] / [requireAudio] =
     * `true`) against an engine currently loaded without that backend forces a
     * re-initialization, since the runtime fixes its vision and audio backends at
     * construction time. Vision and audio are independent: a transcription
     * ([requireAudio] = `true`) keeps any already-enabled vision backend for the
     * same model rather than downgrading it, and vice versa.
     *
     * @param modelPath Optional absolute path to the model file. If null, the globally active model is used.
     * @param requireVision When `true`, the engine must be (re-)initialized with
     *   its vision backend so the run can attach an image. Defaults to `false`
     *   for every text-only run. The pre-flight send guard guarantees the active
     *   model is vision-capable before a `true` load is requested.
     * @param requireAudio When `true`, the engine must be (re-)initialized with
     *   its audio backend so the active model can transcribe an audio clip.
     *   Defaults to `false`. The voice-input pre-flight guarantees the active
     *   model is audio-capable before a `true` load is requested.
     * @return [Result.Success] if the model was successfully loaded, or [Result.Error] otherwise.
     */
    suspend operator fun invoke(
        modelPath: String? = null,
        requireVision: Boolean = false,
        requireAudio: Boolean = false,
    ): Result<Unit, AppError> = withContext(Dispatchers.IO) {
        try {
            // Blank `modelPath` is the "Active model" sentinel persisted by
            // the LITE_RT node form — fall back to `getActiveModel()` just
            // like the null case. Centralising the coercion here keeps every
            // executor's call site uniform.
            val requestedPath = modelPath?.takeIf { it.isNotBlank() }
            val pathToLoad = requestedPath ?: localModelRepository.getActiveModel()?.path
                ?: return@withContext Result.Error(
                    error = ModelLoadError.NoActiveModel,
                    message = "No active model found. Please select a model in settings or provide a path.",
                )

            // Reuse the loaded engine only when it already covers the requested
            // modalities — an image run needs vision on and a transcription
            // needs audio on, but a run that needs neither is happy to reuse an
            // already-enabled engine (no point downgrading). The `!require* ||`
            // ordering short-circuits so each modality flag is only consulted
            // when that modality is actually required.
            val visionModeSatisfied = !requireVision || llmInferenceEngine.isVisionEnabled
            val audioModeSatisfied = !requireAudio || llmInferenceEngine.isAudioEnabled
            if (
                llmInferenceEngine.isInitialized &&
                llmInferenceEngine.currentModelPath == pathToLoad &&
                visionModeSatisfied &&
                audioModeSatisfied
            ) {
                return@withContext Result.Success(Unit)
            }

            val file = File(pathToLoad)
            if (!file.exists()) {
                return@withContext Result.Error(
                    error = ModelLoadError.FileMissing(pathToLoad),
                    message = "Model file not found at: $pathToLoad. Please download it again.",
                )
            }

            // Keep vision on if it was already enabled for THIS model (a prior
            // image run). Re-initialization is a full model reload (unload +
            // reconstruct the engine), so downgrading just to drop the vision
            // encoder would cost far more than the memory it reclaims — the
            // encoder is released instead when the engine is unloaded on
            // background / memory-trim. A model switch resets to the requested
            // mode, so vision is never forced onto a freshly loaded text-only model.
            val sameModel = llmInferenceEngine.currentModelPath == pathToLoad
            val enableVision = requireVision || (llmInferenceEngine.isVisionEnabled && sameModel)
            val enableAudio = requireAudio || (llmInferenceEngine.isAudioEnabled && sameModel)
            return@withContext llmInferenceEngine.initialize(
                pathToLoad,
                enableVision = enableVision,
                enableAudio = enableAudio,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.Error(
                error = LlmSystemError,
                message = e.localizedMessage ?: "Unknown error while loading model",
                throwable = e,
            )
        }
    }
}

/**
 * Why [LoadModelUseCase] could not load a model, for the failures a person can act on.
 *
 * The error's `message` keeps the English sentence a run records; a screen words these
 * types in the interface language instead. Every other failure — the engine refusing the
 * file, an exception — carries an error of no type of its own and its message as written.
 */
sealed interface ModelLoadError : AppError.System {

    /** No path was given and no model is active: only installing or activating one helps. */
    data object NoActiveModel : ModelLoadError

    /**
     * The model's file is gone from the device.
     *
     * @property path Where the file was expected.
     */
    data class FileMissing(val path: String) : ModelLoadError
}
