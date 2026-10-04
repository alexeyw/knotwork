package app.knotwork.android.data.engine

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.NetworkSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server the user runs, reached on the wire through the production [KoogClientFactory] and
 * [KoogCloudLlmModelResolver]: the address the way vLLM and LM Studio document it (ending in
 * `/v1`), the paths, the key or its absence, and the model list.
 */
class OpenAiCompatibleServerEndToEndTest {

    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        server.close()
    }

    private val address get() = server.url("/v1").toString()
    private val origin get() = server.url("/").toString().trimEnd('/')

    private fun apiKeys(key: String?) = mockk<ApiKeyRepository> {
        every { getBaseUrl(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf(address)
        every { getApiKey(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf(key)
        every { getModel(CloudProvider.OPENAI_COMPATIBLE) } returns flowOf("qwen2.5-7b-instruct")
    }

    private val networkSettings = mockk<NetworkSettings>(relaxed = true) {
        every { blockNetworkFromLocalModel } returns MutableStateFlow(false)
        every { approvedCleartextOrigins } returns flowOf(setOf(origin))
        every { cloudRetryMaxAttempts } returns flowOf(1)
    }

    private suspend fun client(keys: ApiKeyRepository): Pair<LLMClient, LLModel> {
        val factory = KoogClientFactory(keys, ModelNetworkGate(networkSettings), CloudRetryWrapper(networkSettings))
        val client = factory.createClient(CloudProvider.OPENAI_COMPATIBLE) as LLMClient
        val model = KoogCloudLlmModelResolver(keys).resolveModel(CloudProvider.OPENAI_COMPATIBLE) as LLModel
        return client to model
    }

    private fun streamed(content: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(
            "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\"," +
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"$content\"}," +
                "\"finish_reason\":null}]}\n\n" +
                "data: {\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\"," +
                "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )
        .build()

    @Test
    fun `given a server without a key when chatting then it answers at v1 chat completions with no Authorization`() =
        runBlocking {
            server.enqueue(streamed("hi"))
            val (client, model) = client(apiKeys(key = null))

            val text = client.executeStreaming(prompt("e2e") { user("hello") }, model).toList()
                .filterIsInstance<StreamFrame.TextDelta>().joinToString("") { it.text }

            val request = server.takeRequest()
            assertEquals("hi", text)
            assertEquals("/v1/chat/completions", request.target)
            assertNull("no key, so no Authorization at all", request.headers["Authorization"])
            assertTrue(request.body?.utf8().orEmpty().contains("\"model\":\"qwen2.5-7b-instruct\""))
        }

    @Test
    fun `given a server with a key when chatting then the key is sent as a Bearer token`() = runBlocking {
        server.enqueue(streamed("hi"))
        val (client, model) = client(apiKeys(key = "sk-own"))

        client.executeStreaming(prompt("e2e") { user("hello") }, model).toList()

        assertEquals("Bearer sk-own", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `given a server without a key when listing models then the list is read at v1 models`() = runBlocking {
        // The list is decoded with the JSON configuration of Koog's own HTTP client; a client
        // built with a default one would fail on the first field it does not know.
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                .body(
                    """{"object":"list","data":[{"id":"qwen2.5-7b-instruct","object":"model","created":1,""" +
                        """"owned_by":"vllm","root":"qwen","max_model_len":32768}]}""",
                )
                .build(),
        )
        val (client, _) = client(apiKeys(key = null))

        val ids = client.models().map { it.id }

        assertEquals(listOf("qwen2.5-7b-instruct"), ids)
        assertEquals("/v1/models", server.takeRequest().target)
    }
}
