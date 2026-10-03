package app.knotwork.android.domain.engine

import app.knotwork.android.domain.engine.executors.NodeExecutorFactory
import app.knotwork.android.domain.engine.executors.ToolNodeExecutor
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
import app.knotwork.android.domain.usecases.ResolveRunCeilingsUseCase
import app.knotwork.android.domain.usecases.RetrieveRelevantMemoryUseCase

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
        settingsRepository = settingsRepository,
        promptTemplateEngine = promptTemplateEngine,
        promptVariableProviders = promptVariableProviders,
        nodeContextBuilder = nodeContextBuilder,
        chatHistoryWindowPlanner = chatHistoryWindowPlanner,
        retrieveRelevantMemoryUseCase = retrieveRelevantMemoryUseCase,
        memoryRepository = memoryRepository,
    ),
)
