package app.knotwork.android.domain.engine

import app.knotwork.android.domain.models.AgentOrchestratorState
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ConsoleEventType
import app.knotwork.android.domain.models.EngineImageInput
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.models.RunImageDelivery
import app.knotwork.android.domain.models.RunOrigin
import app.knotwork.android.domain.models.RunTraceRecord
import app.knotwork.android.domain.models.RunTreeContext
import app.knotwork.android.domain.models.ToolInvocationResult
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [NodeInputComposer].
 *
 * The engine's tests reach these rules through whole runs; here each one is
 * pinned against the sources it reads, because the expensive ones — memory search,
 * the chat history — must be read only when a node asks, and the run-stable ones
 * only once. Advice and the image each have one node that may receive them, and a
 * wrong one is a visible defect: a note in the user's answer, a picture sent to a
 * provider.
 */
class NodeInputComposerTest {

    private val chat: ChatRepository = mockk()
    private val settings: SettingsRepository = mockk()
    private val retrieve: RetrieveRelevantMemoryUseCase = mockk()
    private val memory: MemoryRepository = mockk(relaxed = true)
    private val appended = mutableListOf<RunTraceRecord>()
    private val trace: RunTraceRepository = mockk(relaxed = true) {
        coEvery { append(any()) } answers { appended += firstArg<RunTraceRecord>() }
    }
    private val emitted = mutableListOf<AgentOrchestratorState>()
    private val console = RunConsole(FlowCollector { emitted += it }, trace, "s", "run-1", 0, "G", 0L)
    private val date = object : PromptVariableProvider {
        override fun key(): String = "DATE"
        override suspend fun resolve(): String = "01 October 2026"
    }
    private val factory = NodeInputComposer.Factory(
        chatRepository = chat,
        memorySettings = settings,
        toolSettings = settings,
        promptTemplateEngine = PromptTemplateEngine(),
        promptVariableProviders = setOf(date),
        nodeContextBuilder = NodeContextBuilder(),
        chatHistoryWindowPlanner = ChatHistoryWindowPlanner(),
        retrieveRelevantMemoryUseCase = retrieve,
        memoryRepository = memory,
    )

    init {
        every { settings.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settings.chatHistoryCompressionEnabled } returns flowOf(false)
        every { settings.chatHistoryCompressionThresholdTokens } returns flowOf(1_000)
        every { settings.chatHistoryLiveWindowSize } returns flowOf(10)
        every { settings.workspaceReadTokenBudget } returns flowOf(100)
        every { chat.getMessagesForSession("s") } returns flowOf(emptyList())
        coEvery { chat.getHistorySummary("s") } returns null
        coEvery { retrieve.retrieveScored(any()) } returns listOf(chunk(1) to 0.9f)
    }

    private fun chunk(id: Long) = MemoryChunk(id = id, text = "fact $id", embedding = floatArrayOf(), timestamp = 0L)

    private val graph = PipelineGraph(id = "g", name = "G")

    private fun composer(
        tree: RunTreeContext = RunTreeContext.standalone(),
        pipeline: PipelineGraph = graph,
        snapshot: List<MemoryChunk>? = null,
    ) = factory.open(console, pipeline, "s", "the task", tree, snapshot)

    private fun node(
        type: NodeType,
        config: NodeContextConfig = NodeContextConfig(),
        systemPrompt: String? = null,
        id: String = type.name,
    ) = NodeModel(id = id, type = type, x = 0f, y = 0f, systemPrompt = systemPrompt, contextConfig = config)

    private val memoryOnly = NodeContextConfig(
        chatHistory = false,
        originalTask = false,
        nodeInput = true,
        longTermMemory = true,
        toolResults = false,
    )
    private val historyOnly = memoryOnly.copy(longTermMemory = false, chatHistory = true)
    private val noMemory = memoryOnly.copy(longTermMemory = false)

    private fun consoleLines(type: ConsoleEventType) =
        (emitted.lastOrNull() as? AgentOrchestratorState.ConsoleLog)?.events.orEmpty().filter { it.type == type }

    // --- system prompt ----------------------------------------------------------

