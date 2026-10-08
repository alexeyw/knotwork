package app.knotwork.android.data.engine

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import app.knotwork.android.domain.engine.CloudLlmClientFactory
import app.knotwork.android.domain.engine.CloudLlmModelResolver
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [KoogStructuredInferenceClientFactory] — the cloud-backed
 * [app.knotwork.android.domain.engine.structured.StructuredInferenceClient]
 * seam for the structured-output gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KoogStructuredInferenceClientFactoryTest {

    private val clientFactory = mockk<CloudLlmClientFactory>()
    private val modelResolver = mockk<CloudLlmModelResolver>()
    private val networkTracker = mockk<NetworkActivityTracker>(relaxed = true)
    private val factory = KoogStructuredInferenceClientFactory(clientFactory, modelResolver, networkTracker)

    @Test
    fun `given no configured client when create then returns null`() = runTest {
        coEvery { clientFactory.createClient(CloudProvider.OPENAI, any()) } returns null

        val result = factory.create(CloudProvider.OPENAI) { }

        assertNull(result)
    }

    @Test
    fun `given a model that advertises JSON when infer then the request carries no response schema`() = runTest {
        // The gate keeps its repair budget on a cloud engine because nothing in the
        // request constrains the answer to JSON — whatever the model advertises.
        val client = FakeStreamingClient(flowOf(StreamFrame.TextDelta("{}")))
        coEvery { clientFactory.createClient(CloudProvider.OPENAI, any()) } returns client
        coEvery { modelResolver.resolveModel(CloudProvider.OPENAI) } returns
            LLModel(LLMProvider.OpenAI, "gpt", listOf(LLMCapability.Schema.JSON.Standard))

        factory.create(CloudProvider.OPENAI) { }!!.infer("prompt", temperature = null)

        assertNull(client.lastPrompt?.params?.schema)
    }

    @Test
    fun `given streamed deltas when infer then concatenates and forwards each token`() = runTest {
        coEvery { clientFactory.createClient(CloudProvider.OPENAI, any()) } returns
            FakeStreamingClient(flowOf(StreamFrame.TextDelta("Hel"), StreamFrame.TextDelta("lo")))
        coEvery { modelResolver.resolveModel(CloudProvider.OPENAI) } returns
            LLModel(LLMProvider.OpenAI, "gpt", emptyList())

        val tokens = mutableListOf<String>()
        val result = factory.create(CloudProvider.OPENAI) { tokens.add(it) }
        val output = result!!.infer("prompt", temperature = null)

        assertEquals("Hello", output)
        assertEquals(listOf("Hel", "lo"), tokens)
        verify { networkTracker.recordOutbound() }
    }

    @Test
    fun `given a streamed answer when infer then the privacy indicator is told for as long as it streams`() = runTest {
        // Once before the call and once per frame, as on the free-form CLOUD path.
        coEvery { clientFactory.createClient(CloudProvider.OPENAI, any()) } returns
            FakeStreamingClient(flowOf(StreamFrame.TextDelta("{"), StreamFrame.TextDelta("}")))
        coEvery { modelResolver.resolveModel(CloudProvider.OPENAI) } returns
            LLModel(LLMProvider.OpenAI, "gpt", emptyList())

        factory.create(CloudProvider.OPENAI) {}!!.infer("prompt", temperature = null)

        verify(exactly = 3) { networkTracker.recordOutbound() }
    }

    /** [LLMClient] returning a scripted streaming flow and keeping the prompt it got; other members are unused. */
    private class FakeStreamingClient(private val frames: Flow<StreamFrame>) : LLMClient() {
        var lastPrompt: Prompt? = null

        override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            error("unused")
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            lastPrompt = prompt
            return frames
        }
        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult = error("unused")
        override fun close() = Unit
    }
}
