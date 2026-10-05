package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.executors.NodeExecutorFactory
import app.knotwork.android.domain.engine.executors.ToolNodeExecutor
import app.knotwork.android.domain.models.RunHeader
import app.knotwork.android.domain.models.RunSampler
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.CrashReportingRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.CeilingNotifier
import app.knotwork.android.domain.services.RunEnvironment
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

/**
 * Builds a [GraphExecutionEngine] for a test from the flat list of dependencies the
 * test fakes.
 *
 * The parameters are the repositories, use cases and helpers the engine depends on,
 * in the order its constructor once took them; this function assembles the
 * collaborator factories the constructor takes now. A test names what it fakes and
 * keeps compiling as the engine's constructor regroups those dependencies.
 *
 * @return An engine wired exactly as Hilt wires it, from the given parts.
 */
fun testGraphExecutionEngine(
    nodeExecutorFactory: NodeExecutorFactory,
    toolNodeExecutor: ToolNodeExecutor,
    chatRepository: ChatRepository,
    settingsRepository: SettingsRepository,
    metricsRepository: MetricsRepository,
    promptTemplateEngine: PromptTemplateEngine,
    promptVariableProviders: Set<PromptVariableProvider>,
    nodeContextBuilder: NodeContextBuilder,
    chatHistoryWindowPlanner: ChatHistoryWindowPlanner,
    retrieveRelevantMemoryUseCase: RetrieveRelevantMemoryUseCase,
    crashReportingRepository: CrashReportingRepository,
    localModelRepository: LocalModelRepository,
    memoryRepository: MemoryRepository,
    pipelineRunRepository: PipelineRunRepository,
    runTraceRepository: RunTraceRepository,
    resolveRunCeilingsUseCase: ResolveRunCeilingsUseCase,
    pendingInteractionRepository: PendingInteractionRepository,
    ceilingNotifier: CeilingNotifier,
    runHeaders: RunHeaders = fixedRunHeaders(),
): GraphExecutionEngine = GraphExecutionEngine(
    nodeExecutorFactory = nodeExecutorFactory,
    toolNodeExecutor = toolNodeExecutor,
    metricsRepository = metricsRepository,
    crashReportingRepository = crashReportingRepository,
    localModelRepository = localModelRepository,
    runTraceRepository = runTraceRepository,
    resolveRunCeilingsUseCase = resolveRunCeilingsUseCase,
    runRecords = RunRecordWriter.Factory(
        pipelineRunRepository = pipelineRunRepository,
        pendingInteractionRepository = pendingInteractionRepository,
        ceilingNotifier = ceilingNotifier,
        runTraceRepository = runTraceRepository,
    ),
    nodeInputs = NodeInputComposer.Factory(
        chatRepository = chatRepository,
        memorySettings = settingsRepository,
        toolSettings = settingsRepository,
        promptTemplateEngine = promptTemplateEngine,
        promptVariableProviders = promptVariableProviders,
        nodeContextBuilder = nodeContextBuilder,
        chatHistoryWindowPlanner = chatHistoryWindowPlanner,
        retrieveRelevantMemoryUseCase = retrieveRelevantMemoryUseCase,
        memoryRepository = memoryRepository,
    ),
    runHeaders = runHeaders,
)

/** The header every run of a [fixedRunHeaders] factory starts with. */
val TEST_RUN_HEADER: RunHeader = RunHeader(
    seed = 1234,
    sampler = RunSampler(temperature = 0.7, topK = 40, topP = 0.9),
    appVersion = "test (1)",
    runtimeVersion = "LiteRT-LM test",
    device = "Test Device · Android 16",
)

/**
 * A header factory that always yields [header] — for engine tests that do not
 * test the header and must not depend on the settings mock stubbing a sampler.
 *
 * @param header The header each fresh run gets.
 * @return The factory.
 */
fun fixedRunHeaders(header: RunHeader = TEST_RUN_HEADER): RunHeaders = RunHeaders(
    generationSettings = mockk {
        every { temperature } returns flowOf(header.sampler.temperature.toFloat())
        every { topK } returns flowOf(header.sampler.topK)
        every { topP } returns flowOf(header.sampler.topP.toFloat())
    },
    seedSource = { header.seed },
    environment = object : RunEnvironment {
        override val appVersion: String = header.appVersion
        override val runtimeVersion: String = header.runtimeVersion
        override val device: String = header.device
    },
)
