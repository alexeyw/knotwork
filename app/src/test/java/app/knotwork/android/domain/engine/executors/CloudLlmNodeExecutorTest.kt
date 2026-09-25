package app.knotwork.android.domain.engine.executors

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.engine.retry.CloudRetryListener
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeOutput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ToolRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

class CloudLlmNodeExecutorTest {

    private lateinit var toolRepository: ToolRepository
    private lateinit var chatRepository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var metricsRepository: MetricsRepository
    private lateinit var clientFactory: CloudLlmClientFactory
    private lateinit var modelResolver: CloudLlmModelResolver
    private lateinit var networkActivityTracker: NetworkActivityTracker
    private lateinit var executor: CloudLlmNodeExecutor

    @Before
    fun setup() {
        toolRepository = mockk(relaxed = true)
        chatRepository = mockk(relaxed = true)
        settingsRepository = mockk()
        apiKeyRepository = mockk(relaxed = true)
        metricsRepository = mockk(relaxed = true)
        clientFactory = mockk()
        modelResolver = mockk()
        networkActivityTracker = mockk(relaxed = true)

        every { settingsRepository.systemPromptPrefix } returns flowOf("")
        every { apiKeyRepository.getAnthropicKey() } returns flowOf("anthropic-key")
        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        every { apiKeyRepository.getGoogleKey() } returns flowOf(null)
        every { apiKeyRepository.getDeepSeekKey() } returns flowOf(null)
        every { apiKeyRepository.getAnthropicModel() } returns flowOf("claude-sonnet-4-5")

        executor = CloudLlmNodeExecutor(
            settingsRepository,
            apiKeyRepository,
            metricsRepository,
            clientFactory,
            modelResolver,
            networkActivityTracker,
        )
    }

