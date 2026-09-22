package app.knotwork.android.domain.engine.executors

import app.knotwork.android.domain.constants.DefaultPrompts
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.structured.CloudStructuredClient
import app.knotwork.android.domain.engine.structured.CloudStructuredInferenceClientFactory
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.ExecutionScope
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.LoadModelUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SystemNodeExecutorTest {

    private lateinit var llmEngine: LlmInferenceEngine
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var chatRepository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var executor: SystemNodeExecutor

    @Before
    fun setup() {
        llmEngine = mockk()
        loadModelUseCase = mockk()
        chatRepository = mockk(relaxed = true)
        settingsRepository = mockk()
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        coEvery { loadModelUseCase(any()) } returns Result.Success(Unit)
        executor =
            SystemNodeExecutor(
                llmEngine,
                loadModelUseCase,
                chatRepository,
                StructuredOutputGate(),
                settingsRepository,
                CloudStructuredInferenceClientFactory {
                        _,
                        _,
                    ->
                    null
                },
            )
    }

    @Test
    fun `given a cloud provider error carrying an api key when execute then the emitted error is scrubbed`() = runTest {
        // Google authenticates by query parameter, so an ordinary transport failure
        // quotes the key. From this node's error it would reach the run record, the
        // shared chat export, the console clipboard and Crashlytics.
        val leaking = SystemNodeExecutor(
            llmEngine,
            loadModelUseCase,
            chatRepository,
            StructuredOutputGate(),
            settingsRepository,
            CloudStructuredInferenceClientFactory { _, _ ->
                CloudStructuredClient(
                    inference = { _, _ -> throw RuntimeException(LEAKING_PROVIDER_ERROR) },
                    supportsNativeJson = true,
                )
            },
        )
        val node = NodeModel("1", NodeType.EVALUATION, 0f, 0f, cloudProvider = "google")

        val outputs = leaking.execute(node, "input", "session-1", "prompt").toList()

        val stateError = outputs.filterStates<AgentOrchestratorState.Error>().single().message
        val resultError = outputs.lastResult().error.orEmpty()
        for (text in listOf(stateError, resultError)) {
            assertFalse("key leaked: $text", text.contains(LEAKED_KEY))
            assertTrue("scrub marker missing: $text", text.contains("key=***"))
        }
    }

    @Test
    fun `given INTENT_ROUTER with no labelled edges then runs plain inference and routes by raw reply`() = runTest {
        val node = NodeModel("1", NodeType.INTENT_ROUTER, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("Result")

        val outputs = executor.execute(node, "input", "session-1", "prompt").toList()

        assertTrue(outputs.filterStates<AgentOrchestratorState.Thinking>().isNotEmpty())
        val result = outputs.lastResult()
        assertEquals("Result", result.outputText)
        assertEquals("Result", result.routingKey)
    }

    @Test
    fun `given INTENT_ROUTER and the run carries an image then the prompt notes the attachment`() = runTest {
        val node = NodeModel("1", NodeType.INTENT_ROUTER, 0f, 0f)
        val promptSlot = slot<String>()
        every { llmEngine.generateResponseStream(capture(promptSlot), any(), any()) } returns flowOf("Result")

        executor.execute(
            node,
            "input",
            "session-1",
            "prompt",
            scope = ExecutionScope(imagePresent = true),
        ).toList()

        assertTrue(
            "Router prompt must surface image presence so it can branch on it",
            promptSlot.captured.contains(DefaultPrompts.System.IMAGE_PRESENT_NOTE),
        )
    }

    @Test
    fun `given INTENT_ROUTER and no image then the prompt omits the attachment note`() = runTest {
        val node = NodeModel("1", NodeType.INTENT_ROUTER, 0f, 0f)
        val promptSlot = slot<String>()
        every { llmEngine.generateResponseStream(capture(promptSlot), any(), any()) } returns flowOf("Result")

        executor.execute(node, "input", "session-1", "prompt").toList()

        assertFalse(promptSlot.captured.contains(DefaultPrompts.System.IMAGE_PRESENT_NOTE))
    }

    @Test
    fun `given DECOMPOSITION and the run carries an image then the prompt omits the attachment note`() = runTest {
        // The image note is scoped to INTENT_ROUTER; DECOMPOSITION and EVALUATION share
        // this executor and must not have their prompts mutated by an attachment they
        // never act on.
        val node = NodeModel("1", NodeType.DECOMPOSITION, 0f, 0f)
        val promptSlot = slot<String>()
        every { llmEngine.generateResponseStream(capture(promptSlot), any(), any()) } returns flowOf("[\"task a\"]")

        executor.execute(
            node,
            "input",
            "session-1",
            "prompt",
            scope = ExecutionScope(imagePresent = true),
        ).toList()

        assertFalse(promptSlot.captured.contains(DefaultPrompts.System.IMAGE_PRESENT_NOTE))
    }

    @Test
    fun `given INTENT_ROUTER with labelled edges then routing key is the matched label`() = runTest {
        val node = NodeModel("1", NodeType.INTENT_ROUTER, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("I think this is Weather data")

        val outputs = executor.execute(
            node,
            "input",
            "session-1",
            "prompt",
            scope = ExecutionScope(routingChoices = listOf("Weather", "Calendar")),
        ).toList()

        assertEquals("Weather", outputs.lastResult().routingKey)
    }

    @Test
    fun `given INTENT_ROUTER reply matches no route after repairs then default branch and error event`() = runTest {
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(1)
        val node = NodeModel("1", NodeType.INTENT_ROUTER, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("no idea")

        val outputs = executor.execute(
            node,
            "input",
            "session-1",
            "prompt",
            scope = ExecutionScope(routingChoices = listOf("Weather", "Calendar")),
        ).toList()

        assertNull(outputs.lastResult().routingKey)
        assertEquals(1, outputs.consoleEvents().count { it.type == ConsoleEventType.Error })
        assertEquals(1, outputs.consoleEvents().count { it.type == ConsoleEventType.StructuredOutputRepair })
    }

    @Test
    fun `given EVALUATION verdict then routing key is the canonical token`() = runTest {
        val node = NodeModel("1", NodeType.EVALUATION, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("PASS — looks good")

        val outputs = executor.execute(node, "input", "session-1", "prompt").toList()

        assertEquals("Pass", outputs.lastResult().routingKey)
    }

    @Test
    fun `given EVALUATION malformed then valid verdict then repairs and routes`() = runTest {
        val node = NodeModel("1", NodeType.EVALUATION, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returnsMany listOf(
            flowOf("undecided"),
            flowOf("Retry"),
        )

        val outputs = executor.execute(node, "input", "session-1", "prompt").toList()

        assertEquals("Retry", outputs.lastResult().routingKey)
        assertEquals(1, outputs.consoleEvents().count { it.type == ConsoleEventType.StructuredOutputRepair })
    }

    @Test
    fun `given DECOMPOSITION valid array then re-encodes the validated list`() = runTest {
        val node = NodeModel("1", NodeType.DECOMPOSITION, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns
            flowOf("""Here are the steps: ["buy milk", "walk dog"]""")

        val outputs = executor.execute(node, "input", "session-1", "prompt").toList()

        val result = outputs.lastResult()
        assertNull(result.error)
        assertEquals("""["buy milk","walk dog"]""", result.outputText)
    }

    @Test
    fun `given DECOMPOSITION never valid after repairs then fails the run with a clear error`() = runTest {
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(1)
        val node = NodeModel("1", NodeType.DECOMPOSITION, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("not a json array at all")

        val outputs = executor.execute(node, "input", "session-1", "prompt").toList()

        val result = outputs.lastResult()
        assertNotNull(result.error)
        assertNull(result.outputText)
        assertEquals(1, outputs.consoleEvents().count { it.type == ConsoleEventType.Error })
    }

    @Test
    fun `given model load failure then emits error result`() = runTest {
        coEvery { loadModelUseCase(any()) } returns Result.Error<Unit, AppError>(mockk(relaxed = true), "boom")
        val node = NodeModel("1", NodeType.EVALUATION, 0f, 0f)

        val outputs = executor.execute(node, "input", "session-1", "prompt").toList()

        assertNotNull(outputs.lastResult().error)
    }

    @Test
    fun `given DECOMPOSITION with a subtask cap then only that many subtasks reach the queue`() = runTest {
        val node = NodeModel("1", NodeType.DECOMPOSITION, 0f, 0f, maxSubtasks = 2)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns
            flowOf("[\"a\", \"b\", \"c\", \"d\"]")

        val outputs = executor.execute(node, "input", "session-1", "prompt", scope = ExecutionScope()).toList()

        // Enforced here rather than asked for in the prompt: a model that
        // overshoots would otherwise hand the queue more work than the author
        // allowed, and the prompt is the thing least able to hold a number.
        assertEquals("[\"a\",\"b\"]", outputs.lastResult().outputText)
    }

    @Test
    fun `given DECOMPOSITION with no cap then every produced subtask is kept`() = runTest {
        val node = NodeModel("1", NodeType.DECOMPOSITION, 0f, 0f)
        every { llmEngine.generateResponseStream(any(), any(), any()) } returns flowOf("[\"a\", \"b\", \"c\"]")

        val outputs = executor.execute(node, "input", "session-1", "prompt", scope = ExecutionScope()).toList()

        assertEquals("[\"a\",\"b\",\"c\"]", outputs.lastResult().outputText)
    }

    private companion object {
        const val LEAKED_KEY = "AIzaSyTESTKEY"
        const val LEAKING_PROVIDER_ERROR =
            "Socket timeout has expired [url=https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini:streamGenerateContent?alt=sse&key=$LEAKED_KEY]"
    }
}
