package app.knotwork.android.data.engine.retry

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [RetryingCloudLlmClient]: the loop around the policy — when it waits, what it
 * reports, and what it throws.
 */
class RetryingCloudLlmClientTest {

    private val model = LLModel(provider = LLMProvider.OpenAI, id = "m", capabilities = emptyList())
    private val samplePrompt: Prompt = prompt("t") { user("hi") }

    private val answer = mockk<Message.Assistant>(relaxed = true)
    private val pauses = mutableListOf<Duration>()
    private val events = mutableListOf<String>()
    private val listener = CloudRetryListener { provider, attempt, maxRetries ->
        events += "retry $attempt/$maxRetries $provider"
    }

    private fun client(raw: LLMClient, slot: RetryAfterSlot? = null) = RetryingCloudLlmClient(
        delegate = raw,
        provider = "groq",
        policy = CloudRetryPolicy(
            maxAttempts = 3,
            initialDelay = 100.milliseconds,
            maxDelay = 30.seconds,
            jitterFactor = 0.0,
        ),
        listener = listener,
        retryAfter = slot,
        pause = {
            pauses += it
            events += "pause $it"
        },
    )

    private fun http(status: Int, body: String = "{}") =
        KoogHttpClientException(clientName = "c", statusCode = status, errorBody = body)

    @Test
    fun `given a retryable failure when streaming then it waits, reports and retries in that order`() = runTest {
        var call = 0
        val raw = FakeClient(stream = {
            call++
            flow {
                if (call == 1) throw http(503)
                emit(StreamFrame.TextDelta("ok"))
            }
        })

        val frames = client(raw).executeStreaming(samplePrompt, model).toList()

        assertEquals(1, frames.size)
        assertEquals(listOf("pause 100ms", "retry 1/2 groq"), events)
    }

    @Test
    fun `given a failure after the first frame when streaming then it is not retried`() = runTest {
        // A retry would send the caller the same text twice.
        val raw = FakeClient(stream = {
            flow {
                emit(StreamFrame.TextDelta("half"))
                throw http(503)
            }
        })

        val thrown = runCatchingFailure { client(raw).executeStreaming(samplePrompt, model).toList() }

        assertTrue(thrown is KoogHttpClientException)
        assertTrue("no wait, no retry", events.isEmpty())
    }

    @Test
    fun `given a header recorded for a failed attempt when deciding then that wait is used`() = runTest {
        val slot = RetryAfterSlot()
        var call = 0
        val raw = FakeClient(execute = {
            call++
            if (call == 1) {
                slot.record("2")
                throw http(429)
            }
            answer
        })

        client(raw, slot).execute(samplePrompt, model)

        assertEquals(listOf(2.seconds), pauses)
    }

    @Test
    fun `given a header left by an earlier attempt when a later one fails without one then it is not reused`() =
        runTest {
            val slot = RetryAfterSlot()
            var call = 0
            val raw = FakeClient(execute = {
                call++
                when (call) {
                    1 -> {
                        slot.record("2")
                        throw http(429)
                    }
                    2 -> throw http(503)
                    else -> answer
                }
            })

            client(raw, slot).execute(samplePrompt, model)

            // The second wait is the backoff for retry 2, not the first answer's header again.
            assertEquals(listOf(2.seconds, 200.milliseconds), pauses)
        }

    @Test
    fun `given a 429 asking for more than the ceiling when executing then it fails at once saying so`() = runTest {
        val cause = http(429, """{"error":"Please try again in 1m2.5s."}""")
        val raw = FakeClient(execute = { throw cause })

        val thrown = runCatchingFailure { client(raw).execute(samplePrompt, model) }

        assertTrue(thrown is RetryAfterExceededException)
        assertEquals(
            "'groq' is rate-limiting requests and asked to wait 63 s, longer than the 30 s the app waits. " +
                "The step failed: run it again after that.",
            thrown.message,
        )
        assertSame(cause, thrown.cause)
        assertTrue("nothing waited", pauses.isEmpty())
    }

    @Test
    fun `given a 503 asking for more than the ceiling when executing then it is not called rate limiting`() = runTest {
        val slot = RetryAfterSlot()
        val raw = FakeClient(execute = {
            slot.record("120")
            throw http(503)
        })

        val thrown = runCatchingFailure { client(raw, slot).execute(samplePrompt, model) }

        assertEquals(
            "'groq' asked to wait 120 s, longer than the 30 s the app waits. " +
                "The step failed: run it again after that.",
            thrown.message,
        )
    }

    @Test
    fun `given a failure that is not retryable when executing then the original error is thrown untouched`() = runTest {
        val cause = http(401)
        val raw = FakeClient(execute = { throw cause })

        val thrown = runCatchingFailure { client(raw).execute(samplePrompt, model) }

        assertSame(cause, thrown)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `given every attempt fails when executing then the last error is thrown after the budget`() = runTest {
        var call = 0
        val raw = FakeClient(execute = {
            call++
            throw http(503, "attempt $call")
        })

        val thrown = runCatchingFailure { client(raw).execute(samplePrompt, model) }

        assertEquals(3, call)
        assertEquals(503, (thrown as KoogHttpClientException).statusCode)
        assertEquals(listOf("pause 100ms", "retry 1/2 groq", "pause 200ms", "retry 2/2 groq"), events)
    }

    @Test
    fun `given cancellation when streaming then it propagates and is never retried`() = runTest {
        var call = 0
        val raw = FakeClient(stream = {
            call++
            flow { throw CancellationException("cancelled") }
        })

        val thrown = runCatchingFailure(allowCancellation = true) {
            client(raw).executeStreaming(samplePrompt, model).toList()
        }

        assertTrue(thrown is CancellationException)
        assertEquals(1, call)
    }

    @Test
    fun `given an embedding fails transiently when embedding then it is retried`() = runTest {
        var call = 0
        val raw = FakeClient(embed = {
            call++
            if (call == 1) throw http(502)
            listOf(0.5)
        })

        assertEquals(listOf(0.5), client(raw).embed("text", model))
        assertEquals(2, call)
    }

    private suspend fun runCatchingFailure(allowCancellation: Boolean = false, block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: CancellationException) {
            if (allowCancellation) return e
            throw e
        } catch (e: Exception) {
            return e
        }
        fail("expected a failure")
        error("unreachable")
    }

    /** An [LLMClient] whose operations are scripted per test; unscripted ones fail loudly. */
    private class FakeClient(
        private val stream: () -> Flow<StreamFrame> = { error("stream unused") },
        private val execute: () -> Message.Assistant = { error("execute unused") },
        private val embed: () -> List<Double> = { error("embed unused") },
    ) : LLMClient() {
        override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            execute()
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
            stream()
        override suspend fun embed(text: String, model: LLModel): List<Double> = embed()
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
        override fun close() = Unit
    }
}
