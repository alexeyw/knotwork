package app.knotwork.android.data.engine

import android.content.ComponentCallbacks2
import android.content.Context
import app.knotwork.android.domain.models.LocalBackend
import app.knotwork.android.domain.models.LocalSampling
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.repositories.SettingsRepository
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for [LiteRTLlmEngine].
 */
class LiteRTLlmEngineTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var engine: LiteRTLlmEngine

    // Drives the engine's fire-and-forget unload path (close / onTrimMemory).
    // An unconfined dispatcher runs the launched unload eagerly on the calling
    // thread until its first real suspension, so the uncontended teardown
    // completes synchronously within the test body.
    private val appScope = CoroutineScope(UnconfinedTestDispatcher())

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.cacheDir } returns File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        settingsRepository = mockk(relaxed = true)
        every { settingsRepository.maxContextLength } returns flowOf(4096)
        every { settingsRepository.localModelBackend } returns flowOf("CPU")
        // Crash-recovery breadcrumb stub: empty / `null` means no prior
        // attempt crashed mid-init, which is the desired baseline for
        // every test in this suite.
        every { settingsRepository.lastInitBackendAttempt } returns flowOf(null)
        every { settingsRepository.localBackendFailureStreak } returns flowOf(0)

        mockkConstructor(Engine::class)
        every { anyConstructed<Engine>().initialize() } returns Unit
        every { anyConstructed<Engine>().close() } returns Unit

        engine = LiteRTLlmEngine(context, settingsRepository, appScope, Dispatchers.IO)
    }

    @After
    fun teardown() {
        engine.close()
        appScope.cancel()
        unmockkAll()
    }

    @Test
    fun `initialize returns Error when model file does not exist`() = runTest {
        val path = "/fake/path/model_does_not_exist.tflite"
        val result = engine.initialize(path)
        assertTrue(result is Result.Error)
        assertTrue((result as Result.Error).message!!.contains("does not exist"))
    }

    @Test
    fun `initialize returns Success when model file exists`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()

        val result = engine.initialize(tempFile.absolutePath)

        assertTrue(result is Result.Success)
        assertEquals(tempFile.absolutePath, engine.currentModelPath)
        assertTrue(engine.isInitialized)

        verify { anyConstructed<Engine>().initialize() }
    }

    @Test
    fun `generateResponseStream throws IllegalStateException when not initialized`() = runTest {
        try {
            engine.generateResponseStream("Hello").toList()
            assert(false) { "Expected IllegalStateException" }
        } catch (e: IllegalStateException) {
            assertEquals("LLM Engine not initialized", e.message)
        }
    }

    @Test
    fun `transcribe throws IllegalStateException when not initialized`() = runTest {
        try {
            engine.transcribe("/cache/audio/clip.wav", "Transcribe this").toList()
            assert(false) { "Expected IllegalStateException" }
        } catch (e: IllegalStateException) {
            assertEquals("LLM Engine not initialized", e.message)
        }
    }

    @Test
    fun `text-only init leaves audio disabled`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()

        engine.initialize(tempFile.absolutePath)

        assertTrue(!engine.isAudioEnabled)
    }

    @Test
    fun `init with enableAudio marks the engine audio-enabled`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()

        val result = engine.initialize(tempFile.absolutePath, enableAudio = true)

        assertTrue(result is Result.Success)
        assertTrue(engine.isAudioEnabled)
    }

    @Test
    fun `an ordinary generation carries the user's sampling settings`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        every { settingsRepository.temperature } returns flowOf(0.25f)
        every { settingsRepository.topK } returns flowOf(7)
        every { settingsRepository.topP } returns flowOf(0.55f)
        val captured = slot<ConversationConfig>()
        val conversation = mockk<Conversation>(relaxed = true)
        completesAtOnce(conversation)
        every { anyConstructed<Engine>().createConversation(capture(captured)) } returns conversation
        engine.initialize(tempFile.absolutePath)

        engine.generateResponseStream("Hello").toList()

        // The whole point of the change: these three used to be read by the
        // settings screen and by nothing else, because the conversation was
        // opened with no SamplerConfig at all.
        val sampler = captured.captured.samplerConfig
        assertEquals(7, sampler?.topK)
        assertEquals(0.55, sampler?.topP ?: 0.0, TOLERANCE)
        assertEquals(0.25, sampler?.temperature ?: 0.0, TOLERANCE)
    }

    @Test
    fun `two generations of the same prompt do not reuse one seed`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        every { settingsRepository.temperature } returns flowOf(0.7f)
        every { settingsRepository.topK } returns flowOf(40)
        every { settingsRepository.topP } returns flowOf(0.9f)
        val captured = mutableListOf<ConversationConfig>()
        val conversation = mockk<Conversation>(relaxed = true)
        completesAtOnce(conversation)
        every { anyConstructed<Engine>().createConversation(capture(captured)) } returns conversation
        engine.initialize(tempFile.absolutePath)

        repeat(SEED_SAMPLE_SIZE) { engine.generateResponseStream("Hello").toList() }

        // Supplying a SamplerConfig means supplying a seed, and the library
        // defaults it to 0. If that were left in place, a repeated prompt could
        // come back byte-identical every time and Regenerate would be useless —
        // so the seed is drawn fresh. Asserted over a sample rather than on one
        // pair: a single collision is possible, ten identical values are not.
        assertEquals(SEED_SAMPLE_SIZE, captured.size)
        assertTrue(
            "every generation reused the same seed",
            captured.mapNotNull { it.samplerConfig?.seed }.distinct().size > 1,
        )
    }

    @Test
    fun `given a run's sampling when generating then the conversation runs on it, not on the settings`() = runTest {
        // A pipeline run records the sampler and seed of every call; the engine
        // must run on exactly those, or the record describes another generation.
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        every { settingsRepository.temperature } returns flowOf(1.9f)
        every { settingsRepository.topK } returns flowOf(7)
        every { settingsRepository.topP } returns flowOf(0.55f)
        val captured = slot<ConversationConfig>()
        val conversation = mockk<Conversation>(relaxed = true)
        completesAtOnce(conversation)
        every { anyConstructed<Engine>().createConversation(capture(captured)) } returns conversation
        engine.initialize(tempFile.absolutePath)

        engine.generateResponseStream(
            "Repair this",
            sampling = LocalSampling(RunSampler(temperature = 0.1, topK = 64, topP = 0.95), seed = 4242),
        ).toList()

        val sampler = captured.captured.samplerConfig
        assertEquals(0.1, sampler?.temperature ?: 0.0, TOLERANCE)
        assertEquals(64, sampler?.topK)
        assertEquals(0.95, sampler?.topP ?: 0.0, TOLERANCE)
        assertEquals(4242, sampler?.seed)
    }

    @Test
    fun `given a loaded model when asked for the context window then it is the one the engine was built with`() =
        runTest {
            // Recorded with every on-device call: on GPU a seed repeats only at the same window.
            val tempFile = File.createTempFile("model", ".tflite")
            tempFile.deleteOnExit()
            assertEquals(null, engine.activeContextLength)

            engine.initialize(tempFile.absolutePath)
            assertEquals(4096, engine.activeContextLength)

            engine.unload()
            assertEquals(null, engine.activeContextLength)
        }

    @Test
    fun `registers component callbacks on init`() {
        verify { context.registerComponentCallbacks(engine) }
    }

    @Test
    fun `onTrimMemory background level unloads engine`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        engine.initialize(tempFile.absolutePath)
        assertTrue(engine.isInitialized)

        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        assertTrue(!engine.isInitialized)
        verify { anyConstructed<Engine>().close() }

        engine.close()
        verify { context.unregisterComponentCallbacks(engine) }
    }

    @Test
    fun `given an unload queued for an older engine when a newer one is loaded then it survives`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        // The unload `onTrimMemory` launches must still be *pending* while the
        // next load completes — that ordering is the whole defect, and an eager
        // dispatcher would hide it by running the unload immediately. A standard
        // (queueing) dispatcher lets the test place the two in the observed
        // order: trim first, reload second, unload delivered last.
        val queued = StandardTestDispatcher(testScheduler)
        val deferredScope = CoroutineScope(queued)
        val subject = LiteRTLlmEngine(context, settingsRepository, deferredScope, Dispatchers.IO)

        subject.initialize(tempFile.absolutePath)
        subject.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        // The trigger-started background run reloads the engine before the
        // queued unload gets to run.
        subject.initialize(tempFile.absolutePath)
        advanceUntilIdle()

        assertTrue(
            "a stale unload must not free the engine a later run just loaded",
            subject.isInitialized,
        )
        deferredScope.cancel()
    }

    @Test
    fun `given a generation in flight when the app is backgrounded then the engine stays loaded`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        engine.initialize(tempFile.absolutePath)
        activeGenerationJob().set(engine, Job())

        // TRIM_MEMORY_BACKGROUND is "you went to background", not memory
        // pressure — and a trigger-started background run arrives in exactly
        // that state, so it must not be torn down.
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        assertTrue("background transition must not unload a working engine", engine.isInitialized)
    }

    @Test
    fun `given a generation in flight when real memory pressure arrives then the engine is freed`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        engine.initialize(tempFile.absolutePath)
        activeGenerationJob().set(engine, Job())

        // Being killed by the OS is worse than losing one generation, so genuine
        // pressure still wins over the running job.
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)

        assertTrue("real pressure must still free the engine", !engine.isInitialized)
    }

    @Test
    fun `given one unfinished init when reloading then CPU is used but the choice is kept`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        every { settingsRepository.localModelBackend } returns flowOf("GPU")
        every { settingsRepository.lastInitBackendAttempt } returns flowOf("GPU")
        every { settingsRepository.localBackendFailureStreak } returns flowOf(0)

        engine.initialize(tempFile.absolutePath)

        // The breadcrumb proves the process died inside the init window, not
        // that the GPU is at fault — a swipe-away or an lmkd kill leaves the
        // same trace. Run on CPU now, but do not touch what the user chose.
        assertEquals(LocalBackend.CPU, engine.activeBackend)
        coVerify(exactly = 1) { settingsRepository.setLocalBackendFailureStreak(1) }
        coVerify(exactly = 0) { settingsRepository.setLocalModelBackend(LocalBackend.CPU.key) }
    }

    @Test
    fun `given two unfinished inits in a row when reloading then the backend is switched for good`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        every { settingsRepository.localModelBackend } returns flowOf("GPU")
        every { settingsRepository.lastInitBackendAttempt } returns flowOf("GPU")
        every { settingsRepository.localBackendFailureStreak } returns flowOf(1)

        engine.initialize(tempFile.absolutePath)

        // Corroborated across two starts — now it is evidence about the hardware.
        assertEquals(LocalBackend.CPU, engine.activeBackend)
        coVerify(exactly = 1) { settingsRepository.setLocalModelBackend(LocalBackend.CPU.key) }
    }

    @Test
    fun `given a successful init when it completes then the failure streak is cleared`() = runTest {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        every { settingsRepository.localModelBackend } returns flowOf("GPU")
        every { settingsRepository.lastInitBackendAttempt } returns flowOf(null)
        every { settingsRepository.localBackendFailureStreak } returns flowOf(0)
        every { settingsRepository.localBackendFailureStreak } returns flowOf(1)

        engine.initialize(tempFile.absolutePath)

        assertEquals(LocalBackend.GPU, engine.activeBackend)
        coVerify { settingsRepository.setLocalBackendFailureStreak(0) }
    }

    @Test
    fun `given no model loaded when asked for the backend then none is reported`() = runTest {
        // Better no hint in the status line than a guessed one.
        assertEquals(null, engine.activeBackend)
    }

    @Test
    fun `given native work in flight when the generation is cancelled then close waits for the native side to end`() =
        runTest {
            val native = NativeStub()
            val subject = loadedSubject(native, StandardTestDispatcher(testScheduler))
            native.endingAfter {
                val collection = launch { subject.generateResponseStream("Hello").collect {} }
                runCurrent()

                // The Stop button lands while the model is still reading the prompt.
                collection.cancel()
                runCurrent()

                // Closing now is the crash: LiteRT-LM nulls the session inside the
                // Conversation's destructor while the prefill callback is still due
                // to dereference it. The work has to be cancelled and seen to end.
                verify { native.conversation.cancelProcess() }
                verify(exactly = 0) { native.conversation.close() }

                native.end()
                advanceUntilIdle()

                verifyOrder {
                    native.conversation.cancelProcess()
                    native.conversation.close()
                }
            }
        }

    @Test
    fun `given the native side keeps running after a cancel when waiting then the cancel is repeated until it ends`() =
        runTest {
            val native = NativeStub()
            val subject = loadedSubject(native, StandardTestDispatcher(testScheduler))
            native.endingAfter {
                val collection = launch { subject.generateResponseStream("Hello").collect {} }
                runCurrent()

                collection.cancel()
                runCurrent()
                // A prefill that finished just before the cancel still starts a decode
                // task, and LiteRT-LM gives every new task a fresh cancel flag — the
                // first cancel never reaches it.
                advanceTimeBy(ONE_SECOND_MS)
                runCurrent()

                verify(atLeast = 2) { native.conversation.cancelProcess() }
                verify(exactly = 0) { native.conversation.close() }

                native.end()
                advanceUntilIdle()

                verify(exactly = 1) { native.conversation.close() }
            }
        }

    @Test
    fun `given a generation that ends normally when it completes then the conversation is closed without a cancel`() =
        runTest {
            val native = NativeStub()
            every { native.conversation.sendMessageAsync(any<String>(), any<MessageCallback>()) } answers {
                val callback = secondArg<MessageCallback>()
                callback.onMessage(Message.model("Hi"))
                callback.onDone()
            }
            val subject = loadedSubject(native, StandardTestDispatcher(testScheduler))

            val chunks = subject.generateResponseStream("Hello").toList()

            assertEquals(listOf("Hi"), chunks)
            verify(exactly = 0) { native.conversation.cancelProcess() }
            verify(exactly = 1) { native.conversation.close() }
        }

    @Test
    fun `given the message is refused synchronously when streaming then the conversation is closed at once`() =
        runTest {
            val native = NativeStub()
            every { native.conversation.sendMessageAsync(any<String>(), any<MessageCallback>()) } throws
                LiteRtLmJniException("Failed to start nativeSendMessageAsync")
            val subject = loadedSubject(native, StandardTestDispatcher(testScheduler))

            val failure = failureOf { subject.generateResponseStream("Hello").toList() }

            // Nothing native was started, so there is nothing to wait for — the
            // close must not sit behind a cancel loop that no callback will end.
            assertTrue(failure is LiteRtLmJniException)
            verify(exactly = 0) { native.conversation.cancelProcess() }
            verify(exactly = 1) { native.conversation.close() }
        }

    @Test
    fun `given the native side reports an error mid-stream when collecting then the error reaches the caller`() =
        runTest {
            val native = NativeStub()
            every { native.conversation.sendMessageAsync(any<String>(), any<MessageCallback>()) } answers {
                secondArg<MessageCallback>().onError(LiteRtLmJniException("Native error 13: decode failed"))
            }
            val subject = loadedSubject(native, StandardTestDispatcher(testScheduler))

            val failure = failureOf { subject.generateResponseStream("Hello").toList() }

            assertTrue(failure is LiteRtLmJniException)
            verify(exactly = 0) { native.conversation.cancelProcess() }
            verify(exactly = 1) { native.conversation.close() }
        }

    @Test
    fun `given a generation in flight when memory pressure unloads then teardown waits for the native side`() =
        runTest {
            val native = NativeStub()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val subject = loadedSubject(native, dispatcher, CoroutineScope(dispatcher))
            native.endingAfter {
                launch { subject.generateResponseStream("Hello").collect {} }
                runCurrent()

                subject.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE)
                runCurrent()

                // The unload queues behind the generation, and the generation will not
                // let go while the native side is still running.
                verify(exactly = 0) { native.conversation.close() }
                verify(exactly = 0) { anyConstructed<Engine>().close() }

                native.end()
                advanceUntilIdle()

                verifyOrder {
                    native.conversation.cancelProcess()
                    native.conversation.close()
                    anyConstructed<Engine>().close()
                }
            }
        }

    /** Reflective handle on the engine's private in-flight generation job. */
    private fun activeGenerationJob() = LiteRTLlmEngine::class.java.getDeclaredField("activeGenerationJob")
        .apply { isAccessible = true }

    @Test
    fun `given an image when generating then the multimodal message is streamed through the callback`() = runTest {
        val native = NativeStub()
        answersContentsWith(native.conversation, "A cat")
        val subject = loadedSubject(native, StandardTestDispatcher(testScheduler))

        val chunks = subject.generateResponseStream("Describe it", imagePath = "/cache/images/cat.png").toList()

        // The Flow overload would compile here too — the lambda's result is
        // discarded — and would never send the message at all, leaving the
        // stream waiting forever. Only the callback overload reaches the model.
        assertEquals(listOf("A cat"), chunks)
        verify(exactly = 1) { native.conversation.close() }
    }

    @Test
    fun `given an audio clip when transcribing then the message is streamed through the callback`() = runTest {
        val native = NativeStub()
        answersContentsWith(native.conversation, "Hello there")
        val subject = loadedSubject(native, StandardTestDispatcher(testScheduler))

        val chunks = subject.transcribe("/cache/audio/clip.wav", "Transcribe this").toList()

        assertEquals(listOf("Hello there"), chunks)
        verify(exactly = 1) { native.conversation.close() }
    }

    /**
     * Stubs the multimodal overload of [conversation] to answer [text] and end.
     *
     * @param conversation The mocked conversation.
     * @param text The single response chunk.
     */
    private fun answersContentsWith(conversation: Conversation, text: String) {
        every { conversation.sendMessageAsync(any<Contents>(), any<MessageCallback>()) } answers {
            val callback = secondArg<MessageCallback>()
            callback.onMessage(Message.model(text))
            callback.onDone()
        }
    }

    /**
     * Runs [block] and returns the [LiteRtLmJniException] it threw, or `null`.
     *
     * @param block The generation to run.
     * @return The native failure, or `null` when [block] completed.
     */
    private suspend fun failureOf(block: suspend () -> Unit): LiteRtLmJniException? = try {
        block()
        null
    } catch (e: LiteRtLmJniException) {
        e
    }

    /** Stubs [conversation] so every message ends at once with `onDone`. */
    private fun completesAtOnce(conversation: Conversation) {
        every { conversation.sendMessageAsync(any<String>(), any<MessageCallback>()) } answers {
            secondArg<MessageCallback>().onDone()
        }
    }

    /**
     * Builds an engine on [dispatcher] with a model loaded and [native]'s
     * conversation behind it.
     *
     * @param native The conversation stub every generation receives.
     * @param dispatcher The dispatcher the generation stream runs on.
     * @param scope The application scope, for tests that drive an unload.
     * @return The loaded engine.
     */
    private suspend fun loadedSubject(
        native: NativeStub,
        dispatcher: CoroutineDispatcher,
        scope: CoroutineScope = appScope,
    ): LiteRTLlmEngine {
        val tempFile = File.createTempFile("model", ".tflite")
        tempFile.deleteOnExit()
        every { settingsRepository.temperature } returns flowOf(0.7f)
        every { settingsRepository.topK } returns flowOf(40)
        every { settingsRepository.topP } returns flowOf(0.9f)
        every { anyConstructed<Engine>().createConversation(any()) } returns native.conversation
        return LiteRTLlmEngine(context, settingsRepository, scope, dispatcher).also {
            it.initialize(tempFile.absolutePath)
        }
    }

    /**
     * A conversation whose native side never answers on its own: the message is
     * accepted and its [MessageCallback] captured, so the test decides when the
     * native work ends.
     */
    private class NativeStub {
        /** The mocked LiteRT-LM conversation. */
        val conversation: Conversation = mockk(relaxed = true)

        private val captured = slot<MessageCallback>()

        init {
            every { conversation.sendMessageAsync(any<String>(), capture(captured)) } just Runs
            // The Flow overloads cannot report that native work ended once the
            // collector is gone, and a discarded Flow never sends the message at
            // all. Using one is the regression, so it fails loudly here instead
            // of leaving the engine waiting for a callback that never comes.
            every { conversation.sendMessageAsync(any<String>()) } throws AssertionError(FLOW_OVERLOAD_USED)
            every { conversation.sendMessageAsync(any<Contents>()) } throws AssertionError(FLOW_OVERLOAD_USED)
        }

        /**
         * Delivers the terminal callback LiteRT-LM sends for a cancelled task, if
         * a message was sent. Safe to repeat: the engine ignores every terminal
         * callback after the first.
         */
        fun end() {
            if (captured.isCaptured) {
                captured.captured.onError(CancellationException("Task cancelled"))
            }
        }

        /**
         * Runs [block], then [end]s the native work whatever happened. A failed
         * assertion then fails the test instead of leaving the engine waiting on
         * a native side nobody will end, which would hang the whole test run.
         *
         * @param block The test body.
         */
        inline fun endingAfter(block: () -> Unit) {
            try {
                block()
            } finally {
                end()
            }
        }
    }

    private companion object {
        /** Float-to-double widening slack for the sampler assertions. */
        const val TOLERANCE = 1e-6

        /** Generations sampled when asserting the seed varies. */
        const val SEED_SAMPLE_SIZE = 10

        /** Virtual time comfortably past one re-cancel interval. */
        const val ONE_SECOND_MS = 1_000L

        /** Failure raised when the engine sends through a Flow overload. */
        const val FLOW_OVERLOAD_USED = "sendMessageAsync must use the MessageCallback overload"
    }
}
