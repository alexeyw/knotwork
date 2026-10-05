package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.Result
import kotlinx.coroutines.flow.Flow

/**
 * Interface representing the LLM Inference Engine.
 *
 * This engine is responsible for loading a local LLM from the device's storage,
 * generating text based on a prompt, and providing responses either as a complete
 * string or as a stream of tokens.
 */
interface LlmInferenceEngine {

    /**
     * Initializes the LLM engine with the given model path.
     *
     * @param modelPath The absolute path to the model file on the device.
     * @param enableVision When `true`, the engine is configured with a vision
     *   backend so the model can accept an image alongside the text prompt (see
     *   [generateResponseStream]). Defaults to `false`: a text-only run never
     *   pays the extra vision-encoder memory cost, and only a run carrying an
     *   image (gated by the pre-flight vision check) requests `true`. Toggling
     *   this relative to the currently loaded mode forces a re-initialization —
     *   the underlying runtime fixes the vision backend at engine-construction
     *   time.
     * @param enableAudio When `true`, the engine is configured with an audio
     *   backend so the model can transcribe an audio clip (see [transcribe]).
     *   Defaults to `false` for the same reason as [enableVision]: only the
     *   voice-input transcription step (gated by the active model's audio-support
     *   flag) requests `true`, and toggling it relative to the loaded mode forces
     *   a re-initialization, since the runtime fixes the audio backend at
     *   engine-construction time.
     * @return A [Result] indicating success or containing an [AppError] if initialization failed.
     */
    suspend fun initialize(
        modelPath: String,
        enableVision: Boolean = false,
        enableAudio: Boolean = false,
    ): Result<Unit, AppError>

    /**
     * Returns true if the LLM engine is currently initialized with a model.
     */
    val isInitialized: Boolean

    /**
     * Returns the absolute path of the currently loaded model, or null if none is loaded.
     */
    val currentModelPath: String?

    /**
     * Whether the currently loaded engine was initialized with its vision
     * backend enabled (`enableVision = true` on the last [initialize]). `false`
     * when no model is loaded or the model was loaded text-only. Used by
     * `LoadModelUseCase` to decide whether an image-carrying run needs a
     * vision-enabling re-initialization of an already-loaded model.
     */
    val isVisionEnabled: Boolean

    /**
     * Whether the currently loaded engine was initialized with its audio
     * backend enabled (`enableAudio = true` on the last [initialize]). `false`
     * when no model is loaded or the model was loaded without audio. Used by
     * `LoadModelUseCase` to decide whether a transcription request needs an
     * audio-enabling re-initialization of an already-loaded model.
     */
    val isAudioEnabled: Boolean

    /**
     * The backend the loaded engine is **actually running on**, or `null` when
     * no model is loaded.
     *
     * Deliberately distinct from the backend saved in settings: the two diverge
     * whenever the engine falls back at load time (a previous init that never
     * finished downgrades the session to CPU). Reporting the saved preference as
     * if it were the live one hides that divergence — the user believes they are
     * on GPU while every token is decoded on CPU. Surfaced in the chat status
     * line so the running backend is observable without reading logs.
     */
    val activeBackend: LocalBackend?

    /**
     * The context window, in tokens, the loaded engine was built with, or `null`
     * when no model is loaded.
     *
     * Read from settings at load time and fixed until the next load, so it can
     * differ from the setting's current value. Recorded with every on-device call
     * of a run: on GPU the same seed reproduces the same text only with the same
     * window (`decisions.md §70.6`).
     */
    val activeContextLength: Int?

    /**
     * Transcribes a single audio clip into text using the loaded multimodal
     * model. This is a **preprocessing** step that runs *before* any pipeline:
     * voice input never travels the execution graph — only the resulting text
     * does, as an ordinary editable message. It therefore has its own entry
     * point rather than overloading [generateResponseStream] (whose
     * image/sampling contract belongs to graph inference).
     *
     * @param audioPath Absolute filesystem path of the audio clip to transcribe
     *   (a 16 kHz mono PCM WAV produced by the recorder, or a copy of a picked
     *   audio file). Requires the engine to have been initialized with
     *   [initialize]'s `enableAudio = true`, which the caller guarantees by
     *   loading the model in audio mode before issuing a transcription.
     * @param prompt The transcription instruction sent alongside the audio (the
     *   rendered transcription system prompt).
     * @return A [Flow] of strings streaming the transcript tokens as they are
     *   produced; the caller joins them into the final transcript text.
     */
    fun transcribe(audioPath: String, prompt: String): Flow<String>

    /**
     * Generates a response stream from the LLM based on the provided prompt.
     *
     * @param prompt The input text prompt for the LLM.
     * @param imagePath Absolute filesystem path of a single image to send
     *   alongside [prompt]. When `null` (every text-only call) generation is
     *   purely textual. When non-`null` the engine prepends the image to the
     *   prompt content; this requires the engine to have been initialized with
     *   [initialize]'s `enableVision = true`, which the caller guarantees by
     *   loading the model in vision mode before issuing an image generation.
     * @param sampling The sampler and seed to generate with, or `null` for the
     *   engine's own choice: the user's sampler (Settings → Generation) and a
     *   fresh random seed. A pipeline run always passes one — the run's sampler
     *   and a seed derived from the run seed, or the fixed repair sampling — so
     *   the call can be recorded and repeated (see [NodeInference]); `null` is
     *   for work outside a run, where nothing is recorded.
     * @return A [Flow] of strings representing the generated tokens as they are produced.
     */
    fun generateResponseStream(
        prompt: String,
        imagePath: String? = null,
        sampling: LocalSampling? = null,
    ): Flow<String>

    /**
     * Closes the engine and releases any underlying resources.
     *
     * Should be called when the engine is no longer needed to prevent memory leaks.
     */
    fun close()

    /**
     * Unloads the engine from memory, releasing heavy resources without fully destroying the manager.
     *
     * Used for temporary memory relief (e.g., when the app goes into the background or onTrimMemory).
     *
     * Suspends because tearing down the native session is serialised against any
     * in-flight generation: freeing the native handle while a generation is still
     * streaming would be a use-after-free. The call therefore waits for a running
     * generation to finish (or be cancelled) before releasing.
     */
    suspend fun unload()
}