    @Test
    fun `given an LLM node with a placeholder then its prompt is rendered, a tool's prompt is left alone`() = runTest {
        val composer = composer()

        val llm = composer.prepare(node(NodeType.LITE_RT, noMemory, systemPrompt = "Today is \$DATE."), "in")
        val tool = node(NodeType.TOOL, noMemory, systemPrompt = "\$DATE")
        val toolPrepared = composer.prepare(tool, "in")

        assertEquals("Today is 01 October 2026.", llm.node.systemPrompt)
        assertSame(tool, toolPrepared.node)
    }

    // --- passthrough ------------------------------------------------------------

    @Test
    fun `given a control-flow node or an echo OUTPUT then the carried input passes through and nothing is read`() =
        runTest {
            val composer = composer()

            for (type in listOf(NodeType.INPUT, NodeType.IF_CONDITION, NodeType.QUEUE_PROCESSOR, NodeType.OUTPUT)) {
                assertEquals(type.name, "carried", composer.prepare(node(type), "carried").input)
            }
            coVerify(exactly = 0) { retrieve.retrieveScored(any()) }
            verify(exactly = 0) { chat.getMessagesForSession(any()) }
        }

    // --- memory -----------------------------------------------------------------

    @Test
    fun `given two nodes that render memory then it is retrieved, logged, counted and snapshotted once`() = runTest {
        val composer = composer()

        val first = composer.prepare(node(NodeType.LITE_RT, memoryOnly, id = "a"), "one").input
        val second = composer.prepare(node(NodeType.LITE_RT, memoryOnly, id = "b"), "two").input

        coVerify(exactly = 1) { retrieve.retrieveScored(any()) }
        coVerify(exactly = 1) { memory.recordUsage(listOf(1L), any()) }
        assertEquals(1, consoleLines(ConsoleEventType.MemoryAccess).size)
        assertEquals(1, appended.filterIsInstance<RunTraceRecord.MemorySnapshot>().size)
        assertTrue(first, first.contains("--- Long-Term Memory ---") && first.contains("fact 1"))
        assertTrue(second, second.contains("fact 1"))
    }

    @Test
    fun `given a resumed run's memory snapshot then nothing is retrieved again`() = runTest {
        val input = composer(snapshot = listOf(chunk(7))).prepare(node(NodeType.LITE_RT, memoryOnly), "in").input

        assertTrue(input, input.contains("fact 7"))
        coVerify(exactly = 0) { retrieve.retrieveScored(any()) }
        coVerify(exactly = 0) { memory.recordUsage(any(), any()) }
    }

    @Test
    fun `given a node that does not render memory then nothing is retrieved`() = runTest {
        composer().prepare(node(NodeType.LITE_RT, noMemory), "in")

        coVerify(exactly = 0) { retrieve.retrieveScored(any()) }
    }

    @Test
    fun `given retrieval fails then the node runs without memory and the run goes on`() = runTest {
        coEvery { retrieve.retrieveScored(any()) } throws IllegalStateException("embedder down")

        val input = composer().prepare(node(NodeType.LITE_RT, memoryOnly), "in").input

        assertFalse(input, input.contains("--- Long-Term Memory ---"))
        assertEquals(1, consoleLines(ConsoleEventType.MemoryAccess).size)
    }

    @Test
    fun `given a background run with a declared query then the rendered query keys retrieval`() = runTest {
        val tree = RunTreeContext.standalone().copy(origin = RunOrigin.TRIGGER)
        val pipeline = graph.copy(memoryRetrievalQuery = "news for \$DATE")

        composer(tree = tree, pipeline = pipeline).prepare(node(NodeType.LITE_RT, memoryOnly), "node input")

        coVerify { retrieve.retrieveScored("news for 01 October 2026") }
    }

    // --- chat history -----------------------------------------------------------

    @Test
    fun `given history-replaying nodes then settings are read once and messages for every node`() = runTest {
        val composer = composer()

        composer.prepare(node(NodeType.LITE_RT, historyOnly, id = "a"), "in")
        composer.prepare(node(NodeType.LITE_RT, historyOnly, id = "b"), "in")

        verify(exactly = 1) { settings.chatHistoryCompressionEnabled }
        verify(exactly = 2) { chat.getMessagesForSession("s") }
    }

