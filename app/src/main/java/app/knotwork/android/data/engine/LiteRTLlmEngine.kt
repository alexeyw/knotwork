package app.knotwork.android.data.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import app.knotwork.android.di.ApplicationScope
import app.knotwork.android.di.IoDispatcher
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.repositories.GenerationSettings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * @property ioDispatcher Dispatcher for engine construction and for the native
 *   generation stream. Injected so a test can order a cancellation against the
 *   native callbacks deterministically.
 */
@Singleton
class LiteRTLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val generationSettings: GenerationSettings,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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
     * Context window the live engine was built with — set on every successful
     * init, cleared on teardown. May differ from the current setting, which is
     * read only at load time.
     */
    @Volatile
    private var _activeContextLength: Int? = null

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

    /**
     * The context window the loaded engine was built with, or `null` when no
     * model is loaded.
     */
    override val activeContextLength: Int? get() = _activeContextLength

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
    ): Result<Unit, AppError> = withContext(ioDispatcher) {
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
     * [ioDispatcher] + try/catch boundary stays in one place while the body
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
            Timber.e("Model file does not exist at path: %s", modelPath)
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

        val maxTokens = generationSettings.maxContextLength.first()
        val configuredKey = generationSettings.localModelBackend.first()
        val configured = LocalBackend.runnableFromKey(configuredKey)

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
        val previousAttempt = generationSettings.lastInitBackendAttempt.first()
        val crashedLastTime = configured != LocalBackend.CPU && previousAttempt == configured.key
        val resolved = if (crashedLastTime) {
            val streak = generationSettings.localBackendFailureStreak.first() + 1
            generationSettings.setLocalBackendFailureStreak(streak)
            if (streak >= BACKEND_FAILURE_STREAK_LIMIT) {
                Timber.w(
                    "LiteRT backend '%s' failed to initialise %d starts in a row — switching to CPU for good.",
                    configured.key,
                    streak,
                )
                generationSettings.setLocalModelBackend(LocalBackend.CPU.key)
                generationSettings.setLocalBackendFailureStreak(0)
            } else {
                Timber.w(
                    "Previous init with '%s' did not finish — running on CPU this session, '%s' stays selected.",
                    configured.key,
                    configured.key,
                )
            }
            generationSettings.setLastInitBackendAttempt(null)
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
            generationSettings.setLastInitBackendAttempt(resolved.key)
        } else {
            generationSettings.setLastInitBackendAttempt(null)
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
        generationSettings.setLastInitBackendAttempt(null)
        generationSettings.setLocalBackendFailureStreak(0)
        _activeBackend = resolved
        _activeContextLength = maxTokens
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
     * @param sampling The sampler and seed to open the conversation with — a
     *   pipeline run's, recorded with the call — or `null` to open it with the
     *   user's sampler and a fresh seed (see [userConversationConfig]).
     * @return A [Flow] of strings representing the generated tokens as they are produced.
     */
    override fun generateResponseStream(prompt: String, imagePath: String?, sampling: LocalSampling?): Flow<String> =
        // With an image, the message is a multimodal [Contents] (image then text);
        // without, the plain-string overload keeps the text path byte-identical.
        streamConversation(sampling) { conversation, callback ->
            if (imagePath == null) {
                conversation.sendMessageAsync(prompt, callback)
            } else {
                conversation.sendMessageAsync(Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)), callback)
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
     * @return A [Flow] of transcript token chunks, emitted on [ioDispatcher].
     */
    override fun transcribe(audioPath: String, prompt: String): Flow<String> =
        streamConversation(sampling = null) { conversation, callback ->
            conversation.sendMessageAsync(Contents.of(Content.AudioFile(audioPath), Content.Text(prompt)), callback)
        }

    /**
     * Shared single-conversation streaming discipline for [generateResponseStream]
     * and [transcribe]. Serialises on [generationMutex] so closing/recreating the
     * single LiteRT-LM [Conversation] and streaming its tokens never interleaves
     * with another concurrent generation (which would tear down an in-flight
     * session), opens a fresh conversation carrying a [SamplerConfig] (the
     * caller's [sampling], or the user's Settings values and a fresh seed when it
     * is `null`), sends the caller-built message, and re-emits each chunk's
     * [Content.Text] parts as they arrive.
     *
     * **The conversation is closed only once its native work has ended.** A
     * stream that stops early — Stop, a cancelled run, a memory-pressure unload —
     * cancels the native work and waits for LiteRT-LM's terminal callback before
     * closing (see [awaitNativeWorkEnded]). Closing any earlier crashes the
     * process: the Conversation's native destructor clears its session and then
     * waits for pending tasks, and a prefill finishing inside that wait calls
     * into the cleared session. This is why the callback overload of
     * `sendMessageAsync` is used — the Flow overload never reports that its
     * native work has ended once the collector is gone.
     *
     * @param sampling The sampler and seed to use (see [generateResponseStream]);
     *   `null` uses the user's configured sampler and a fresh seed.
     * @param sendMessage Builds the message and sends it on the freshly opened
     *   [Conversation], reporting the response through the given
     *   [MessageCallback].
     * @return A [Flow] of generated text chunks, emitted on [ioDispatcher].
     */
    private fun streamConversation(
        sampling: LocalSampling?,
        sendMessage: (Conversation, MessageCallback) -> Unit,
    ): Flow<String> = flow {
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
            val conversationConfig = sampling?.let(::conversationConfigOf) ?: userConversationConfig()
            conversation?.close()
            val activeConversation = currentEngine.createConversation(conversationConfig)
            conversation = activeConversation
            val generationJob = currentCoroutineContext()[Job]
            activeGenerationJob = generationJob
            val response = NativeResponse()
            var nativeWorkStarted = false
            try {
                sendMessage(activeConversation, response)
                nativeWorkStarted = true
                for (chunk in response.messages) {
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
                // leaves a live conversation resident until the next call —
                // but never while its native work is still running. A send
                // that threw started nothing, so there is nothing to wait for.
                withContext(NonCancellable) {
                    if (nativeWorkStarted) {
                        awaitNativeWorkEnded(activeConversation, response)
                    }
                    activeConversation.close()
                }
                if (conversation === activeConversation) {
                    conversation = null
                }
                if (activeGenerationJob === generationJob) {
                    activeGenerationJob = null
                }
            }
        }
    }.flowOn(ioDispatcher)

    /**
     * Cancels [conversation]'s native work and suspends until LiteRT-LM reports
     * it ended, so the conversation can be closed safely.
     *
     * Returns at once when [response] already received its terminal callback —
     * the ordinary end of a generation. Otherwise it cancels repeatedly, every
     * [RECANCEL_INTERVAL_MS], because one cancel is not enough: LiteRT-LM marks
     * only the tasks that exist at the moment of the cancel, and a prefill that
     * finished just before it still starts a decode task with a fresh, unset
     * cancel flag. A prefill that is already running is not interrupted either;
     * the terminal callback arrives when it ends.
     *
     * There is deliberately no upper bound on the wait: closing before the
     * native side ends is the crash this function exists to prevent, and how
     * long a long prefill runs on a slow device is not known. The cost is that
     * native work that never ends would hold [generationMutex] until the process
     * restarts. A warning is logged every [STILL_WAITING_LOG_EVERY] attempts so
     * such a hang is visible.
     *
     * @param conversation The conversation whose native work is cancelled.
     * @param response The callback the work reports its terminal state to.
     */
    private suspend fun awaitNativeWorkEnded(conversation: Conversation, response: NativeResponse) {
        var attempts = 0
        while (!response.ended.isCompleted) {
            conversation.cancelProcess()
            attempts += 1
            withTimeoutOrNull(RECANCEL_INTERVAL_MS) { response.ended.await() }
            if (!response.ended.isCompleted && attempts % STILL_WAITING_LOG_EVERY == 0) {
                Timber.w("Native generation still running after %d cancel attempts; conversation stays open", attempts)
            }
        }
    }

    /**
     * Receives one message's response from LiteRT-LM on its native threads and
     * hands it to the generation coroutine.
     *
     * [ended] completes on the terminal callback — `onDone` or `onError`, exactly
     * one of which LiteRT-LM delivers — and is completed **before** [messages] is
     * closed, so a stream that finished normally never looks unfinished to
     * [awaitNativeWorkEnded].
     */
    private class NativeResponse : MessageCallback {
        /** Response chunks in arrival order; closed with the error on failure. */
        val messages = Channel<Message>(Channel.UNLIMITED)

        /** Completes once the native side has reported its terminal state. */
        val ended = CompletableDeferred<Unit>()

        /**
         * Forwards one response chunk.
         *
         * @param message The chunk LiteRT-LM produced.
         */
        override fun onMessage(message: Message) {
            messages.trySend(message)
        }

        /** Marks the response complete and ends the chunk stream. */
        override fun onDone() {
            ended.complete(Unit)
            messages.close()
        }

        /**
         * Marks the response ended and fails the chunk stream with [throwable].
         *
         * @param throwable The native failure; a `CancellationException` when
         *   the work was cancelled.
         */
        override fun onError(throwable: Throwable) {
            ended.complete(Unit)
            messages.close(throwable)
        }
    }

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
            _activeContextLength = null
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
     * cancelled stream runs its `finally` — cancelling the native work, waiting
     * for it to end ([awaitNativeWorkEnded]) and closing the conversation — and
     * releases the mutex, letting a pending memory-pressure [unload] acquire it
     * and free the engine without waiting for the full decode. A prefill that is
     * already running still finishes first: LiteRT-LM does not interrupt it.
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
        // cancelled stream waits for its native work to end and closes the
        // conversation in its `finally`, and the unload then acquires the
        // released mutex.
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
        // Never resolved — `runnableFromKey` reads a withdrawn NPU choice as CPU — and
        // a CPU engine is what LiteRT built for it anyway on this app's builds.
        LocalBackend.NPU, LocalBackend.CPU -> Backend.CPU()
    }

    /**
     * Builds the [ConversationConfig] for a generation outside a pipeline run: a
     * [SamplerConfig] carrying the user's Settings values (Generation →
     * Temperature / Top-K / Top-P) and a fresh seed.
     *
     * Reading all three here is not a style choice. LiteRT-LM's [SamplerConfig]
     * has no defaults for `topK` / `topP` / `temperature` and no "override one
     * field" path, so the moment any one of them is honoured all three must be
     * supplied — which is exactly why the three sliders reached nothing before:
     * the conversation was opened with no config at all.
     *
     * The seed is fresh for every such conversation. A fixed seed is not random
     * native-side: on CPU and GPU the same seed, sampler, model file and input
     * give the same text byte for byte — and `0`, the library's default, is an
     * ordinary fixed seed (`decisions.md §70.6`). That is what a pipeline run
     * relies on: it passes its own sampling, with a seed derived from the run
     * seed, so its calls can be recorded and repeated, and a retry draws a new
     * run seed. Work outside a run records nothing, so it keeps the variability a
     * fresh seed gives.
     *
     * @return A conversation config carrying the user's sampler and a fresh seed.
     */
    private suspend fun userConversationConfig(): ConversationConfig = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = generationSettings.topK.first(),
            topP = generationSettings.topP.first().toDouble(),
            temperature = generationSettings.temperature.first().toDouble(),
            seed = Random.nextInt(from = 1, until = Int.MAX_VALUE),
        ),
    )

    /**
     * Builds the [ConversationConfig] for [sampling] — exactly its sampler and
     * seed, nothing read from settings, so the conversation is the one the run
     * records.
     *
     * @param sampling The sampler and seed to open the conversation with.
     * @return A conversation config carrying them.
     */
    private fun conversationConfigOf(sampling: LocalSampling): ConversationConfig = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = sampling.sampler.topK,
            topP = sampling.sampler.topP,
            temperature = sampling.sampler.temperature,
            seed = sampling.seed,
        ),
    )

    private companion object {
        /**
         * Per-message image budget when vision is enabled. This phase carries a
         * single attachment per user message, so one image suffices.
         */
        const val MAX_NUM_IMAGES: Int = 1

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

        /**
         * Pause between cancels while waiting for native work to end. Short enough
         * that a decode started after the first cancel is stopped within a few
         * tokens; each cancel is a single flag write under LiteRT-LM's task lock.
         */
        const val RECANCEL_INTERVAL_MS: Long = 200L

        /** Cancel attempts between "still running" warnings — ten seconds apart. */
        const val STILL_WAITING_LOG_EVERY: Int = 50
    }
}
