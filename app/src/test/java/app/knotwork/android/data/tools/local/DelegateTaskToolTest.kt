package app.knotwork.android.data.tools.local

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.data.repositories.NetworkActivityTrackerImpl
import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DelegateTaskTool].
 */
class DelegateTaskToolTest {

    private lateinit var cloudLlmClientFactory: CloudLlmClientFactory
    private lateinit var cloudLlmModelResolver: CloudLlmModelResolver
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var embeddingProviderResolver: EmbeddingProviderResolver
    private lateinit var embeddingProvider: EmbeddingProvider
    private lateinit var resolvedModel: LLModel
    private lateinit var delegateTaskTool: DelegateTaskTool
    private lateinit var mockClient: LLMClient
    private lateinit var networkActivity: NetworkActivityTrackerImpl

    @Before
    fun setup() {
        cloudLlmClientFactory = mockk()
        cloudLlmModelResolver = mockk()
        memoryRepository = mockk(relaxed = true)
        embeddingProviderResolver = mockk()
        embeddingProvider = mockk()
        mockClient = mockk(relaxed = true)
        resolvedModel = mockk(relaxed = true)
        networkActivity = NetworkActivityTrackerImpl()

        coEvery { embeddingProviderResolver.resolve() } returns embeddingProvider
        coEvery { cloudLlmModelResolver.resolveModel(any()) } returns resolvedModel

        delegateTaskTool = tool(networkActivity)
    }

    private fun tool(tracker: NetworkActivityTracker) = DelegateTaskTool(
        cloudLlmClientFactory = cloudLlmClientFactory,
        cloudLlmModelResolver = cloudLlmModelResolver,
        memoryRepository = memoryRepository,
        embeddingProviderResolver = embeddingProviderResolver,
        networkActivityTracker = tracker,
    )

    /** Stubs the factory's client for [provider]; the tool asks without a retry listener. */
    private fun givenClient(provider: CloudProvider, client: LLMClient?) {
        coEvery { cloudLlmClientFactory.createClient(provider, any()) } returns client
    }

    @Test
    fun `given a client when executeDelegation then the privacy indicator records the call`() = runTest {
        // A delegated task goes to a cloud provider — the indicator must not keep saying
        // "no network calls" while it does.
        givenClient(CloudProvider.ANTHROPIC, mockClient)
        coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } returns
            kotlinx.coroutines.flow.flowOf(StreamFrame.TextDelta("done"))
        coEvery { embeddingProvider.embed("done") } returns floatArrayOf(0.1f)

        delegateTaskTool.executeDelegation("Summarise this", "anthropic")

