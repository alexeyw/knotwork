package app.knotwork.android.data.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import app.knotwork.android.di.ApplicationScope
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.repositories.SettingsRepository
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * An implementation of [LlmInferenceEngine] using the specialized LiteRT-LM library.
 *
 * This engine manages the lifecycle of the LiteRT-LM [Engine], which is optimized
 * specifically for Large Language Models (LLMs) on edge devices.
 */
@Singleton
class LiteRTLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : LlmInferenceEngine,
    ComponentCallbacks2 {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var _currentModelPath: String? = null
    private var _isVisionEnabled: Boolean = false
    private var _isAudioEnabled: Boolean = false

    /**
     * Backend the live engine was actually built with — set on every successful
     * init, cleared on teardown. May differ from the persisted preference when
     * crash-recovery downgraded this session to CPU.
     */
    @Volatile
    private var _activeBackend: LocalBackend? = null

    /**
     * The coroutine [Job] of the generation currently holding [generationMutex],
     * or `null` when none is streaming. Captured so a memory-pressure unload
     * ([onTrimMemory] / [onLowMemory]) can **cancel** the in-flight decode rather
     * than wait for it — otherwise `unload()` would block on the mutex for the
     * whole generation and free nothing while the OS demands memory now.
     * `@Volatile` because it is written on the generation coroutine and read from
     * the system `ComponentCallbacks2` thread.
     */
    @Volatile
    private var activeGenerationJob: Job? = null

    /**
     * Identity of the currently-loaded native engine, incremented on every
     * successful [initialize].
     *
     * A memory-pressure unload cannot run inline — it has to queue behind
     * [generationMutex] — so by the time it acquires the lock the engine it was
     * asked to release may already be gone and a **different** one loaded in its
     * place. Without this counter the deferred unload happily tore down the new
     * engine, and the run that had just loaded it failed with "Engine is not
     * initialized" milliseconds later. Observed on device during a directed
     * on-device test: a trigger-started background run loaded the
     * engine at `03:09:08.461` and a unload queued 13 seconds earlier freed it at
     * `03:09:08.596`.
     *
     * `@Volatile` for the same reason as [activeGenerationJob]: written on
     * coroutine threads, read from the system `ComponentCallbacks2` thread.
     */
    @Volatile
    private var loadGeneration: Long = 0L

    /**
     * Serialises every native-session access: each [generateResponseStream]
     * stream, [initialize] (engine construction) and [unload] (engine teardown).
     * LiteRT-LM allows only one active [Conversation], and each generation tears
     * the previous conversation down before opening a fresh one — so two
     * overlapping generations (e.g. a foreground pipeline and a background
     * memory-extraction pass) would corrupt each other's session. Just as
     * dangerously, freeing the engine/conversation (idle unload, low-battery,
     * `onTrimMemory`) while a generation is mid-stream is a native
     * use-after-free. Holding this single mutex across generation, load and
     * unload guarantees the native handles are never read on one path while
     * being freed or rebuilt on another.
     */
    private val generationMutex = Mutex()

    /**
     * Indicates whether the engine has been successfully initialized and is ready for use.
     */
    override val isInitialized: Boolean get() = engine != null

    /**
     * Returns the file path of the currently loaded model, or null if no model is loaded.
     */
    override val currentModelPath: String? get() = _currentModelPath

    /**
     * Whether the loaded engine was constructed with its vision backend enabled.
     */
    override val isVisionEnabled: Boolean get() = _isVisionEnabled

    /**
     * Whether the loaded engine was constructed with its audio backend enabled.
     */
    override val isAudioEnabled: Boolean get() = _isAudioEnabled

    /**
     * The backend the loaded engine is really running on, or `null` when no
     * model is loaded.
     */
    override val activeBackend: LocalBackend? get() = _activeBackend

    init {
        context.registerComponentCallbacks(this)
    }

    /**
     * Internal error mapping implementation for system/unknown errors.
     */
    private object LlmSystemError : AppError.System

    /**
     * Initializes the LiteRT-LM engine by configuring its options with the
     * specified model path.
     *
     * @param modelPath The exact path to the locally downloaded model file.
     * @param enableVision When `true`, the engine is built with a vision backend
     *   (mirroring the compute [Backend]) and a one-image budget so a
     *   vision-capable model can read an attached image; when `false` the vision
     *   backend is left unset, keeping text-only runs lean. The flag is recorded
     *   in [isVisionEnabled] so the loader can detect a needed mode switch.
     * @param enableAudio When `true`, the engine is built with an audio backend
     *   (mirroring the compute [Backend]) so a multimodal model can transcribe an
     *   audio clip; when `false` the audio backend is left unset. The flag is
     *   recorded in [isAudioEnabled] so the loader can detect a needed mode switch.
     * @return [Result.Success] on successful initialization, or [Result.Error] on failure.
     */
    override suspend fun initialize(
        modelPath: String,
        enableVision: Boolean,
        enableAudio: Boolean,
    ): Result<Unit, AppError> = withContext(Dispatchers.IO) {
        try {
            // Hold the native-session mutex across the whole (re-)initialization
            // so the check-then-build inside [initializeInternal] is atomic with
            // respect to generations and to any concurrent load — two racing
            // loads cannot interleave a teardown with another's freshly built
            // engine, and the second observes the first's result.
            generationMutex.withLock {
                initializeInternal(modelPath, enableVision, enableAudio).also { outcome ->
                    // Stamp a new engine identity on **every** successful load —
                    // including the reuse fast-path, which returns without
                    // touching the native handles. Reuse still means a caller
                    // has just asserted it needs this engine, so an unload
                    // queued before that assertion is stale and must not be
                    // honoured (see [loadGeneration]).
                    if (outcome is Result.Success) {
                        loadGeneration += 1
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch `Throwable` (not just `Exception`) so JVM-side
            // `Error`s thrown by the LiteRT JNI layer (e.g.
            // `UnsatisfiedLinkError`, `AssertionError`) also land here
            // instead of escaping to the default uncaught-exception
            // handler and killing the process. The crash-recovery
            // breadcrumb stays set on disk so the next cold-start
            // auto-falls back to CPU.
            Timber.e(e, "Failed to initialize LiteRTLlmEngine")
            _currentModelPath = null
            _isVisionEnabled = false
            _isAudioEnabled = false
            Result.Error(
                error = LlmSystemError,
                message = e.localizedMessage ?: "Unknown initialization error",
                throwable = e,
            )
        }
    }

    /**
     * Performs the actual engine construction. Split out from [initialize] so the
     * `Dispatchers.IO` + try/catch boundary stays in one place while the body
     * reads top-to-bottom.
     *
     * @param modelPath The exact path to the locally downloaded model file.
     * @param enableVision Whether to configure the vision backend (see [initialize]).
     * @param enableAudio Whether to configure the audio backend (see [initialize]).
     * @return [Result.Success] on successful initialization, or [Result.Error] on failure.
     */
    private suspend fun initializeInternal(
        modelPath: String,
        enableVision: Boolean,
        enableAudio: Boolean,
    ): Result<Unit, AppError> {
        val file = File(modelPath)
        if (!file.exists()) {
            val errorMsg = "Model file does not exist at path: $modelPath"
            Timber.e(errorMsg)
            _currentModelPath = null
            _isVisionEnabled = false
            _isAudioEnabled = false
            return Result.Error(
                error = LlmSystemError,
                message = errorMsg,
            )
        }

        // Atomic reuse check (runs under `generationMutex`, held by [initialize]):
        // if the engine is already built for this exact model and modality set,
        // a concurrent load that raced to here observes the finished result and
        // skips a redundant teardown/rebuild. The caller (`LoadModelUseCase`)
        // does an unlocked pre-check, so this is the race-safe confirmation.
        if (
            engine != null &&
            _currentModelPath == modelPath &&
            _isVisionEnabled == enableVision &&
            _isAudioEnabled == enableAudio
        ) {
            return Result.Success(Unit)
        }

        // Close existing engine if present to release previous resources. Uses
        // the non-locking teardown because the mutex is already held here.
        unloadInternal()

        val maxTokens = settingsRepository.maxContextLength.first()
        val configuredKey = settingsRepository.localModelBackend.first()
        val configured = LocalBackend.fromKey(configuredKey) ?: LocalBackend.CPU

        // Crash-recovery: if the previous attempt died mid-init with this exact
        // backend (sentinel still set), run this session on CPU. The native
        // LiteRT dispatch failure ("No dispatch library found …" for GPU / NPU
        // on devices that don't ship one) can SIGABRT before Kotlin try/catch
        // fires, so the attempt has to be gated before it starts.
        //
        // The sentinel proves the process died in that window — not *why*. Being
        // swiped from recents, reclaimed by the low-memory killer or frozen by
        // an OEM leaves exactly the same trace as a broken driver. So the first
        // strike only downgrades **this session** and leaves the user's saved
        // choice alone; the backend is overwritten for good only once a second
        // consecutive start finds the same evidence. One unexplained kill
        // silently and permanently costing the device its GPU is the failure
        // this guards against.
        val previousAttempt = settingsRepository.lastInitBackendAttempt.first()
        val crashedLastTime = configured != LocalBackend.CPU && previousAttempt == configured.key
        val resolved = if (crashedLastTime) {
            val streak = settingsRepository.localBackendFailureStreak.first() + 1
            settingsRepository.setLocalBackendFailureStreak(streak)
            if (streak >= BACKEND_FAILURE_STREAK_LIMIT) {
                Timber.w(
                    "LiteRT backend '%s' failed to initialise %d starts in a row — switching to CPU for good.",
                    configured.key,
                    streak,
                )
                settingsRepository.setLocalModelBackend(LocalBackend.CPU.key)
                settingsRepository.setLocalBackendFailureStreak(0)
            } else {
                Timber.w(
                    "Previous init with '%s' did not finish — running on CPU this session, '%s' stays selected.",
                    configured.key,
                    configured.key,
                )
            }
            settingsRepository.setLastInitBackendAttempt(null)
            LocalBackend.CPU
        } else {
            configured
        }

        // Drop the breadcrumb before invoking the native engine.
        // Cleared after a successful init (and after the recovery
        // path above forces CPU). CPU is intentionally not gated:
        // the CPU backend ships in-process and cannot fail to find
        // its dispatch library.
        if (resolved != LocalBackend.CPU) {
            settingsRepository.setLastInitBackendAttempt(resolved.key)
        } else {
            settingsRepository.setLastInitBackendAttempt(null)
        }

        val backend = newBackend(resolved)

        // The vision encoder runs on a fresh backend instance of the same
        // compute family as text (LiteRT-LM fixes the vision backend at engine
        // construction). A text-only init leaves it `null` so no vision encoder
        // is loaded. `maxNumImages` mirrors the one-attachment-per-message
        // contract of this phase.
        val visionBackend = if (enableVision) newBackend(resolved) else null

        // The audio encoder, like vision, runs on a fresh backend instance of the
        // same compute family and is fixed at engine construction (LiteRT-LM
        // exposes no `maxNumAudio` budget — the audio backend's presence alone
        // enables transcription). A non-audio init leaves it `null`.
        val audioBackend = if (enableAudio) newBackend(resolved) else null

        // Initialize Engine Configuration
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = visionBackend,
            audioBackend = audioBackend,
            maxNumTokens = maxTokens,
            maxNumImages = if (enableVision) MAX_NUM_IMAGES else null,
            cacheDir = context.cacheDir.absolutePath,
        )

        // Create Engine from config and initialize
        engine = Engine(config).apply {
            initialize()
        }
        _currentModelPath = modelPath
        _isVisionEnabled = enableVision
        _isAudioEnabled = enableAudio
        // Init succeeded — clear the crash-recovery breadcrumb so the
        // next launch trusts the persisted backend, and drop the failure
        // streak: whatever killed earlier attempts is evidently not fatal.
        settingsRepository.setLastInitBackendAttempt(null)
        settingsRepository.setLocalBackendFailureStreak(0)
        _activeBackend = resolved
        Timber.i(
            "LiteRT-LM Engine successfully initialized with $modelPath " +
                "(vision=$enableVision, audio=$enableAudio)",
        )

        return Result.Success(Unit)
    }

    /**
     * Generates a response stream from the LLM based on the provided prompt.
     *
     * @param prompt The input text prompt for the LLM.
     * @param imagePath Absolute path of an image to send with [prompt], or `null`
     *   for a text-only generation. When non-`null` the message is built as an
     *   ordered [Contents] of the image (`Content.ImageFile`) followed by the
     *   text, which requires the engine to have been initialized with
     *   `enableVision = true`; the caller (`LiteRtNodeExecutor` via
     *   `LoadModelUseCase`) guarantees that before issuing an image generation.
     * @param temperature Optional sampling-temperature override. When `null` the
     *   conversation is opened with the sampler the user configured in Settings
     *   (Generation → Temperature / Top-K / Top-P) — the ordinary path. When
     *   non-`null` the conversation is opened with a deterministic-leaning
     *   [SamplerConfig] at the requested temperature, overriding the user's
     *   values — used by the structured-output repair loop.
     * @return A [Flow] of strings representing the generated tokens as they are produced.
     */
    override fun generateResponseStream(prompt: String, imagePath: String?, temperature: Float?): Flow<String> =
        // With an image, the message is a multimodal [Contents] (image then text);
        // without, the plain-string overload keeps the text path byte-identical.
        streamConversation(temperature) { conversation ->
            if (imagePath == null) {
                conversation.sendMessageAsync(prompt)
            } else {
                conversation.sendMessageAsync(Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)))
            }
        }

    /**
     * Transcribes a single audio clip into text. Runs through the same
     * single-conversation [streamConversation] discipline as
     * [generateResponseStream] but builds the message from an audio file
     * (`Content.AudioFile`) followed by the transcription instruction, which
     * requires the engine to have been initialized with `enableAudio = true`
     * (guaranteed by `LoadModelUseCase` loading the model in audio mode first).
     * Transcription is a pre-pipeline step: the resulting text, not the audio,
     * is what later travels the execution graph.
     *
     * @param audioPath Absolute path of the audio clip (16 kHz mono PCM WAV).
     * @param prompt The rendered transcription instruction.
     * @return A [Flow] of transcript token chunks, emitted on [Dispatchers.IO].
     */
    override fun transcribe(audioPath: String, prompt: String): Flow<String> =
        streamConversation(temperature = null) { conversation ->
            conversation.sendMessageAsync(Contents.of(Content.AudioFile(audioPath), Content.Text(prompt)))
        }

    /**
     * Shared single-conversation streaming discipline for [generateResponseStream]
     * and [transcribe]. Serialises on [generationMutex] so closing/recreating the
     * single LiteRT-LM [Conversation] and streaming its tokens never interleaves
     * with another concurrent generation (which would tear down an in-flight
     * session), opens a fresh conversation carrying a [SamplerConfig] (the
     * user's Settings values, or the repair sampler when [temperature] is
     * non-`null`), sends the caller-built message, and re-emits each chunk's
     * [Content.Text] parts as they arrive.
     *
     * @param temperature Optional sampling-temperature override (see
     *   [generateResponseStream]); `null` uses the user's configured sampler.
     * @param openResponses Builds and sends the message on the freshly opened
     *   [Conversation], returning the LiteRT-LM response [Message] stream.
     * @return A [Flow] of generated text chunks, emitted on [Dispatchers.IO].
     */
    private fun streamConversation(temperature: Float?, openResponses: (Conversation) -> Flow<Message>): Flow<String> =
        flow {
            generationMutex.withLock {
                // Read the native engine handle inside the lock so it cannot be
                // freed by a concurrent unload between the null-check and use.
                val currentEngine = engine
                if (currentEngine == null) {
                    Timber.e("Engine is not initialized")
                    throw IllegalStateException("LLM Engine not initialized")
                }

                // LiteRT-LM allows only one active session. The orchestrator supplies
                // the full history every time, so we close the old conversation and
                // open a fresh one to prevent token accumulation and OOM crashes.
                // Resolved after the initialization check, so an uninitialized
                // engine still fails on that and not on a settings read. The
                // mutex is held for the whole decode anyway, so one cached
                // DataStore read inside it costs nothing measurable.
                val conversationConfig = if (temperature == null) {
                    userConversationConfig()
                } else {
                    repairConversationConfig(temperature)
                }
                conversation?.close()
                val activeConversation = currentEngine.createConversation(conversationConfig)
                conversation = activeConversation
                val generationJob = currentCoroutineContext()[Job]
                activeGenerationJob = generationJob
                try {
                    openResponses(activeConversation).collect { chunk ->
                        val text = chunk.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString(separator = "") { it.text }
                        if (text.isNotEmpty()) {
                            emit(text)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error during conversation streaming")
                    throw e
                } finally {
                    // Close the native session on completion AND on cancellation
                    // (Stop button, scope death) so a cancelled generation never
                    // leaves a live conversation resident until the next call.
                    activeConversation.close()
                    if (conversation === activeConversation) {
                        conversation = null
                    }
                    if (activeGenerationJob === generationJob) {
                        activeGenerationJob = null
                    }
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Unloads the engine from memory, releasing heavy resources. Acquires
     * [generationMutex] so the native handles are never freed while a generation
     * is mid-stream — the call waits for any in-flight generation to finish or
     * cancel before tearing down.
     */
    override suspend fun unload() = generationMutex.withLock { unloadInternal() }

    /**
     * Performs the actual native teardown **without** acquiring [generationMutex].
     * Callers must already hold the mutex (the public [unload] and the
     * re-initialization path in [initializeInternal]). The mutex is not
     * reentrant, so this split is what lets `initialize` release-then-rebuild
     * under a single lock acquisition without self-deadlocking.
     */
    private fun unloadInternal() {
        try {
            conversation?.close()
            engine?.close()
            Timber.i("LiteRT-LM engine unloaded successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error unloading LiteRT-LM engine")
        } finally {
            conversation = null
            engine = null
            _currentModelPath = null
            _isVisionEnabled = false
            _isAudioEnabled = false
            _activeBackend = null
        }
    }

    /**
     * Closes the engine and removes the callbacks to prevent leaks.
     *
     * Invoked from synchronous Android lifecycle callbacks (e.g. the foreground
     * service's `onDestroy`), so the mutex-guarded [unload] is dispatched on the
     * application scope rather than blocked on; the callback de-registration runs
     * immediately to stop further trim callbacks.
     */
    override fun close() {
        appScope.launch { unload() }
        context.unregisterComponentCallbacks(this)
    }

    /**
     * Called by the system when the device configuration changes while your component is running.
     *
     * @param newConfig The new device configuration.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        // No action needed
    }

    /**
     * This is called when the overall system is running low on memory, and actively running processes should trim their memory usage.
     * Unloads the engine to free up resources.
     */
    override fun onLowMemory() {
        Timber.w("onLowMemory called, unloading engine")
        // Cancel any in-flight decode first so `unload()` doesn't block on the
        // mutex for the whole generation — under memory pressure the model must
        // be freed promptly, not after the current answer finishes.
        cancelActiveGeneration()
        appScope.launch { unload() }
    }

    /**
     * Cancels the generation currently holding [generationMutex], if any. The
     * cancelled stream runs its `finally` (closing the native conversation) and
     * releases the mutex, letting a pending memory-pressure [unload] acquire it
     * and free the engine without waiting for the full decode.
     */
    private fun cancelActiveGeneration() {
        activeGenerationJob?.cancel(CancellationException("Engine unload requested under memory pressure"))
    }

    /**
     * Called when the operating system has determined that it is a good time for a process to trim unneeded memory from its process.
     * Unloads the engine if the memory trim level is critical.
     *
     * @param level The context of the trim, giving a hint of the amount of trimming the application may like to perform.
     */
    override fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return

        // TRIM_MEMORY_BACKGROUND means "you just went to background and are a
        // candidate for killing" — it is a lifecycle signal, not actual memory
        // pressure. A trigger-started background run arrives in exactly that
        // state, and it is *not* the "agent inactive in background" case the
        // deallocation rule targets, so working through it must not be cancelled.
        // Genuine pressure (MODERATE and above) still wins over the running job:
        // being killed by the OS is worse than losing one generation.
        val underRealPressure = level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
        if (!underRealPressure && activeGenerationJob != null) {
            Timber.i("onTrimMemory level %d ignored: a generation is in flight", level)
            return
        }

        Timber.w("onTrimMemory called with level %d, unloading engine", level)
        // Cancel the in-flight decode so the memory-pressure unload frees the
        // engine promptly instead of waiting for the whole generation; the
        // cancelled stream still tears its native session down safely in its
        // `finally`, and the unload then acquires the released mutex.
        cancelActiveGeneration()
        val target = loadGeneration
        appScope.launch { unloadGeneration(target) }
    }

    /**
     * Unloads the engine **only if** it is still the one identified by [target].
     *
     * The check happens under [generationMutex], i.e. at the moment teardown
     * would actually run, which is the only point where "is this still the engine
     * I was asked to free?" can be answered truthfully. When the identity has
     * moved on, a newer [initialize] owns the native handles and freeing them
     * here would break whoever loaded them — the F4 failure mode.
     *
     * @param target the [loadGeneration] value captured when the unload was requested.
     */
    private suspend fun unloadGeneration(target: Long) = generationMutex.withLock {
        if (loadGeneration != target) {
            Timber.i("Stale unload for engine generation %d skipped; %d is live", target, loadGeneration)
            return@withLock
        }
        unloadInternal()
    }

    /**
     * Builds a fresh LiteRT-LM [Backend] instance for the given [LocalBackend].
     * Used for both the compute backend and (when vision is enabled) the vision
     * backend, so the GPU/NPU/CPU mapping lives in exactly one place.
     *
     * @param backend The resolved on-device execution backend.
     * @return A new [Backend] of the matching family.
     */
    private fun newBackend(backend: LocalBackend): Backend = when (backend) {
        LocalBackend.GPU -> Backend.GPU()
        LocalBackend.NPU -> Backend.NPU()
        LocalBackend.CPU -> Backend.CPU()
    }

    /**
     * Builds the [ConversationConfig] for an ordinary generation: a
     * [SamplerConfig] carrying the user's Settings values (Generation →
     * Temperature / Top-K / Top-P).
     *
     * Reading all three here is not a style choice. LiteRT-LM's [SamplerConfig]
     * has no defaults for `topK` / `topP` / `temperature` and no "override one
     * field" path, so the moment any one of them is honoured all three must be
     * supplied — which is exactly why the three sliders reached nothing before:
     * the conversation was opened with no config at all.
     *
     * `seed` is drawn fresh per conversation. It is the one field the library
     * does default (to `0`), and what a fixed seed means is decided native-side,
     * below this API — so pinning it would risk making every answer to a
     * repeated prompt identical, and "Regenerate" useless. A fresh value keeps
     * the variability the model had when no config was passed, whatever the
     * native default happens to be.
     *
     * @return A conversation config carrying the user's sampler.
     */
    private suspend fun userConversationConfig(): ConversationConfig = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = settingsRepository.topK.first(),
            topP = settingsRepository.topP.first().toDouble(),
            temperature = settingsRepository.temperature.first().toDouble(),
            seed = Random.nextInt(from = 1, until = Int.MAX_VALUE),
        ),
    )

    /**
     * Builds a [ConversationConfig] whose [SamplerConfig] applies [temperature]
     * over conventional top-k / top-p values.
     *
     * LiteRT-LM exposes the sampler only as an all-or-nothing [SamplerConfig], so
     * a temperature override cannot leave the user's other sampler fields in
     * place — they must be re-specified here. The chosen top-k / top-p are the
     * conventional Gemma-family decode values; the override is only ever used for
     * the structured-output repair loop, where the low [temperature] already
     * biases generation towards near-deterministic, schema-obedient output, and
     * where the user's creative settings are precisely what must not apply.
     *
     * @param temperature The repair sampling temperature to apply.
     * @return A conversation config carrying the repair [SamplerConfig].
     */
    private fun repairConversationConfig(temperature: Float): ConversationConfig = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = REPAIR_SAMPLER_TOP_K,
            topP = REPAIR_SAMPLER_TOP_P,
            temperature = temperature.toDouble(),
            seed = REPAIR_SAMPLER_SEED,
        ),
    )

    private companion object {
        /**
         * Per-message image budget when vision is enabled. This phase carries a
         * single attachment per user message, so one image suffices.
         */
        const val MAX_NUM_IMAGES: Int = 1

        /**
         * Top-k for the temperature-override (repair) sampler. Conventional
         * Gemma-family value; paired with [REPAIR_SAMPLER_TOP_P] it is only ever
         * exercised by the structured-output repair loop.
         */
        const val REPAIR_SAMPLER_TOP_K: Int = 64

        /** Top-p (nucleus) for the temperature-override (repair) sampler. */
        const val REPAIR_SAMPLER_TOP_P: Double = 0.95

        /**
         * Fixed seed for the repair sampler so a corrective re-inference is
         * reproducible for a given prompt — desirable when chasing down a model
         * that keeps emitting the same malformed shape.
         */
        const val REPAIR_SAMPLER_SEED: Int = 0

        /**
         * Consecutive unfinished inits with the same non-CPU backend before the
         * persisted preference is overwritten with CPU.
         *
         * Two, not one: the crash breadcrumb records *that* the process died
         * inside the init window, never *why*, and a swipe-away or a low-memory
         * kill leaves the identical trace as a broken driver. One strike still
         * downgrades the current session — a genuinely crashing backend must not
         * be retried into a boot loop — but only a second consecutive strike is
         * accepted as proof about the hardware.
         */
        const val BACKEND_FAILURE_STREAK_LIMIT: Int = 2
    }
}
