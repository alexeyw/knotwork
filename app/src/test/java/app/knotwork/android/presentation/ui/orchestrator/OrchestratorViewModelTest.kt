package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelineTargetAvailability
import app.knotwork.android.domain.models.PipelineValidationError
import app.knotwork.android.domain.models.PipelineValidationException
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.prompt.PromptSegment
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.services.PipelineCompositionValidator
import app.knotwork.android.domain.usecases.CreatePipelineUseCase
import app.knotwork.android.domain.usecases.DeletePipelineUseCase
import app.knotwork.android.domain.usecases.DuplicatePipelineUseCase
import app.knotwork.android.domain.usecases.ExportPipelineBundleUseCase
import app.knotwork.android.domain.usecases.GetPromptTemplatesUseCase
import app.knotwork.android.domain.usecases.ImportPipelineBundleUseCase
import app.knotwork.android.domain.usecases.ImportPipelineUseCase
import app.knotwork.android.domain.usecases.LoadPipelineFromPresetUseCase
import app.knotwork.android.domain.usecases.LoadPipelineUseCase
import app.knotwork.android.domain.usecases.RenamePipelineUseCase
import app.knotwork.android.domain.usecases.ResolveSurfacePipelineUseCase
import app.knotwork.android.domain.usecases.SavePipelineAsPresetUseCase
import app.knotwork.android.domain.usecases.SavePipelineUseCase
import app.knotwork.android.domain.usecases.SavePromptAsPresetUseCase
import app.knotwork.android.domain.usecases.SavePromptTemplateUseCase
import app.knotwork.android.domain.usecases.SetSurfacePipelineUseCase
import app.knotwork.android.presentation.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorViewModelTest {

    private lateinit var savePipelineUseCase: SavePipelineUseCase
    private lateinit var loadPipelineUseCase: LoadPipelineUseCase
    private lateinit var importPipelineUseCase: ImportPipelineUseCase
    private lateinit var importPipelineBundleUseCase: ImportPipelineBundleUseCase
    private lateinit var exportPipelineBundleUseCase: ExportPipelineBundleUseCase
    private lateinit var pipelineRepository: PipelineRepository
    private lateinit var loadPipelineFromPresetUseCase: LoadPipelineFromPresetUseCase
    private lateinit var renamePipelineUseCase: RenamePipelineUseCase
    private lateinit var duplicatePipelineUseCase: DuplicatePipelineUseCase
    private lateinit var deletePipelineUseCase: DeletePipelineUseCase
    private lateinit var createPipelineUseCase: CreatePipelineUseCase
    private lateinit var getPromptTemplatesUseCase: GetPromptTemplatesUseCase
    private lateinit var savePromptTemplateUseCase: SavePromptTemplateUseCase
    private lateinit var savePipelineAsPresetUseCase: SavePipelineAsPresetUseCase
    private lateinit var savePromptAsPresetUseCase: SavePromptAsPresetUseCase
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var localModelRepository: LocalModelRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private lateinit var promptPresetRepository: PromptPresetRepository
    private lateinit var compositionValidator: PipelineCompositionValidator
    private lateinit var skillRepository: SkillRepository
    private lateinit var providerDate: PromptVariableProvider
    private lateinit var providerTime: PromptVariableProvider
    private lateinit var viewModel: OrchestratorViewModel

    private val testDispatcher = StandardTestDispatcher()

    /** Drives the http allowlist so the TOOL-picker refresh can be exercised. */
    private lateinit var allowedDomainsFlow: MutableStateFlow<List<String>>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        allowedDomainsFlow = MutableStateFlow(emptyList())
        savePipelineUseCase = mockk()
        // Default to a successful save so the import-then-save pipeline used
        // by `importPipelineFromJson` round-trips without extra wiring.
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)
        loadPipelineUseCase = mockk()
        // Use the real ImportPipelineUseCase against the mocked save: the
        // import path here is exercised end-to-end (parse + persist). No
        // collision by default so a clean import still auto-persists.
        pipelineRepository = mockk()
        coEvery { pipelineRepository.getPipelineById(any()) } returns null
        coEvery { pipelineRepository.savePipelines(any()) } returns Unit
        importPipelineUseCase = ImportPipelineUseCase(savePipelineUseCase, pipelineRepository)
        compositionValidator = mockk()
        importPipelineBundleUseCase = ImportPipelineBundleUseCase(pipelineRepository, compositionValidator)
        exportPipelineBundleUseCase = mockk()
        loadPipelineFromPresetUseCase = mockk()
        renamePipelineUseCase = mockk()
        duplicatePipelineUseCase = mockk()
        deletePipelineUseCase = mockk()
        createPipelineUseCase = mockk()
        getPromptTemplatesUseCase = mockk()
        savePromptTemplateUseCase = mockk()
        savePipelineAsPresetUseCase = mockk()
        savePromptAsPresetUseCase = mockk()
        promptPresetRepository = mockk(relaxed = true) {
            every { getPresetsForType(any()) } returns flowOf(emptyList())
        }
        skillRepository = mockk(relaxed = true)
        apiKeyRepository = mockk()
        toolRepository = mockk()
        localModelRepository = mockk()
        settingsRepository = mockk(relaxed = true) {
            every { defaultPipelineId } returns flowOf(null)
            every { shareTargetPipelineId } returns flowOf(null)
            every { quickSettingsTilePipelineId } returns flowOf(null)
            every { allowedHttpDomains } returns allowedDomainsFlow
        }

        every { loadPipelineUseCase.observeAllPipelines() } returns flowOf(emptyList())
        every { getPromptTemplatesUseCase() } returns flowOf(emptyList())
        every { localModelRepository.getAllModels() } returns flowOf(emptyList())

        every { apiKeyRepository.getOpenAIKey() } returns flowOf(null)
        every { apiKeyRepository.getAnthropicKey() } returns flowOf("key")
        every { apiKeyRepository.getGoogleKey() } returns flowOf(null)
        every { apiKeyRepository.getDeepSeekKey() } returns flowOf(null)

        coEvery { toolRepository.getAvailableTools() } returns listOf(AgentTool("Tool1", "Desc", "{}"))

        promptTemplateEngine = mockk()
        providerDate = mockk {
            every { key() } returns "DATE"
            coEvery { resolve() } returns "01 May 2026"
        }
        providerTime = mockk {
            every { key() } returns "TIME"
            coEvery { resolve() } returns "15:30"
        }

        viewModel = buildViewModel()
    }

    /**
     * Constructs an [OrchestratorViewModel] from the current mock fields. Pulled
     * out of [setUp] so a test can re-stub a dependency (e.g. the saved-pipeline
     * flow) and rebuild the VM before exercising it.
     */
    private fun buildViewModel(): OrchestratorViewModel = OrchestratorViewModel(
        savePipelineUseCase,
        loadPipelineUseCase,
        importPipelineUseCase,
        importPipelineBundleUseCase,
        exportPipelineBundleUseCase,
        loadPipelineFromPresetUseCase,
        renamePipelineUseCase,
        duplicatePipelineUseCase,
        deletePipelineUseCase,
        createPipelineUseCase,
        mockk<ResolveSurfacePipelineUseCase>(relaxed = true),
        mockk<SetSurfacePipelineUseCase>(relaxed = true),
        getPromptTemplatesUseCase,
        savePromptTemplateUseCase,
        savePipelineAsPresetUseCase,
        savePromptAsPresetUseCase,
        apiKeyRepository,
        toolRepository,
        localModelRepository,
        settingsRepository,
        promptTemplateEngine,
        promptPresetRepository,
        compositionValidator,
        setOf(providerDate, providerTime),
        skillRepository,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads provider keys and tools`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(true, state.providerKeys[CloudProvider.ANTHROPIC])
        assertEquals(false, state.providerKeys[CloudProvider.OPENAI])
        assertEquals(1, state.availableTools.size)
        assertEquals("Tool1", state.availableTools[0].name)
    }

    @Test
    fun `adding an allowed domain refreshes the available tools in-session`() = runTest {
        // The repository hides http_request while the allowlist is empty and un-hides it
        // once a domain is added; the picker must reflect that in-session, not after a
        // restart. Key the stub on the current allowlist so it is call-count independent.
        coEvery { toolRepository.getAvailableTools() } answers {
            if (allowedDomainsFlow.value.isEmpty()) {
                listOf(AgentTool("Tool1", "Desc", "{}"))
            } else {
                listOf(AgentTool("Tool1", "Desc", "{}"), AgentTool("http_request", "Desc", "{}"))
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.availableTools.any { it.name == "http_request" })

        allowedDomainsFlow.value = listOf("api.example.com")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.availableTools.any { it.name == "http_request" })
    }

    @Test
    fun `addNode adds a new node to currentPipeline`() {
        // Act
        viewModel.addNode(NodeType.TOOL, 100f, 200f)

        // Assert
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        assertEquals(1, nodes.size)
        assertEquals(NodeType.TOOL, nodes[0].type)
        assertEquals(100f, nodes[0].x)
        assertEquals(200f, nodes[0].y)
    }

    @Test
    fun `moveNode updates coordinates of existing node by delta`() {
        // Arrange
        viewModel.addNode(NodeType.TOOL, 100f, 200f)
        val nodeId = viewModel.uiState.value.currentPipeline.nodes[0].id

        // Act
        viewModel.moveNode(nodeId, 50f, -20f)

        // Assert
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        assertEquals(1, nodes.size)
        assertEquals(150f, nodes[0].x)
        assertEquals(180f, nodes[0].y)
    }

    @Test
    fun `addConnection creates connection if DAG is valid and returns its ID`() {
        // Arrange
        viewModel.addNode(NodeType.INPUT, 0f, 0f)
        viewModel.addNode(NodeType.OUTPUT, 100f, 100f)
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        val n1 = nodes[0].id
        val n2 = nodes[1].id

        // Act
        val connectionId = viewModel.addConnection(n1, n2)

        // Assert
        assertNotNull(connectionId)
        val connections = viewModel.uiState.value.currentPipeline.connections
        assertEquals(1, connections.size)
        assertEquals(connectionId, connections[0].id)
        assertEquals(n1, connections[0].sourceNodeId)
        assertEquals(n2, connections[0].targetNodeId)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `addConnection sets error if DAG becomes invalid (cycle) and returns null`() {
        // Arrange
        viewModel.addNode(NodeType.INPUT, 0f, 0f)
        viewModel.addNode(NodeType.OUTPUT, 100f, 100f)
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        val n1 = nodes[0].id
        val n2 = nodes[1].id

        viewModel.addConnection(n1, n2)

        // Act
        val connectionId = viewModel.addConnection(n2, n1) // This should create a cycle

        // Assert
        assertEquals(null, connectionId)
        val connections = viewModel.uiState.value.currentPipeline.connections
        assertEquals(1, connections.size) // The cyclic connection should not be added
        assertEquals(
            UiText.Resource(
                app.knotwork.android.R.string.errors_orchestrator_cycle_detected,
            ),
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun `updateConnectionLabel updates the label of a connection`() {
        // Arrange
        viewModel.addNode(NodeType.INPUT, 0f, 0f)
        viewModel.addNode(NodeType.OUTPUT, 100f, 100f)
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        val n1 = nodes[0].id
        val n2 = nodes[1].id
        val connectionId = viewModel.addConnection(n1, n2)

        // Act
        viewModel.updateConnectionLabel(connectionId!!, "NewLabel")

        // Assert
        val connections = viewModel.uiState.value.currentPipeline.connections
        assertEquals(1, connections.size)
        assertEquals("NewLabel", connections[0].label)
    }

    @Test
    fun `removeConnection removes connection by ID`() {
        // Arrange
        viewModel.addNode(NodeType.INPUT, 0f, 0f)
        viewModel.addNode(NodeType.OUTPUT, 100f, 100f)
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        val n1 = nodes[0].id
        val n2 = nodes[1].id
        val connectionId = viewModel.addConnection(n1, n2)

        // Act
        viewModel.removeConnection(connectionId!!)

        // Assert
        val connections = viewModel.uiState.value.currentPipeline.connections
        assertEquals(0, connections.size)
    }

    @Test
    fun `removeNode removes node and associated connections`() {
        // Arrange
        viewModel.addNode(NodeType.INPUT, 0f, 0f)
        viewModel.addNode(NodeType.OUTPUT, 100f, 100f)
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        val n1 = nodes[0].id
        val n2 = nodes[1].id
        viewModel.addConnection(n1, n2)

        // Act
        viewModel.removeNode(n1)

        // Assert
        val state = viewModel.uiState.value
        assertEquals(1, state.currentPipeline.nodes.size)
        assertEquals(0, state.currentPipeline.connections.size)
    }

    @Test
    fun `clearPipeline empties nodes and connections`() {
        viewModel.addNode(NodeType.INPUT, 0f, 0f)
        viewModel.clearPipeline()

        val state = viewModel.uiState.value
        assertEquals(0, state.currentPipeline.nodes.size)
        assertEquals(0, state.currentPipeline.connections.size)
    }

    @Test
    fun `given a node is added then the editor reports unsaved changes`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.addNode(NodeType.LITE_RT, 10f, 10f)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Adding a node must mark the pipeline unsaved", viewModel.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun `given a successful save then the editor reports no unsaved changes`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)
        viewModel.addNode(NodeType.LITE_RT, 10f, 10f)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveCurrentPipeline()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("A saved pipeline must not read as unsaved", viewModel.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun `given a failed save then the pipeline still reports unsaved changes`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns
            Result.failure(PipelineValidationException(listOf(PipelineValidationError.MissingOutput)))
        viewModel.addNode(NodeType.LITE_RT, 10f, 10f)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveCurrentPipeline()
        testDispatcher.scheduler.advanceUntilIdle()

        // The failure mode this whole change exists to stop: a Save that did not
        // reach storage must not leave the editor looking clean, or the guard
        // that asks before leaving will not ask.
        assertTrue("A rejected save must leave the work unsaved", viewModel.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun `given an edit made while a save is in flight then it stays unsaved`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)
        viewModel.addNode(NodeType.LITE_RT, 10f, 10f)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveCurrentPipeline()
        // A second node lands before the save coroutine settles the baseline.
        viewModel.addNode(NodeType.OUTPUT, 40f, 40f)
        testDispatcher.scheduler.advanceUntilIdle()

        // The baseline moves to the graph that was persisted, not to whatever is
        // on screen when the save returns — otherwise the second node would be
        // silently counted as saved.
        assertTrue("An edit made during a save is still unsaved", viewModel.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun `given a rejected save then no success feedback is shown`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns
            Result.failure(PipelineValidationException(listOf(PipelineValidationError.MissingOutput)))
        viewModel.addNode(NodeType.LITE_RT, 10f, 10f)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveCurrentPipeline()
        testDispatcher.scheduler.advanceUntilIdle()

        // The editor used to announce "Pipeline saved." the moment the menu item
        // was tapped, so a rejected save reported success and failure at once.
        val state = viewModel.uiState.value
        assertEquals(null, state.feedbackMessage)
        assertTrue("A rejected save must still surface its error", state.errorMessage != null)
    }

    @Test
    fun `given a successful save then the confirmation is shown`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)
        viewModel.addNode(NodeType.LITE_RT, 10f, 10f)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.saveCurrentPipeline()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("A successful save must confirm", viewModel.uiState.value.feedbackMessage != null)
    }

    @Test
    fun `saveCurrentPipeline calls use case and updates state`() = runTest {
        coEvery { savePipelineUseCase(any()) } returns Result.success(Unit)

        viewModel.saveCurrentPipeline()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { savePipelineUseCase(any()) }
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `saveCurrentPipeline handles validation errors and updates errorMessage`() = runTest {
        val errors = listOf(
            PipelineValidationError.MissingInput,
            PipelineValidationError.MissingOutput,
        )
        val exception = PipelineValidationException(errors)
        coEvery { savePipelineUseCase(any()) } returns Result.failure(exception)

        viewModel.saveCurrentPipeline()
        testDispatcher.scheduler.advanceUntilIdle()

        val errorMessage = viewModel.uiState.value.errorMessage
        assertEquals(false, viewModel.uiState.value.isLoading)
        val joined = errorMessage as UiText.Joined
        val ids = joined.parts.map { (it as UiText.Resource).id }
        assertTrue(app.knotwork.android.R.string.errors_orchestrator_validation_missing_input in ids)
        assertTrue(app.knotwork.android.R.string.errors_orchestrator_validation_missing_output in ids)
    }

    @Test
    fun `loadPipeline loads from use case and updates current pipeline`() = runTest {
        val mockPipeline = PipelineGraph(id = "test-1", name = "Test Pipeline")
        coEvery { loadPipelineUseCase.getPipelineById("test-1") } returns mockPipeline

        viewModel.loadPipeline("test-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("test-1", viewModel.uiState.value.currentPipeline.id)
        assertEquals(false, viewModel.uiState.value.isLoading)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `clearError sets error message to null`() {
        viewModel.addNode(NodeType.INPUT, 0f, 0f)
        viewModel.addNode(NodeType.OUTPUT, 100f, 100f)
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        viewModel.addConnection(nodes[0].id, nodes[1].id) // valid
        viewModel.addConnection(nodes[1].id, nodes[0].id) // invalid cycle
        assertTrue(viewModel.uiState.value.errorMessage != null)

        viewModel.clearError()
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `updateNodeTool updates the tool assigned to a specific node`() {
        viewModel.addNode(NodeType.TOOL, 0f, 0f)
        val nodeId = viewModel.uiState.value.currentPipeline.nodes.first().id

        viewModel.updateNodeTool(nodeId, "CalendarTool")

        val updatedNode = viewModel.uiState.value.currentPipeline.nodes.first()
        assertEquals("CalendarTool", updatedNode.toolName)
        assertEquals("CalendarTool", updatedNode.label)
    }

    @Test
    fun `applyBasePreset replaces current pipeline nodes and connections in place`() {
        val originalId = viewModel.uiState.value.currentPipeline.id
        val originalName = viewModel.uiState.value.currentPipeline.name

        viewModel.applyBasePreset()

        val state = viewModel.uiState.value
        val nodes = state.currentPipeline.nodes
        val connections = state.currentPipeline.connections

        assertEquals(10, nodes.size)
        assertEquals(NodeType.INPUT, nodes.find { it.type == NodeType.INPUT }?.type)
        assertEquals(NodeType.INTENT_ROUTER, nodes.find { it.type == NodeType.INTENT_ROUTER }?.type)
        assertEquals(NodeType.DECOMPOSITION, nodes.find { it.type == NodeType.DECOMPOSITION }?.type)
        assertEquals(NodeType.OUTPUT, nodes.find { it.type == NodeType.OUTPUT }?.type)

        assertEquals(12, connections.size)
        // The preset is applied IN PLACE — the pipeline's identity (id and
        // user-given name) is preserved so saving updates the existing
        // pipeline rather than spawning a "Base Preset" twin.
        assertEquals(originalId, state.currentPipeline.id)
        assertEquals(originalName, state.currentPipeline.name)
    }

    @Test
    fun `exportPipelineToJson returns valid json string`() {
        viewModel.applyBasePreset()

        val json = viewModel.exportPipelineToJson()

        assertTrue(json.contains("INPUT"))
        assertTrue(json.contains("LITE_RT"))
        assertTrue(json.contains("OUTPUT"))
    }

    @Test
    fun `importPipelineFromJson updates current pipeline from json`() = runTest {
        viewModel.applyBasePreset()
        val originalName = viewModel.uiState.value.currentPipeline.name
        val json = viewModel.exportPipelineToJson()

        viewModel.clearPipeline()
        assertEquals(0, viewModel.uiState.value.currentPipeline.nodes.size)

        viewModel.importPipelineFromJson(json)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(originalName, state.currentPipeline.name)
        assertEquals(10, state.currentPipeline.nodes.size)
        assertEquals(12, state.currentPipeline.connections.size)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `importPipelineFromJson sets error on invalid json`() = runTest {
        viewModel.importPipelineFromJson("{ invalid json }")
        testDispatcher.scheduler.advanceUntilIdle()

        val err = viewModel.uiState.value.errorMessage as UiText.Dynamic
        assertTrue(err.text.contains("Invalid JSON"))
    }

    @Test
    fun `availableVariables are derived from injected providers and sorted`() {
        val state = viewModel.uiState.value

        assertEquals(listOf("\$DATE", "\$TIME"), state.availableVariables)
    }

    @Test
    fun `requestPromptPreview transitions Hidden to Ready with engine segments`() = runTest {
        val template = "Hello \$DATE"
        val segments = listOf(
            PromptSegment.Literal("Hello "),
            PromptSegment.Resolved("DATE", "01 May 2026"),
        )
        coEvery { promptTemplateEngine.renderSegments(template, any()) } returns segments

        viewModel.requestPromptPreview(template)
        testDispatcher.scheduler.advanceUntilIdle()

        val readyState = viewModel.uiState.value.previewState
        assertTrue(readyState is PromptPreviewState.Ready)
        assertEquals(segments, (readyState as PromptPreviewState.Ready).segments)
        coVerify { promptTemplateEngine.renderSegments(template, any()) }
    }

    @Test
    fun `addNode supports CLARIFICATION type with default fields`() {
        viewModel.addNode(NodeType.CLARIFICATION, 50f, 80f)

        val nodes = viewModel.uiState.value.currentPipeline.nodes
        assertEquals(1, nodes.size)
        val node = nodes[0]
        assertEquals(NodeType.CLARIFICATION, node.type)
        assertEquals(50f, node.x)
        assertEquals(80f, node.y)
        assertEquals(null, node.clarificationTimeoutMs)
        // The default system prompt for CLARIFICATION is the JSON-format instruction.
        assertNotNull(node.systemPrompt)
    }

    @Test
    fun `addNode applies recommended contextConfig for LITE_RT`() {
        viewModel.addNode(NodeType.LITE_RT, 0f, 0f)

        val node = viewModel.uiState.value.currentPipeline.nodes.single()
        assertEquals(NodeContextConfig.defaultForType(NodeType.LITE_RT), node.contextConfig)
        // Sanity: the LITE_RT preset trims chat history / memory / tool results.
        assertEquals(false, node.contextConfig.chatHistory)
        assertEquals(true, node.contextConfig.originalTask)
        assertEquals(true, node.contextConfig.nodeInput)
        assertEquals(false, node.contextConfig.longTermMemory)
        assertEquals(false, node.contextConfig.toolResults)
    }

    @Test
    fun `addNode applies recommended contextConfig for CLOUD`() {
        viewModel.addNode(NodeType.CLOUD, 0f, 0f)

        val node = viewModel.uiState.value.currentPipeline.nodes.single()
        assertEquals(NodeContextConfig.defaultForType(NodeType.CLOUD), node.contextConfig)
        assertEquals(true, node.contextConfig.chatHistory)
        assertEquals(true, node.contextConfig.originalTask)
        assertEquals(true, node.contextConfig.nodeInput)
        assertEquals(false, node.contextConfig.longTermMemory)
        assertEquals(false, node.contextConfig.toolResults)
    }

    @Test
    fun `addNode applies recommended contextConfig for TOOL`() {
        viewModel.addNode(NodeType.TOOL, 0f, 0f)

        val node = viewModel.uiState.value.currentPipeline.nodes.single()
        assertEquals(NodeContextConfig.defaultForType(NodeType.TOOL), node.contextConfig)
        assertEquals(false, node.contextConfig.chatHistory)
        assertEquals(false, node.contextConfig.originalTask)
        assertEquals(true, node.contextConfig.nodeInput)
        assertEquals(false, node.contextConfig.longTermMemory)
        assertEquals(false, node.contextConfig.toolResults)
    }

    @Test
    fun `addNode applies recommended contextConfig for OUTPUT (nodeInput only)`() {
        viewModel.addNode(NodeType.OUTPUT, 0f, 0f)

        val node = viewModel.uiState.value.currentPipeline.nodes.single()
        assertEquals(NodeContextConfig.defaultForType(NodeType.OUTPUT), node.contextConfig)
        assertEquals(false, node.contextConfig.chatHistory)
        assertEquals(false, node.contextConfig.originalTask)
        assertEquals(true, node.contextConfig.nodeInput)
        assertEquals(false, node.contextConfig.longTermMemory)
        assertEquals(false, node.contextConfig.toolResults)
    }

    @Test
    fun `addNode applies recommended contextConfig for CLARIFICATION`() {
        viewModel.addNode(NodeType.CLARIFICATION, 0f, 0f)

        val node = viewModel.uiState.value.currentPipeline.nodes.single()
        assertEquals(NodeContextConfig.defaultForType(NodeType.CLARIFICATION), node.contextConfig)
        assertEquals(false, node.contextConfig.chatHistory)
        assertEquals(true, node.contextConfig.originalTask)
        assertEquals(true, node.contextConfig.nodeInput)
    }

    @Test
    fun `addNode applies recommended contextConfig for QUEUE_PROCESSOR`() {
        viewModel.addNode(NodeType.QUEUE_PROCESSOR, 0f, 0f)

        val node = viewModel.uiState.value.currentPipeline.nodes.single()
        assertEquals(NodeContextConfig.defaultForType(NodeType.QUEUE_PROCESSOR), node.contextConfig)
        assertEquals(true, node.contextConfig.originalTask)
        assertEquals(true, node.contextConfig.nodeInput)
        assertEquals(false, node.contextConfig.chatHistory)
    }

    @Test
    fun `updateNodeClarificationTimeout updates only the target node`() {
        viewModel.addNode(NodeType.CLARIFICATION, 0f, 0f)
        val nodeId = viewModel.uiState.value.currentPipeline.nodes[0].id

        viewModel.updateNodeClarificationTimeout(nodeId, 30_000L)

        val updated = viewModel.uiState.value.currentPipeline.nodes
            .single { it.id == nodeId }
        assertEquals(30_000L, updated.clarificationTimeoutMs)
    }

    @Test
    fun `updateNodeClarificationTimeout clears timeout when null is passed`() {
        viewModel.addNode(NodeType.CLARIFICATION, 0f, 0f)
        val nodeId = viewModel.uiState.value.currentPipeline.nodes[0].id
        viewModel.updateNodeClarificationTimeout(nodeId, 30_000L)

        viewModel.updateNodeClarificationTimeout(nodeId, null)

        val updated = viewModel.uiState.value.currentPipeline.nodes
            .single { it.id == nodeId }
        assertEquals(null, updated.clarificationTimeoutMs)
    }

    @Test
    fun `updateNodeContextConfig updates target node and forces nodeInput true`() {
        viewModel.addNode(NodeType.TOOL, 0f, 0f)
        val nodeId = viewModel.uiState.value.currentPipeline.nodes[0].id

        val requested = NodeContextConfig(
            chatHistory = true,
            originalTask = false,
            nodeInput = false,
            longTermMemory = true,
            toolResults = false,
        )
        viewModel.updateNodeContextConfig(nodeId, requested)

        val updated = viewModel.uiState.value.currentPipeline.nodes.single { it.id == nodeId }
        assertEquals(true, updated.contextConfig.nodeInput)
        assertEquals(true, updated.contextConfig.chatHistory)
        assertEquals(false, updated.contextConfig.originalTask)
        assertEquals(true, updated.contextConfig.longTermMemory)
        assertEquals(false, updated.contextConfig.toolResults)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `updateNodeContextConfig with all flags disabled sets snackbar message and forces nodeInput true`() {
        viewModel.addNode(NodeType.TOOL, 0f, 0f)
        val nodeId = viewModel.uiState.value.currentPipeline.nodes[0].id

        val empty = NodeContextConfig(
            chatHistory = false,
            originalTask = false,
            nodeInput = false,
            longTermMemory = false,
            toolResults = false,
        )
        viewModel.updateNodeContextConfig(nodeId, empty)

        val updated = viewModel.uiState.value.currentPipeline.nodes.single { it.id == nodeId }
        assertEquals(true, updated.contextConfig.nodeInput)
        assertEquals(
            UiText.Resource(
                app.knotwork.android.R.string.errors_orchestrator_at_least_one_source,
            ),
            viewModel.uiState.value.errorMessage,
        )
    }

    @Test
    fun `updateNodeContextConfig leaves other nodes unchanged`() {
        viewModel.addNode(NodeType.TOOL, 0f, 0f)
        viewModel.addNode(NodeType.LITE_RT, 100f, 100f)
        val nodes = viewModel.uiState.value.currentPipeline.nodes
        val targetId = nodes.first { it.type == NodeType.TOOL }.id
        val otherId = nodes.first { it.type == NodeType.LITE_RT }.id

        viewModel.updateNodeContextConfig(
            targetId,
            NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = true,
                longTermMemory = false,
                toolResults = false,
            ),
        )

        val updated = viewModel.uiState.value.currentPipeline.nodes
        val target = updated.single { it.id == targetId }
        val other = updated.single { it.id == otherId }
        assertEquals(false, target.contextConfig.chatHistory)
        assertEquals(false, target.contextConfig.originalTask)
        assertEquals(false, target.contextConfig.longTermMemory)
        assertEquals(false, target.contextConfig.toolResults)
        // The untouched node still carries the recommended default for its
        // type (LITE_RT — `nodeInput` + `originalTask` only).
        assertEquals(NodeContextConfig.defaultForType(NodeType.LITE_RT), other.contextConfig)
    }

    @Test
    fun `updateNodeContextConfig with valid config clears error message`() {
        viewModel.addNode(NodeType.TOOL, 0f, 0f)
        val nodeId = viewModel.uiState.value.currentPipeline.nodes[0].id
        viewModel.updateNodeContextConfig(
            nodeId,
            NodeContextConfig(false, false, false, false, false),
        )
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.updateNodeContextConfig(
            nodeId,
            NodeContextConfig(
                chatHistory = false,
                originalTask = true,
                nodeInput = true,
                longTermMemory = false,
                toolResults = false,
            ),
        )

        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `saveCurrentPipeline maps NodeEmptyContext to localized message with node label`() = runTest {
        viewModel.addNode(NodeType.TOOL, 0f, 0f)
        val node = viewModel.uiState.value.currentPipeline.nodes[0]

        val errors = listOf(
            PipelineValidationError.NodeEmptyContext(node.id),
        )
        val exception = PipelineValidationException(errors)
        coEvery { savePipelineUseCase(any()) } returns Result.failure(exception)

        viewModel.saveCurrentPipeline()
        testDispatcher.scheduler.advanceUntilIdle()

        val msg = viewModel.uiState.value.errorMessage as UiText.Resource
        assertEquals(
            app.knotwork.android.R.string.errors_orchestrator_validation_node_no_sources,
            msg.id,
        )
        assertEquals(listOf(node.label), msg.args)
    }

    @Test
    fun `dismissPromptPreview resets state to Hidden`() = runTest {
        coEvery { promptTemplateEngine.renderSegments(any(), any()) } returns emptyList()
        viewModel.requestPromptPreview("anything")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissPromptPreview()

        assertEquals(PromptPreviewState.Hidden, viewModel.uiState.value.previewState)
    }

    @Test
    fun `renamePipeline patches currentPipeline name and emits feedback when active is renamed`() = runTest {
        // Arrange — load the pipeline so it becomes the active one.
        val active = PipelineGraph(id = "active", name = "Old Name")
        coEvery { loadPipelineUseCase.getPipelineById("active") } returns active
        viewModel.loadPipeline("active")
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { renamePipelineUseCase("active", "  New Name  ") } returns Result.success(Unit)

        // Act
        viewModel.renamePipeline("active", "  New Name  ")
        testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertEquals("New Name", state.currentPipeline.name)
        assertEquals(false, state.isLoading)
        assertEquals(null, state.errorMessage)
        assertEquals(
            UiText.Resource(
                app.knotwork.android.R.string.orchestrator_feedback_pipeline_renamed,
            ),
            state.feedbackMessage,
        )
        coVerify { renamePipelineUseCase("active", "  New Name  ") }
    }

    @Test
    fun `renamePipeline surfaces error and leaves current pipeline unchanged on failure`() = runTest {
        coEvery {
            renamePipelineUseCase("p1", "")
        } returns Result.failure(IllegalArgumentException("Pipeline name cannot be empty"))

        viewModel.renamePipeline("p1", "")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            UiText.Dynamic("Pipeline name cannot be empty"),
            state.errorMessage,
        )
        assertEquals(null, state.feedbackMessage)
    }

    @Test
    fun `duplicatePipeline loads the duplicate as currentPipeline on success`() = runTest {
        val duplicate = PipelineGraph(id = "dup", name = "Source (copy)")
        coEvery { duplicatePipelineUseCase("src") } returns Result.success(duplicate)

        viewModel.duplicatePipeline("src")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("dup", state.currentPipeline.id)
        assertEquals("Source (copy)", state.currentPipeline.name)
        assertEquals(
            UiText.Resource(
                app.knotwork.android.R.string.orchestrator_feedback_pipeline_duplicated,
            ),
            state.feedbackMessage,
        )
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `duplicatePipeline surfaces error on failure`() = runTest {
        val previousPipelineId = viewModel.uiState.value.currentPipeline.id
        coEvery {
            duplicatePipelineUseCase("missing")
        } returns Result.failure(IllegalStateException("Pipeline not found"))

        viewModel.duplicatePipeline("missing")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(previousPipelineId, state.currentPipeline.id)
        assertEquals(
            UiText.Dynamic("Pipeline not found"),
            state.errorMessage,
        )
    }

    @Test
    fun `given the pipeline the editor holds when deleted then the editor moves to the next saved pipeline`() =
        runTest {
            val open = PipelineGraph(id = "open", name = "Open", nodes = listOf(NodeModel("i", NodeType.INPUT, 0f, 0f)))
            val other =
                PipelineGraph(id = "other", name = "Other", nodes = listOf(NodeModel("i", NodeType.INPUT, 0f, 0f)))
            val library = MutableStateFlow(listOf(open, other))
            every { loadPipelineUseCase.observeAllPipelines() } returns library
            // A fresh screen adopts the most recent pipeline — the one that used to be
            // labelled "Active" and could not be deleted.
            val vm = buildViewModel()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("open", vm.uiState.value.currentPipeline.id)
            coEvery { deletePipelineUseCase("open") } returns Result.success(Unit)

            vm.deletePipeline("open")
            testDispatcher.scheduler.advanceUntilIdle()
            library.value = listOf(other)
            testDispatcher.scheduler.advanceUntilIdle()

            // Before: refused after the user had confirmed, with the exception text as the
            // message. Now it is deleted, and the editor no longer holds a graph that a
            // Save would write back under the deleted id.
            val state = vm.uiState.value
            assertEquals(null, state.errorMessage)
            assertEquals("other", state.currentPipeline.id)
            assertEquals(false, state.hasUnsavedChanges)
        }

    @Test
    fun `given the last pipeline when deleted then the editor falls back to an empty scratch pipeline`() = runTest {
        val only = PipelineGraph(id = "only", name = "Only", nodes = listOf(NodeModel("i", NodeType.INPUT, 0f, 0f)))
        val library = MutableStateFlow(listOf(only))
        every { loadPipelineUseCase.observeAllPipelines() } returns library
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("only", vm.uiState.value.currentPipeline.id)
        coEvery { deletePipelineUseCase("only") } returns Result.success(Unit)

        vm.deletePipeline("only")
        testDispatcher.scheduler.advanceUntilIdle()
        library.value = emptyList()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(null, state.errorMessage)
        assertTrue(state.currentPipeline.id != "only")
        assertTrue(state.currentPipeline.nodes.isEmpty())
        assertEquals(false, state.hasUnsavedChanges)
    }

    @Test
    fun `given another pipeline when deleted then the editor keeps what it holds`() = runTest {
        val open = PipelineGraph(id = "open", name = "Open", nodes = listOf(NodeModel("i", NodeType.INPUT, 0f, 0f)))
        coEvery { loadPipelineUseCase.getPipelineById("open") } returns open
        viewModel.loadPipeline("open")
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { deletePipelineUseCase("p2") } returns Result.success(Unit)

        viewModel.deletePipeline("p2")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("open", viewModel.uiState.value.currentPipeline.id)
    }

    @Test
    fun `given the delete fails when deleting the open pipeline then the editor keeps it`() = runTest {
        val open = PipelineGraph(id = "open", name = "Open", nodes = listOf(NodeModel("i", NodeType.INPUT, 0f, 0f)))
        coEvery { loadPipelineUseCase.getPipelineById("open") } returns open
        viewModel.loadPipeline("open")
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery { deletePipelineUseCase("open") } returns Result.failure(RuntimeException("disk full"))

        viewModel.deletePipeline("open")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("open", state.currentPipeline.id)
        assertEquals(UiText.Dynamic("disk full"), state.errorMessage)
        assertEquals(null, state.feedbackMessage)
    }

    @Test
    fun `deletePipeline emits feedback on successful deletion`() = runTest {
        coEvery { deletePipelineUseCase("p2") } returns Result.success(Unit)

        viewModel.deletePipeline("p2")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            UiText.Resource(
                app.knotwork.android.R.string.orchestrator_feedback_pipeline_deleted,
            ),
            state.feedbackMessage,
        )
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun `createNewPipeline loads the seed graph as currentPipeline`() = runTest {
        val seed = PipelineGraph(
            id = "new",
            name = "Brand New",
            nodes = listOf(
                NodeModel("i", NodeType.INPUT, 0f, 0f),
                NodeModel("o", NodeType.OUTPUT, 100f, 0f),
            ),
            connections = listOf(
                ConnectionModel("c", "i", "o"),
            ),
        )
        coEvery { createPipelineUseCase("Brand New") } returns Result.success(seed)

        viewModel.createNewPipeline("Brand New")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("new", state.currentPipeline.id)
        assertEquals(2, state.currentPipeline.nodes.size)
        assertEquals(
            UiText.Resource(
                app.knotwork.android.R.string.orchestrator_feedback_pipeline_created,
            ),
            state.feedbackMessage,
        )
    }

    @Test
    fun `createNewPipeline surfaces validation error from use case`() = runTest {
        coEvery {
            createPipelineUseCase("")
        } returns Result.failure(IllegalArgumentException("Pipeline name cannot be empty"))

        viewModel.createNewPipeline("")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(
            UiText.Dynamic("Pipeline name cannot be empty"),
            state.errorMessage,
        )
        assertEquals(null, state.feedbackMessage)
    }

    @Test
    fun `clearFeedback resets feedbackMessage to null`() = runTest {
        coEvery { deletePipelineUseCase("p2") } returns Result.success(Unit)
        viewModel.deletePipeline("p2")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            UiText.Resource(
                app.knotwork.android.R.string.orchestrator_feedback_pipeline_deleted,
            ),
            viewModel.uiState.value.feedbackMessage,
        )

        viewModel.clearFeedback()

        assertEquals(null, viewModel.uiState.value.feedbackMessage)
    }

    @Test
    fun `createNewPipeline sets pendingEditorNavigation true on success`() = runTest {
        val seed = PipelineGraph(id = "new", name = "Brand New")
        coEvery { createPipelineUseCase("Brand New") } returns Result.success(seed)

        viewModel.createNewPipeline("Brand New")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.uiState.value.pendingEditorNavigation)
    }

    @Test
    fun `createNewPipeline keeps pendingEditorNavigation false on failure`() = runTest {
        coEvery {
            createPipelineUseCase("")
        } returns Result.failure(IllegalArgumentException("Pipeline name cannot be empty"))

        viewModel.createNewPipeline("")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.pendingEditorNavigation)
    }

    @Test
    fun `consumePendingEditorNavigation resets the flag`() = runTest {
        val seed = PipelineGraph(id = "new", name = "Brand New")
        coEvery { createPipelineUseCase("Brand New") } returns Result.success(seed)
        viewModel.createNewPipeline("Brand New")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.pendingEditorNavigation)

        viewModel.consumePendingEditorNavigation()

        assertEquals(false, viewModel.uiState.value.pendingEditorNavigation)
    }

    // ─── Pipeline editor hooks ───────────────────────────

    @Test
    fun `addNode returns the id of the just-added node`() {
        val id = viewModel.addNode(NodeType.LITE_RT, 10f, 20f)
        val newNode = viewModel.uiState.value.currentPipeline.nodes.last()
        assertEquals(id, newNode.id)
    }

    @Test
    fun `updateNodeFromEditor replaces the matching node`() {
        viewModel.addNode(NodeType.LITE_RT, 0f, 0f)
        val node = viewModel.uiState.value.currentPipeline.nodes.first()
        val mutated = node.copy(label = "Renamed", systemPrompt = "new")

        viewModel.updateNodeFromEditor(node.id, mutated)

        val refreshed = viewModel.uiState.value.currentPipeline.nodes.first()
        assertEquals("Renamed", refreshed.label)
        assertEquals("new", refreshed.systemPrompt)
    }

    @Test
    fun `replaceCurrentPipeline swaps the active pipeline`() {
        val replacement = PipelineGraph(id = "x", name = "X")
        viewModel.replaceCurrentPipeline(replacement)
        assertEquals(replacement, viewModel.uiState.value.currentPipeline)
    }

    @Test
    fun `requestFocusNode does not throw and the SharedFlow exists`() {
        // The SharedFlow is wired with `extraBufferCapacity = 1` so `tryEmit` always
        // succeeds even when no collector is active. Asserting that exposed flow is
        // non-null and the emit is silent is enough behaviour-coverage at the VM seam;
        // end-to-end emission timing is covered by the editor's instrumentation tests.
        assertNotNull(viewModel.focusNodeRequest)
        viewModel.requestFocusNode("node-7")
    }

    @Test
    fun `labelFor returns a non-null UiText for every validation error`() {
        val errors: List<PipelineValidationError> = listOf(
            PipelineValidationError.MissingInput,
            PipelineValidationError.MissingOutput,
            PipelineValidationError.MultipleInputs,
            PipelineValidationError.MultipleOutputs,
            PipelineValidationError.HasCycles,
            PipelineValidationError.DisconnectedInput,
            PipelineValidationError.DisconnectedOutput,
            PipelineValidationError.UnreachableNode,
            PipelineValidationError.DeadEndNode,
            PipelineValidationError.NodeEmptyContext(nodeId = "missing"),
        )
        errors.forEach { err -> assertNotNull(viewModel.labelFor(err)) }
    }

    // ─── Save-as-preset ─────────────────────────────

    @Test
    fun `saveCurrentAsPreset packages current pipeline through SavePipelineAsPresetUseCase`() = runTest {
        // Given
        coEvery {
            savePipelineAsPresetUseCase(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns Result.success("preset-1")

        // Sanity: VM starts with a scratch pipeline; load a valid graph first.
        val nodeIn = NodeModel(id = "in", type = NodeType.INPUT, x = 0f, y = 0f, contextConfig = NodeContextConfig())
        val nodeOut = NodeModel(id = "out", type = NodeType.OUTPUT, x = 0f, y = 0f, contextConfig = NodeContextConfig())
        val conn = ConnectionModel(id = "c1", sourceNodeId = "in", targetNodeId = "out")
        val pipeline =
            PipelineGraph(id = "p1", name = "Demo", nodes = listOf(nodeIn, nodeOut), connections = listOf(conn))
        viewModel.replaceCurrentPipeline(pipeline)

        // When
        viewModel.saveCurrentAsPreset(
            name = "My preset",
            description = "demo",
            category = PresetCategory.LOCAL,
            tags = listOf("offline"),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        coVerify(exactly = 1) {
            savePipelineAsPresetUseCase(
                graph = pipeline,
                name = "My preset",
                description = "demo",
                category = PresetCategory.LOCAL,
                tags = listOf("offline"),
            )
        }
        assertNotNull(viewModel.uiState.value.feedbackMessage)
    }

    @Test
    fun `saveCurrentAsPreset surfaces validation error to errorMessage`() = runTest {
        coEvery {
            savePipelineAsPresetUseCase(any(), any(), any(), any(), any())
        } returns Result.failure(IllegalArgumentException("Preset name must not be blank"))

        viewModel.saveCurrentAsPreset(
            name = " ",
            description = "",
            category = PresetCategory.OTHER,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val msg = viewModel.uiState.value.errorMessage
        assertTrue(msg is UiText.Dynamic)
    }

    @Test
    fun `saveAsPresetFromLibrary loads pipeline by id and forwards to the use case`() = runTest {
        // Given
        val nodeIn = NodeModel(id = "in", type = NodeType.INPUT, x = 0f, y = 0f, contextConfig = NodeContextConfig())
        val nodeOut = NodeModel(id = "out", type = NodeType.OUTPUT, x = 0f, y = 0f, contextConfig = NodeContextConfig())
        val conn = ConnectionModel(id = "c1", sourceNodeId = "in", targetNodeId = "out")
        val src = PipelineGraph(id = "p9", name = "Source", nodes = listOf(nodeIn, nodeOut), connections = listOf(conn))
        coEvery { loadPipelineUseCase.getPipelineById("p9") } returns src
        coEvery {
            savePipelineAsPresetUseCase(any(), any(), any(), any(), any())
        } returns Result.success("preset-9")

        // When
        viewModel.saveAsPresetFromLibrary(
            pipelineId = "p9",
            name = "From library",
            description = "",
            category = PresetCategory.HYBRID,
            tags = emptyList(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        coVerify(exactly = 1) {
            savePipelineAsPresetUseCase(
                graph = src,
                name = "From library",
                description = "",
                category = PresetCategory.HYBRID,
                tags = emptyList(),
            )
        }
    }

    @Test
    fun `saveAsPresetFromLibrary surfaces pipeline-not-found error`() = runTest {
        coEvery { loadPipelineUseCase.getPipelineById("missing") } returns null

        viewModel.saveAsPresetFromLibrary(
            pipelineId = "missing",
            name = "x",
            description = "",
            category = PresetCategory.OTHER,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    // ─── Prompt preset surface ────────────────────────────

    @Test
    fun `bundledPresetsForType returns only bundled presets`() = runTest {
        val bundled = PromptPreset(
            id = "b1",
            name = "Bundled",
            description = "",
            nodeType = NodeType.LITE_RT,
            systemPrompt = "You are helpful.",
            isBundled = true,
        )
        val user = bundled.copy(id = "u1", name = "Mine", isBundled = false)
        every { promptPresetRepository.getPresetsForType(NodeType.LITE_RT) } returns
            flowOf(listOf(bundled, user))

        val emitted = mutableListOf<List<PromptPreset>>()
        viewModel.bundledPresetsForType(NodeType.LITE_RT)
            .collect { emitted += it }

        assertEquals(1, emitted.last().size)
        assertEquals("b1", emitted.last().first().id)
    }

    @Test
    fun `userPresetsForType returns only user presets`() = runTest {
        val bundled = PromptPreset(
            id = "b1",
            name = "Bundled",
            description = "",
            nodeType = NodeType.CLOUD,
            systemPrompt = "You are helpful.",
            isBundled = true,
        )
        val user = bundled.copy(id = "u1", name = "Mine", isBundled = false)
        every { promptPresetRepository.getPresetsForType(NodeType.CLOUD) } returns
            flowOf(listOf(bundled, user))

        val emitted = mutableListOf<List<PromptPreset>>()
        viewModel.userPresetsForType(NodeType.CLOUD)
            .collect { emitted += it }

        assertEquals(1, emitted.last().size)
        assertEquals("u1", emitted.last().first().id)
    }

    @Test
    fun `saveCurrentPromptAsPreset sets feedback message on success`() = runTest {
        coEvery {
            savePromptAsPresetUseCase(
                systemPrompt = any(),
                name = any(),
                description = any(),
                nodeType = any(),
                tags = any(),
            )
        } returns Result.success("preset-1")

        viewModel.saveCurrentPromptAsPreset(
            systemPrompt = "You are concise.",
            name = "Concise assistant",
            description = "",
            nodeType = NodeType.LITE_RT,
            tags = listOf("concise"),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(null, state.errorMessage)
        assertNotNull(state.feedbackMessage)
        coVerify(exactly = 1) {
            savePromptAsPresetUseCase(
                systemPrompt = "You are concise.",
                name = "Concise assistant",
                description = "",
                nodeType = NodeType.LITE_RT,
                tags = listOf("concise"),
            )
        }
    }

    @Test
    fun `saveCurrentPromptAsPreset surfaces error on failure`() = runTest {
        coEvery {
            savePromptAsPresetUseCase(
                systemPrompt = any(),
                name = any(),
                description = any(),
                nodeType = any(),
                tags = any(),
            )
        } returns Result.failure(IllegalArgumentException("Preset name must not be blank"))

        viewModel.saveCurrentPromptAsPreset(
            systemPrompt = "ignored",
            name = "",
            description = "",
            nodeType = NodeType.LITE_RT,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `applyPresetToCurrentPipeline fills current pipeline preserving its id and name`() = runTest {
        val originalId = viewModel.uiState.value.currentPipeline.id
        val originalName = viewModel.uiState.value.currentPipeline.name
        val materialized = PipelineGraph(
            id = "preset-instance",
            name = "Preset Name",
            nodes = listOf(
                NodeModel("n1", NodeType.INPUT, 0f, 0f),
                NodeModel("n2", NodeType.OUTPUT, 0f, 0f),
            ),
            connections = emptyList(),
        )
        coEvery { loadPipelineFromPresetUseCase.materialize("preset1") } returns Result.success(materialized)

        viewModel.applyPresetToCurrentPipeline("preset1")
        testDispatcher.scheduler.advanceUntilIdle()

        val current = viewModel.uiState.value.currentPipeline
        // id + name preserved (we fill the existing pipeline, not spawn a new one)…
        assertEquals(originalId, current.id)
        assertEquals(originalName, current.name)
        // …but nodes come from the materialised preset.
        assertEquals(listOf("n1", "n2"), current.nodes.map { it.id })
        assertNotNull(viewModel.uiState.value.feedbackMessage)
    }

    @Test
    fun `applyPresetToCurrentPipeline surfaces error when materialize fails`() = runTest {
        coEvery { loadPipelineFromPresetUseCase.materialize("bad") } returns
            Result.failure(IllegalStateException("Preset not found: bad"))

        viewModel.applyPresetToCurrentPipeline("bad")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `dependentsOf returns only the pipelines that reference the target`() = runTest {
        val target = PipelineGraph(id = "x", name = "Target")
        val dependent = PipelineGraph(
            id = "a",
            name = "Caller",
            nodes = listOf(
                NodeModel(id = "a-n0", type = NodeType.PIPELINE, x = 0f, y = 0f, targetPipelineId = "x"),
            ),
        )
        val unrelated = PipelineGraph(id = "b", name = "Unrelated")
        every { loadPipelineUseCase.observeAllPipelines() } returns
            flowOf(listOf(dependent, unrelated, target))
        val vm = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("a"), vm.dependentsOf("x").map { it.id })
    }

    @Test
    fun `classifyPipelineTargets maps the validator classification`() = runTest {
        coEvery { compositionValidator.classifyTargets(any(), any()) } returns listOf(
            PipelineTargetAvailability(
                pipelineId = "ok",
                name = "OK",
                selectable = true,
                reason = null,
            ),
            PipelineTargetAvailability(
                pipelineId = "loop",
                name = "Loop",
                selectable = false,
                reason = PipelineTargetAvailability.Reason.Cycle("Caller"),
            ),
        )

        val result = viewModel.classifyPipelineTargets()

        assertEquals(listOf("ok", "loop"), result.map { it.pipelineId })
        assertEquals(PipelineTargetAvailability.Reason.Cycle("Caller"), result[1].reason)
    }

    @Test
    fun `classifyPipelineTargets degrades to empty list when the validator throws`() = runTest {
        coEvery { compositionValidator.classifyTargets(any(), any()) } throws IllegalStateException("boom")

        assertEquals(emptyList<PipelineTargetAvailability>(), viewModel.classifyPipelineTargets())
    }
}