        assertNotNull("the delegated call left without being recorded", networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given a streamed answer when executeDelegation then the indicator is told for as long as it streams`() =
        runTest {
            val tracker = mockk<NetworkActivityTracker>(relaxed = true)
            val tool = tool(tracker)
            givenClient(CloudProvider.ANTHROPIC, mockClient)
            coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } returns
                kotlinx.coroutines.flow.flowOf(StreamFrame.TextDelta("a"), StreamFrame.TextDelta("b"))
            coEvery { embeddingProvider.embed("ab") } returns floatArrayOf(0.1f)

            tool.executeDelegation("Summarise this", "anthropic")

            // Once before the call, once per frame.
            verify(exactly = 3) { tracker.recordOutbound() }
        }

    @Test
    fun `given no client can be built when executeDelegation then nothing is recorded`() = runTest {
        givenClient(CloudProvider.ANTHROPIC, null)
        coEvery { cloudLlmClientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns null

        delegateTaskTool.executeDelegation("Summarise this", "anthropic")

        assertNull(networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `executeDelegation returns success and saves to memory when client completes successfully`() {
        runTest {
            val targetModel = "anthropic"
            val taskDescription = "Write a hello world app"
            val mockResponseText = "Here is your hello world app"
            val mockEmbedding = floatArrayOf(0.1f, 0.2f, 0.3f)

            // Koog 1.0.0+: `executeStreaming` returns a `Flow<StreamFrame>` — the test
            // routes the response via `StreamFrame.TextDelta`, not via a mock
            // `Message.Response` (that class was renamed to `Message.Assistant` and
            // no longer exposes a plain `.content` accessor anyway).
            givenClient(CloudProvider.ANTHROPIC, mockClient)
            coEvery { mockClient.models() } returns emptyList()
            every { mockClient.llmProvider() } returns mockk(relaxed = true)
            coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } returns
                kotlinx.coroutines.flow.flowOf(StreamFrame.TextDelta(mockResponseText))

            coEvery { embeddingProvider.embed(mockResponseText) } returns mockEmbedding

            val result = delegateTaskTool.executeDelegation(taskDescription, targetModel)

            assertTrue(result.startsWith("Success: Task completed"))
            coVerify(exactly = 1) { memoryRepository.saveMemory(mockResponseText, mockEmbedding) }
        }
    }

    @Test
    fun `executeDelegation still returns the delegated result when memory embedding fails`() {
        runTest {
            val targetModel = "anthropic"
            val taskDescription = "Write a hello world app"
            val mockResponseText = "Here is your hello world app"

            givenClient(CloudProvider.ANTHROPIC, mockClient)
            coEvery { mockClient.models() } returns emptyList()
            every { mockClient.llmProvider() } returns mockk(relaxed = true)
            coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } returns
                kotlinx.coroutines.flow.flowOf(StreamFrame.TextDelta(mockResponseText))

            // The (cloud) embedding call fails — the secondary memory write must
            // not discard the primary delegated result.
            coEvery { embeddingProvider.embed(mockResponseText) } throws RuntimeException("embedding backend down")

            val result = delegateTaskTool.executeDelegation(taskDescription, targetModel)

            assertTrue(result.startsWith("Success: Task completed"))
            assertTrue(result.contains("memory save failed"))
            coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `given the delegated client throws with a key in the url when executeDelegation then the result is scrubbed`() =
        runTest {
            // The tool result is persisted as the node output, shown in the console and
            // fed back to the model as its observation — none of those may carry the key.
            val leakedKey = "AIzaSyTESTKEY"
            givenClient(CloudProvider.GOOGLE, mockClient)
            coEvery { mockClient.models() } returns emptyList()
            every { mockClient.llmProvider() } returns mockk(relaxed = true)
            coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } throws RuntimeException(
                "Socket timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/" +
                    "gemini:streamGenerateContent?alt=sse&key=$leakedKey]",
            )

            val result = delegateTaskTool.executeDelegation("Task", "google")

            assertTrue(result, result.startsWith("Error: Task delegation failed"))
            assertFalse("key leaked: $result", result.contains(leakedKey))
            assertTrue("scrub marker missing: $result", result.contains("key=***"))
        }

    @Test
    fun `executeDelegation returns error when target model is unsupported`() = runTest {
        val result = delegateTaskTool.executeDelegation("Task", "unknown_model")
        assertTrue(result.startsWith("Error: Unsupported target model"))
        coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
    }

    @Test
    fun `executeDelegation returns error when client cannot be initialized`() = runTest {
        givenClient(CloudProvider.ANTHROPIC, null)
        coEvery { cloudLlmClientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns
            CloudClientUnavailability.MissingCredentials
        val result = delegateTaskTool.executeDelegation("Task", "anthropic")
        assertTrue(result.startsWith("Error: Client for"))
        assertTrue(result.contains("no API key"))
        coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
    }

    @Test
    fun `given a non-local Ollama in local-only mode when executeDelegation then the error names the restriction`() =
        runTest {
            givenClient(CloudProvider.OLLAMA, null)
            coEvery { cloudLlmClientFactory.unavailabilityOf(CloudProvider.OLLAMA) } returns
                CloudClientUnavailability.EndpointNotLocal("ollama.example.com")

            val result = delegateTaskTool.executeDelegation("Task", "ollama")

            assertTrue(result.startsWith("Error: Client for 'ollama'"))
            assertTrue(result.contains("Block network from local model"))
            assertTrue(result.contains("ollama.example.com"))
        }

    @Test
    fun `given the factory reports no cause when executeDelegation then the error still points at settings`() =
        runTest {
            givenClient(CloudProvider.ANTHROPIC, null)
            coEvery { cloudLlmClientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns null

            val result = delegateTaskTool.executeDelegation("Task", "anthropic")

            assertTrue(result.startsWith("Error: Client for 'anthropic'"))
            assertTrue(result.contains("settings"))
        }

    @Test
    fun `executeDelegation returns error when client throws exception`() {
        runTest {
            givenClient(CloudProvider.ANTHROPIC, mockClient)
            coEvery { mockClient.models() } returns emptyList()
            every { mockClient.llmProvider() } returns mockk(relaxed = true)
            coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } throws
                RuntimeException("Network error")

            val result = delegateTaskTool.executeDelegation("Task", "anthropic")

            assertTrue(result.startsWith("Error: Task delegation failed"))
            coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `given a provider when executeDelegation then the call uses the model the resolver chose for it`() = runTest {
        // The tool once resolved models itself, with a third copy of every default; the
        // resolver is now the only place that knows them.
        givenClient(CloudProvider.DEEPSEEK, mockClient)
        coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } returns
            kotlinx.coroutines.flow.flowOf(StreamFrame.TextDelta("done"))
        coEvery { embeddingProvider.embed("done") } returns floatArrayOf(0.1f)

        delegateTaskTool.executeDelegation("Task", "deepseek")

        coVerify(exactly = 1) { cloudLlmModelResolver.resolveModel(CloudProvider.DEEPSEEK) }
        coVerify(exactly = 1) { mockClient.executeStreaming(any<Prompt>(), resolvedModel) }
    }

    @Test
    fun `given an unsupported target when executeDelegation then the error lists every provider id`() = runTest {
        val result = delegateTaskTool.executeDelegation("Task", "mistral")

        assertEquals(
            "Error: Unsupported target model 'mistral'. Supported models: " +
                "${CloudProvider.entries.joinToString { it.id }}.",
            result,
        )
    }
}
