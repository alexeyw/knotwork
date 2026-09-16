package app.knotwork.android.data.tools.local

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.data.engine.KoogClientFactory
import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DelegateTaskTool].
 */
class DelegateTaskToolTest {

    private lateinit var koogClientFactory: KoogClientFactory
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var embeddingProviderResolver: EmbeddingProviderResolver
    private lateinit var embeddingProvider: EmbeddingProvider
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var delegateTaskTool: DelegateTaskTool
    private lateinit var mockClient: LLMClient

    @Before
    fun setup() {
        koogClientFactory = mockk()
        memoryRepository = mockk(relaxed = true)
        embeddingProviderResolver = mockk()
        embeddingProvider = mockk()
        apiKeyRepository = mockk(relaxed = true)
        mockClient = mockk(relaxed = true)

        coEvery { embeddingProviderResolver.resolve() } returns embeddingProvider

        every { apiKeyRepository.getAnthropicModel() } returns flowOf("claude-3-5-sonnet-20240620")
        every { apiKeyRepository.getOpenAIModel() } returns flowOf("gpt-4o")
        every { apiKeyRepository.getGoogleModel() } returns flowOf("gemini-1.5-pro-preview-0409")
        every { apiKeyRepository.getDeepSeekModel() } returns flowOf("deepseek-chat")
        every { apiKeyRepository.getOllamaModelName() } returns flowOf("llama3")

        delegateTaskTool = DelegateTaskTool(
            koogClientFactory = koogClientFactory,
            memoryRepository = memoryRepository,
            embeddingProviderResolver = embeddingProviderResolver,
            apiKeyRepository = apiKeyRepository,
        )
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
            coEvery { koogClientFactory.createAnthropicExecutor() } returns mockClient
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

            coEvery { koogClientFactory.createAnthropicExecutor() } returns mockClient
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
    fun `executeDelegation returns error when target model is unsupported`() = runTest {
        val result = delegateTaskTool.executeDelegation("Task", "unknown_model")
        assertTrue(result.startsWith("Error: Unsupported target model"))
        coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
    }

    @Test
    fun `executeDelegation returns error when client cannot be initialized`() = runTest {
        coEvery { koogClientFactory.createAnthropicExecutor() } returns null
        coEvery { koogClientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns
            CloudClientUnavailability.MissingCredentials
        val result = delegateTaskTool.executeDelegation("Task", "anthropic")
        assertTrue(result.startsWith("Error: Client for"))
        assertTrue(result.contains("no API key"))
        coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
    }

    @Test
    fun `given a non-local Ollama in local-only mode when executeDelegation then the error names the restriction`() =
        runTest {
            coEvery { koogClientFactory.createOllamaExecutor() } returns null
            coEvery { koogClientFactory.unavailabilityOf(CloudProvider.OLLAMA) } returns
                CloudClientUnavailability.EndpointNotLocal("ollama.example.com")

            val result = delegateTaskTool.executeDelegation("Task", "ollama")

            assertTrue(result.startsWith("Error: Client for 'ollama'"))
            assertTrue(result.contains("Block network from local model"))
            assertTrue(result.contains("ollama.example.com"))
        }

    @Test
    fun `given the factory reports no cause when executeDelegation then the error still points at settings`() =
        runTest {
            coEvery { koogClientFactory.createAnthropicExecutor() } returns null
            coEvery { koogClientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns null

            val result = delegateTaskTool.executeDelegation("Task", "anthropic")

            assertTrue(result.startsWith("Error: Client for 'anthropic'"))
            assertTrue(result.contains("settings"))
        }

    @Test
    fun `executeDelegation returns error when client throws exception`() {
        runTest {
            coEvery { koogClientFactory.createAnthropicExecutor() } returns mockClient
            coEvery { mockClient.models() } returns emptyList()
            every { mockClient.llmProvider() } returns mockk(relaxed = true)
            coEvery { mockClient.executeStreaming(any<Prompt>(), any<LLModel>()) } throws
                RuntimeException("Network error")

            val result = delegateTaskTool.executeDelegation("Task", "anthropic")

            assertTrue(result.startsWith("Error: Task delegation failed"))
            coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
        }
    }
}
