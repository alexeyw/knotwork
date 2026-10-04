package app.knotwork.android.data.engine.retry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.engine.retry.CollectingCloudRetryListener
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CloudRetryWrapper]: the settings it reads and the [RetryingCloudLlmClient]
 * it builds. The transient failure is simulated at the client layer — a thrown error whose
 * message alone says what happened, with no HTTP status, so the policy judges it by the text
 * rules it keeps for such failures. The status-carrying cases are in [CloudRetryPolicyTest],
 * and the wire-level ones in [CloudRetryEndToEndTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudRetryWrapperTest {

    private val model = LLModel(provider = LLMProvider.OpenAI, id = "test-model", capabilities = emptyList())
    private val samplePrompt: Prompt = prompt("t") { user("hi") }

    private fun settings(maxAttempts: Int, baseDelayMs: Long = 10L): SettingsRepository = mockk(relaxed = true) {
        every { cloudRetryMaxAttempts } returns flowOf(maxAttempts)
        every { cloudRetryBaseDelayMs } returns flowOf(baseDelayMs)
    }

    @Test
    fun `given attempts of 1 when wrap then returns the raw client unchanged`() = runTest {
        val wrapper = CloudRetryWrapper(settings(maxAttempts = 1))
        val raw = FakeLLMClient(behaviour = { flowOf(StreamFrame.TextDelta("ok")) })

        val wrapped = wrapper.wrap(raw, provider = "openai")

        assertSame("retries disabled ⇒ no wrapping", raw, wrapped)
    }

    @Test
    fun `given a transient failure then a success when streaming then retried and observed`() = runTest {
        val wrapper = CloudRetryWrapper(settings(maxAttempts = 3))
        var call = 0
        val raw = FakeLLMClient(behaviour = {
            call++
            flow {
                if (call == 1) throw RuntimeException("429 Too Many Requests")
                emit(StreamFrame.TextDelta("recovered"))
            }
        })
        val listener = CollectingCloudRetryListener()

        val wrapped = wrapper.wrap(raw, provider = "openai", listener = listener)
        val frames = wrapped.executeStreaming(samplePrompt, model).toList()

        assertEquals(1, frames.size)
        assertEquals("recovered", (frames.single() as StreamFrame.TextDelta).text)
        // The initial call plus one retry: exactly one retry reported.
        assertEquals(1, listener.attempts.size)
        assertEquals("Cloud retry 1/2 for openai", listener.attempts.single().consoleMessage())
    }

    @Test
    fun `given an authentication failure when streaming then not retried`() = runTest {
        val wrapper = CloudRetryWrapper(settings(maxAttempts = 4))
        var call = 0
        val raw = FakeLLMClient(behaviour = {
            call++
            flow<StreamFrame> { throw RuntimeException("401 invalid api key") }
        })
        val listener = CollectingCloudRetryListener()

        val wrapped = wrapper.wrap(raw, provider = "openai", listener = listener)

        var thrown: Throwable? = null
        try {
            wrapped.executeStreaming(samplePrompt, model).toList()
        } catch (e: RuntimeException) {
            thrown = e
        }
        assertTrue("auth failure propagates", thrown is RuntimeException)
        assertEquals("auth errors are not retryable", 1, call)
        assertTrue("no retries observed", listener.attempts.isEmpty())
    }

    @Test
    fun `given cancellation when streaming then propagated and not retried`() = runTest {
        val wrapper = CloudRetryWrapper(settings(maxAttempts = 4))
        var call = 0
        val raw = FakeLLMClient(behaviour = {
            call++
            flow<StreamFrame> { throw CancellationException("cancelled") }
        })

        val wrapped = wrapper.wrap(raw, provider = "openai", listener = CloudRetryListener.NONE)

        var thrown: Throwable? = null
        try {
            wrapped.executeStreaming(samplePrompt, model).toList()
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue("cancellation propagates", thrown is CancellationException)
        assertEquals("cancellation is never retried", 1, call)
    }

    /**
     * Minimal [LLMClient] whose streaming behaviour is supplied per call, so a
     * test can script transient failures and recovery. Only the members the
     * retry path touches are meaningfully implemented.
     */
    private class FakeLLMClient(private val behaviour: () -> Flow<StreamFrame>) : LLMClient() {
        override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            error("unused")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            behaviour()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
        override fun close() = Unit
    }
}