    @Test
    fun `execute forwards inputText verbatim without refetching context`() = runTest {
        // Defect 1 regression guard for the cloud path: NodeContextBuilder is the single
        // source of truth, so the executor must consume `inputText` as-is.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic", systemPrompt = "Sys")
        val client: LLMClient = mockk(relaxed = true)
        val capturedPrompt = slot<Prompt>()
        coEvery { client.executeStreaming(capture(capturedPrompt), any<LLModel>()) } returns
            flowOf(StreamFrame.TextDelta("ok"))
        coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.ANTHROPIC) } returns AnthropicModels.Sonnet_4_5

        val assembledContext = "--- Original Task ---\nQ\n\n--- Previous Node Output ---\nU"
        executor.execute(node, assembledContext, "s1", "Q").toList()

        val text = capturedPrompt.captured.messages.joinToString("\n") { it.textContent() }
        assertTrue(text.contains(assembledContext))
        assertFalse(text.contains("RELEVANT LONG-TERM MEMORIES:"))
    }

    @Test
    fun `execute records tokenCount equal to number of stream deltas`() = runTest {
        // Defect 7: each StreamFrame.TextDelta is one cloud-side token; counting by
        // `+= 1` keeps the metric symmetric with the local LiteRT executor and avoids
        // length-based inflation.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("a"),
            StreamFrame.TextDelta("bbb"),
            StreamFrame.TextDelta("cc"),
        )
        coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.ANTHROPIC) } returns AnthropicModels.Sonnet_4_5

        val outputs = executor.execute(node, "input", "s1", "Q").toList()
        val result = outputs.filterIsInstance<NodeOutput.Result>().single().result
        assertEquals(3, result.tokenCount)
    }

    @Test
    fun `given a streamed answer when execute then the privacy indicator is told for as long as it streams`() =
        runTest {
            // Once before the call and once per frame: recorded at the start alone, a
            // three-minute answer would read "no network calls in last 2 m" mid-stream.
            val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic")
            val client: LLMClient = mockk(relaxed = true)
            coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
                StreamFrame.TextDelta("a"),
                StreamFrame.TextDelta("b"),
                StreamFrame.TextDelta("c"),
            )
            coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, any()) } returns client
            coEvery { modelResolver.resolveModel(CloudProvider.ANTHROPIC) } returns AnthropicModels.Sonnet_4_5

            executor.execute(node, "input", "s1", "Q").toList()

            verify(exactly = 4) { networkActivityTracker.recordOutbound() }
        }

    @Test
    fun `given no credentials when execute then the node fails instead of answering`() = runTest {
        // Regression guard: an unreachable provider must terminate the node through
        // `error`, never through `outputText`. The previous version of this test asserted
        // the opposite — it pinned the defect in place, because a result whose outputText
        // reads "Error: anthropic not configured" and whose error is null is a *success*
        // to GraphExecutionEngine, which then forwarded that sentence to the next node.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic")
        coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, any()) } returns null
        coEvery { clientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns
            CloudClientUnavailability.MissingCredentials

        val outputs = executor.execute(node, "input", "s1", "Q").toList()

        val result = outputs.filterIsInstance<NodeOutput.Result>().single().result
        assertNull("a failed cloud call must not produce node output", result.outputText)
        assertTrue(result.error!!.contains("no API key"))
        assertTrue(
            "the live UI needs the same failure",
            outputs.filterIsInstance<NodeOutput.State>()
                .any { it.state is AgentOrchestratorState.Error },
        )
    }

    @Test
    fun `given local-only mode when execute then the error names the restriction not a missing key`() = runTest {
        // The factory returns null for both "blocked by policy" and "no credentials";
        // reporting the latter when the former is true sends the user to the wrong screen.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic")
        coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, any()) } returns null
        coEvery { clientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns
            CloudClientUnavailability.BlockedByLocalOnlyMode

        val error = executeForError(node)

        assertTrue("expected the restriction to be named, got: $error", error.contains("Block network"))
        assertFalse("must not blame a missing key", error.contains("no API key"))
    }

    @Test
    fun `given a public Ollama in local-only mode when execute then the error names the host and the restriction`() =
        runTest {
            val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "ollama")
            coEvery { clientFactory.createClient(CloudProvider.OLLAMA, any()) } returns null
            coEvery { clientFactory.unavailabilityOf(CloudProvider.OLLAMA) } returns
                CloudClientUnavailability.EndpointNotLocal("ollama.example.com")

            val error = executeForError(node)

            assertTrue("expected the host, got: $error", error.contains("ollama.example.com"))
            assertTrue("expected the restriction, got: $error", error.contains("Block network from local model"))
            assertFalse("Ollama has no API key to blame", error.contains("API key"))
        }

    @Test
    fun `given a refused cleartext address with local-only mode on when execute then the cleartext reason is shown`() =
        runTest {
            // The executor used to re-read the restriction flag and blame it for any null
            // client while it was on — including an address the cleartext rule refused.
            every { settingsRepository.blockNetworkFromLocalModel } returns flowOf(true)
            val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "ollama")
            coEvery { clientFactory.createClient(CloudProvider.OLLAMA, any()) } returns null
            coEvery { clientFactory.unavailabilityOf(CloudProvider.OLLAMA) } returns
                CloudClientUnavailability.CleartextRefused("Re-save the address in Settings and confirm the prompt.")

            val error = executeForError(node)

            assertTrue("expected the cleartext remedy, got: $error", error.contains("confirm the prompt"))
            assertFalse("must not blame the restriction", error.contains("Block network"))
        }

    @Test
    fun `given the factory reports no cause when execute then the node still fails with a neutral reason`() = runTest {
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic")
        coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, any()) } returns null
        coEvery { clientFactory.unavailabilityOf(CloudProvider.ANTHROPIC) } returns null

        val error = executeForError(node)

        assertTrue("expected a settings pointer, got: $error", error.contains("settings"))
    }

    private suspend fun executeForError(node: NodeModel): String = executor.execute(node, "input", "s1", "Q").toList()
        .filterIsInstance<NodeOutput.Result>().single().result.error!!

    @Test
    fun `given no provider selected and no keys when execute then the node fails`() = runTest {
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = CloudProvider.AUTO_KEY)
        every { apiKeyRepository.getAnthropicKey() } returns flowOf(null)

        val outputs = executor.execute(node, "input", "s1", "Q").toList()

        val result = outputs.filterIsInstance<NodeOutput.Result>().single().result
        assertNull(result.outputText)
        assertTrue(result.error!!.contains("No cloud provider is configured"))
    }

    @Test
    fun `given the auto sentinel in upper case when execute then a provider is picked from the keys`() = runTest {
        // The node sheet shows "AUTO" as Auto (it compares ignoring case); the run
        // must treat it the same way instead of as an unknown provider.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "AUTO")
        every { apiKeyRepository.getAnthropicKey() } returns flowOf(null)

        executor.execute(node, "input", "s1", "Q").toList()

        // Auto-detection is the only path that reads the saved keys.
        verify { apiKeyRepository.getGoogleKey() }
    }

    @Test
    fun `given a stream that ends without a finish reason then the answer is not passed off as complete`() = runTest {
        // Measured shape of a dropped connection on the OpenAI-compatible clients: the
        // frames are identical to a healthy stream except that End carries no finish
        // reason, and no exception is raised. Handing that text on would be exactly the
        // "partial result presented as a full one" the cloud path must not produce.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "deepseek")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("Half an "),
            StreamFrame.TextDelta("answer"),
            StreamFrame.End(finishReason = null, metaInfo = ResponseMetaInfo.Empty),
        )
        coEvery { clientFactory.createClient(CloudProvider.DEEPSEEK, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.DEEPSEEK) } returns AnthropicModels.Sonnet_4_5

        val result = executor.execute(node, "input", "s1", "Q").toList()
            .filterIsInstance<NodeOutput.Result>().single().result

        assertNull("the truncated text must not become node output", result.outputText)
        assertTrue(result.error!!.contains("cut off"))
    }

    @Test
    fun `given a stream that ends with a finish reason then the answer is delivered`() = runTest {
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "deepseek")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("A whole "),
            StreamFrame.TextDelta("answer"),
            StreamFrame.End(finishReason = "stop", metaInfo = ResponseMetaInfo.Empty),
        )
        coEvery { clientFactory.createClient(CloudProvider.DEEPSEEK, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.DEEPSEEK) } returns AnthropicModels.Sonnet_4_5

        val result = executor.execute(node, "input", "s1", "Q").toList()
            .filterIsInstance<NodeOutput.Result>().single().result

        assertEquals("A whole answer", result.outputText)
        assertNull(result.error)
    }

    @Test
    fun `given a provider that reports usage then prompt and completion tokens are charged`() = runTest {
        // The point of reading the provider's usage record: in a loop the cost is
        // dominated by the prompt being re-sent, and the old delta count only ever
        // saw the answer. Two deltas would have counted 2; the provider says 1400.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "deepseek")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("Two "),
            StreamFrame.TextDelta("deltas"),
            StreamFrame.End(
                finishReason = "stop",
                metaInfo = ResponseMetaInfo(
                    timestamp = Instant.DISTANT_PAST,
                    totalTokensCount = 1_400,
                    inputTokensCount = 1_200,
                    outputTokensCount = 200,
                ),
            ),
        )
        coEvery { clientFactory.createClient(CloudProvider.DEEPSEEK, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.DEEPSEEK) } returns AnthropicModels.Sonnet_4_5

        val result = executor.execute(node, "input", "s1", "Q").toList()
            .filterIsInstance<NodeOutput.Result>().single().result

        assertEquals(1_400, result.tokenCount)
        assertFalse("a provider-reported figure is not an estimate", result.tokensEstimated)
    }

    @Test
    fun `given usage without a total then input and output are summed`() = runTest {
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "deepseek")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("hi"),
            StreamFrame.End(
                finishReason = "stop",
                metaInfo = ResponseMetaInfo(
                    timestamp = Instant.DISTANT_PAST,
                    inputTokensCount = 900,
                    outputTokensCount = 100,
                ),
            ),
        )
        coEvery { clientFactory.createClient(CloudProvider.DEEPSEEK, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.DEEPSEEK) } returns AnthropicModels.Sonnet_4_5

        val result = executor.execute(node, "input", "s1", "Q").toList()
            .filterIsInstance<NodeOutput.Result>().single().result

        assertEquals(1_000, result.tokenCount)
        assertFalse(result.tokensEstimated)
    }

    @Test
    fun `given a provider that reports no usage then the delta estimate is charged and flagged`() = runTest {
        // Measured against the pinned client library: the Ollama client emits no
        // usage at all, and DeepSeek only if its API volunteers one. The run must
        // still be charged something rather than running for free, and the number
        // must be marked as the app's own estimate.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "ollama")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("one"),
            StreamFrame.TextDelta("two"),
            StreamFrame.TextDelta("three"),
            StreamFrame.End(finishReason = null, metaInfo = ResponseMetaInfo.Empty),
        )
        coEvery { clientFactory.createClient(CloudProvider.OLLAMA, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.OLLAMA) } returns AnthropicModels.Sonnet_4_5

        val result = executor.execute(node, "input", "s1", "Q").toList()
            .filterIsInstance<NodeOutput.Result>().single().result

        assertEquals(3, result.tokenCount)
        assertTrue("an estimate must say so", result.tokensEstimated)
    }

    @Test
    fun `given reported usage then the generation-rate metric sees only completion tokens`() = runTest {
        // The metrics display divides by elapsed time to show a rate. Prompt
        // tokens were not produced during the call, so folding them in would
        // inflate the figure and break comparability with local inference.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "deepseek")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("x"),
            StreamFrame.End(
                finishReason = "stop",
                metaInfo = ResponseMetaInfo(
                    timestamp = Instant.DISTANT_PAST,
                    totalTokensCount = 1_400,
                    inputTokensCount = 1_200,
                    outputTokensCount = 200,
                ),
            ),
        )
        coEvery { clientFactory.createClient(CloudProvider.DEEPSEEK, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.DEEPSEEK) } returns AnthropicModels.Sonnet_4_5

        executor.execute(node, "input", "s1", "Q").toList()

        coVerify { metricsRepository.updateMetrics(any(), 200) }
        coVerify(exactly = 0) { metricsRepository.updateMetrics(any(), 1_400) }
    }

    @Test
    fun `given Ollama which never reports a finish reason then a healthy answer is not rejected`() = runTest {
        // The false positive that would break working setups: Koog's Ollama client emits no
        // finish reason at all, so absence must not be read as truncation there.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "ollama")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(
            StreamFrame.TextDelta("Local answer"),
            StreamFrame.End(finishReason = null, metaInfo = ResponseMetaInfo.Empty),
        )
        coEvery { clientFactory.createClient(CloudProvider.OLLAMA, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.OLLAMA) } returns AnthropicModels.Sonnet_4_5

        val result = executor.execute(node, "input", "s1", "Q").toList()
            .filterIsInstance<NodeOutput.Result>().single().result

        assertEquals("Local answer", result.outputText)
        assertNull(result.error)
    }

    @Test
    fun `execute uses domain-level CloudLlmClientFactory not data-layer types`() = runTest {
        // Defect 3 regression guard: this test compiles and runs against the domain
        // interfaces only. If CloudLlmNodeExecutor regressed and started importing
        // KoogClientFactory / KoogModelMapper directly, this test setup would no longer
        // be sufficient to drive it (it would still pass at runtime but the architectural
        // intent would be lost — see the package-level guard test for compile-time enforcement).
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic")
        val client: LLMClient = mockk(relaxed = true)
        coEvery { client.executeStreaming(any(), any<LLModel>()) } returns flowOf(StreamFrame.TextDelta("hi"))
        coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.ANTHROPIC) } returns AnthropicModels.Sonnet_4_5

        val outputs = executor.execute(node, "input", "s1", "Q").toList()
        val result = outputs.filterIsInstance<NodeOutput.Result>().single().result
        assertEquals("hi", result.outputText)
    }

    @Test
    fun `given a retry before the first token when execute then the console line precedes the answer`() = runTest {
        // Timing, not presence. The retry lines used to be buffered and drained
        // after the stream finished, so on a real run the attempts happened at 1 s
        // and 3 s while the user saw nothing until the very end. Asserting the
        // ORDER of the emissions is what pins that: the console line has to come
        // out before the tokens it preceded in real time.
        val node = NodeModel("1", NodeType.CLOUD, 0f, 0f, cloudProvider = "anthropic")
        val client: LLMClient = mockk(relaxed = true)
        val capturedListener = slot<CloudRetryListener>()
        coEvery { clientFactory.createClient(CloudProvider.ANTHROPIC, capture(capturedListener)) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.ANTHROPIC) } returns AnthropicModels.Sonnet_4_5
        // Fire the retry callback at the moment the provider is first called —
        // i.e. before any token exists — exactly as the retry wrapper does.
        coEvery { client.executeStreaming(any(), any<LLModel>()) } answers {
            capturedListener.captured.onRetry(provider = "anthropic", attempt = 1, maxRetries = 2)
            flowOf(StreamFrame.TextDelta("answer"))
        }

        val outputs = executor.execute(node, "input", "s1", "Q").toList()

        val retryIndex = outputs.indexOfFirst {
            it is NodeOutput.Console && it.type == ConsoleEventType.CloudRetry
        }
        val firstTokenIndex = outputs.indexOfFirst { it is NodeOutput.State }
        assertTrue("the retry line must be emitted at all", retryIndex >= 0)
        assertTrue(
            "the retry line ($retryIndex) must precede the first streamed token ($firstTokenIndex)",
            retryIndex < firstTokenIndex,
        )
        val line = (outputs[retryIndex] as NodeOutput.Console).message
        assertEquals("Cloud retry 1/2 for anthropic", line)
    }
}
