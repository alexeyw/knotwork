package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.NodeExecutionResult
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.RunGeneratingModel
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.usecases.LoadModelUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputNodeExecutorTest {
    @Test
    fun `execute emits Completed state and result without system prompt`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, mockk(relaxed = true), mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val results = executor.execute(node, "final text", "session-1", "prompt").toList().unwrap()

        assertEquals(2, results.size)
        assertTrue(results[0] is AgentOrchestratorState.Completed)
        assertEquals("final text", (results[0] as AgentOrchestratorState.Completed).finalResponse)

        assertTrue(results[1] is NodeExecutionResult)
        assertEquals("final text", (results[1] as NodeExecutionResult).outputText)
    }

    @Test
    fun `execute uses llm if system prompt is present`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Formatted Response")

        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, mockk(relaxed = true), mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = "Format please:")

        val results = executor.execute(node, "final text", "session-1", "prompt").toList().unwrap()

        assertEquals(3, results.size) // Thinking, Completed, NodeExecutionResult
        assertTrue(results[0] is AgentOrchestratorState.Thinking)
        assertTrue(results[1] is AgentOrchestratorState.Completed)
        assertEquals("Formatted Response", (results[1] as AgentOrchestratorState.Completed).finalResponse)
    }

    @Test
    fun `nested OUTPUT (depth greater than zero) returns the text without writing to chat`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val results = executor
            .execute(node, "final text", "session-1", "prompt", runId = null, scope = ExecutionScope(depth = 1))
            .toList()
            .unwrap()

        // Result still flows up to the parent PIPELINE node…
        assertTrue(results[0] is AgentOrchestratorState.Completed)
        assertEquals("final text", (results[1] as NodeExecutionResult).outputText)
        // …but a nested OUTPUT must NOT persist a chat message.
        coVerify(exactly = 0) { chatRepository.saveMessage(any()) }
    }

    @Test
    fun `nested OUTPUT with system prompt formats but still does not write to chat`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Formatted Response")
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = "Format please:")

        val results = executor
            .execute(node, "final text", "session-1", "prompt", runId = null, scope = ExecutionScope(depth = 1))
            .toList()
            .unwrap()

        assertTrue(results.any { it is AgentOrchestratorState.Completed })
        coVerify(exactly = 0) { chatRepository.saveMessage(any()) }
    }

    @Test
    fun `root OUTPUT stamps the active model name on the saved message`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val localModelRepository = mockk<LocalModelRepository>()
        coEvery { localModelRepository.getActiveModel() } returns
            mockk<LocalModel> { every { name } returns "Gemma 4" }
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, localModelRepository)
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val saved = slot<ChatMessage>()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit

        executor.execute(node, "answer", "session-1", "prompt").toList().unwrap()

        // The answer is attributed to the model that generated it, snapshotted now.
        assertEquals("Gemma 4", saved.captured.modelName)
    }

    @Test
    fun `root OUTPUT attributes a cloud answer to the recorded provider label, not the active model`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val localModelRepository = mockk<LocalModelRepository>()
        coEvery { localModelRepository.getActiveModel() } returns
            mockk<LocalModel> { every { name } returns "Local Gemma" }
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, localModelRepository)
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val saved = slot<ChatMessage>()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit

        // A CLOUD node recorded its provider label on the run holder.
        val scope = ExecutionScope(generatingModel = RunGeneratingModel(cloudLabel = "openai"))
        executor.execute(node, "answer", "session-1", "prompt", runId = null, scope = scope).toList().unwrap()

        assertEquals("openai", saved.captured.modelName)
    }

    @Test
    fun `root OUTPUT resolves the recorded on-device model path to its display name`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val localModelRepository = mockk<LocalModelRepository>()
        every { localModelRepository.getAllModels() } returns flowOf(
            listOf(
                mockk<LocalModel> {
                    every { path } returns "/models/m.litertlm"
                    every { name } returns "Gemma 4 4B"
                },
            ),
        )
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, localModelRepository)
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        val saved = slot<ChatMessage>()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit

        val scope = ExecutionScope(generatingModel = RunGeneratingModel(localModelPath = "/models/m.litertlm"))
        executor.execute(node, "answer", "session-1", "prompt", runId = null, scope = scope).toList().unwrap()

        assertEquals("Gemma 4 4B", saved.captured.modelName)
    }

    @Test
    fun `root OUTPUT (depth zero) persists the chat message`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        executor.execute(node, "final text", "session-1", "prompt").toList().unwrap()

        coVerify(exactly = 1) { chatRepository.saveMessage(any()) }
    }

    @Test
    fun `given echo mode behind a node no model wrote when saved then the reply is marked relayed`() = runTest {
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val saved = slot<ChatMessage>()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit
        val executor = OutputNodeExecutor(mockk(), mockk(), chatRepository, mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)

        executor.execute(node, "a tool's result", "session-1", "prompt", runId = null, scope = ExecutionScope())
            .toList()

        assertTrue(saved.captured.relayed)
    }

    @Test
    fun `given echo mode behind a model's answer when saved then the reply is not relayed`() = runTest {
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val saved = slot<ChatMessage>()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit
        val executor = OutputNodeExecutor(mockk(), mockk(), chatRepository, mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = null)
        val scope = ExecutionScope(inputWrittenByModel = true)

        executor.execute(node, "an answer", "session-1", "prompt", runId = null, scope = scope).toList()

        assertFalse(saved.captured.relayed)
    }

    @Test
    fun `given formatting mode when the model writes the reply then it is not relayed`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("Formatted")
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val saved = slot<ChatMessage>()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = "Format:")

        executor.execute(node, "a tool's result", "session-1", "prompt", runId = null, scope = ExecutionScope())
            .toList()

        assertFalse(saved.captured.relayed)
    }

    @Test
    fun `given formatting mode when the model writes nothing then the fallback reply is relayed`() = runTest {
        val llmEngine = mockk<LlmInferenceEngine>()
        val loadModelUseCase = mockk<LoadModelUseCase>()
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        every { llmEngine.generateResponseStream(any()) } returns flowOf("  ")
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        val saved = slot<ChatMessage>()
        coEvery { chatRepository.saveMessage(capture(saved)) } returns Unit
        val executor = OutputNodeExecutor(llmEngine, loadModelUseCase, chatRepository, mockk(relaxed = true))
        val node = NodeModel("1", NodeType.OUTPUT, 0f, 0f, systemPrompt = "Format:")
        // Even behind a model's answer: the fallback is the node's composed input.
        val scope = ExecutionScope(inputWrittenByModel = true)

        executor.execute(node, "context and a tool's result", "session-1", "prompt", runId = null, scope = scope)
            .toList()

        assertEquals("context and a tool's result", saved.captured.content)
        assertTrue(saved.captured.relayed)
    }
}