    @Test
    fun `given history over budget with no summary then the console says so once per run`() = runTest {
        every { settings.chatHistoryCompressionEnabled } returns flowOf(true)
        every { settings.chatHistoryCompressionThresholdTokens } returns flowOf(1)
        every { settings.chatHistoryLiveWindowSize } returns flowOf(1)
        every { chat.getMessagesForSession("s") } returns flowOf(
            (1..3).map {
                ChatMessage(sessionId = "s", role = Role.USER, content = "message number $it", timestamp = 0L)
            },
        )
        val composer = composer()

        composer.prepare(node(NodeType.LITE_RT, historyOnly, id = "a"), "in")
        composer.prepare(node(NodeType.LITE_RT, historyOnly, id = "b"), "in")

        val notes = consoleLines(ConsoleEventType.HistoryCompression)
        assertEquals(1, notes.size)
        assertEquals("Chat history over budget; summary not ready, kept the last 1 messages", notes.single().message)
    }

    // --- tool results -----------------------------------------------------------

    @Test
    fun `given a recorded tool result then a later node that opts in sees it`() = runTest {
        val composer = composer()
        composer.recordToolResult(ToolInvocationResult(toolName = "search_tool", output = "found it"))

        val input = composer.prepare(node(NodeType.LITE_RT, noMemory.copy(toolResults = true)), "in").input

        assertTrue(input, input.contains("--- Tool Results ---") && input.contains("search_tool"))
    }

    @Test
    fun `given tool text then an on-device node reads its budget and a cloud node does not`() = runTest {
        val composer = composer()
        val withTools = noMemory.copy(toolResults = true)

        composer.prepare(node(NodeType.LITE_RT, withTools, id = "before"), "in")
        verify(exactly = 0) { settings.workspaceReadTokenBudget }

        composer.recordToolResult(ToolInvocationResult(toolName = "read_file", output = "x".repeat(10)))
        composer.prepare(node(NodeType.CLOUD, withTools, id = "cloud"), "in")
        verify(exactly = 0) { settings.workspaceReadTokenBudget }

        composer.prepare(node(NodeType.LITE_RT, withTools, id = "local"), "in")
        verify(exactly = 1) { settings.workspaceReadTokenBudget }
    }

    // --- advice -----------------------------------------------------------------

    @Test
    fun `given pending advice then OUTPUT never gets it and the next composing node gets it once`() = runTest {
        val tree = RunTreeContext.standalone()
        tree.contextNotes.add("WRAP UP")
        val composer = composer(tree = tree)

        val output = composer.prepare(node(NodeType.OUTPUT, noMemory, systemPrompt = "Answer."), "in").input
        val passthrough = composer.prepare(node(NodeType.IF_CONDITION), "carried").input
        val first = composer.prepare(node(NodeType.LITE_RT, noMemory, id = "a"), "in").input
        val second = composer.prepare(node(NodeType.LITE_RT, noMemory, id = "b"), "in").input

        assertFalse(output, output.contains("WRAP UP"))
        assertEquals("carried", passthrough)
        assertTrue(first, first.startsWith("WRAP UP\n\n"))
        assertFalse(second, second.contains("WRAP UP"))
    }

    // --- image ------------------------------------------------------------------

    @Test
    fun `given the run's image then the first on-device node with the task takes it, once, and no other`() {
        val delivery = RunImageDelivery(EngineImageInput("/img.jpg", width = 1, height = 1, sizeBytes = 1L))
        val composer = composer(tree = RunTreeContext.standalone().copy(imageDelivery = delivery, imagePresent = true))

        assertNull(composer.takeImage(node(NodeType.CLOUD)))
        assertNull(composer.takeImage(node(NodeType.LITE_RT, NodeContextConfig(originalTask = false))))
        assertFalse(delivery.consumed)
        assertEquals("/img.jpg", composer.takeImage(node(NodeType.LITE_RT, id = "vision")))
        assertTrue(delivery.consumed)
        assertNull(composer.takeImage(node(NodeType.LITE_RT, id = "later")))
    }

    @Test
    fun `given a run without an image then no node gets one`() {
        assertNull(composer().takeImage(node(NodeType.LITE_RT)))
    }
}
