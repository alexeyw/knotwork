package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ratchet on the production files that depend on the whole `SettingsRepository` composite: a new
 * file may not, and the list of files that still do can only shrink.
 *
 * **Why.** The composite is every setting at once. A consumer that holds it can read and write any
 * of them, and a test of that consumer has to fake all of them. The settings are split into
 * sections (`GenerationSettings`, `MemorySettings`, …); production code depends on the section it
 * reads. The composite stays while the existing consumers move, and tests may keep mocking it — a
 * mock of the composite is a mock of every section.
 *
 * **What it reads.** The production sources with comments removed ([ProductionSources]), so a KDoc
 * link to the composite does not count and a use in code does. A listed file that no longer names
 * the composite fails too: delete its line, which is how the list shrinks.
 */
class SettingsCompositeConsumersTest {

    @Test
    fun `no production file depends on the settings composite unless it is listed`() {
        val consumers = ProductionSources.code
            .filter { (path, code) -> COMPOSITE.containsMatchIn(code) && !path.endsWith(DECLARATION) }
            .keys.mapTo(sortedSetOf()) { it.replace(PACKAGE_PATH, "") }

        assertEquals(
            "a new dependency on SettingsRepository — depend on the section the code reads instead " +
                "(GenerationSettings, NetworkSettings, MemorySettings, ToolSettings, RunSettings, " +
                "EntryPointSettings, PrivacySettings, AppStateSettings, SettingsReset)",
            sortedSetOf<String>(),
            consumers - LISTED_CONSUMERS,
        )
        assertEquals(
            "these files no longer depend on SettingsRepository — delete their lines; the list only shrinks",
            sortedSetOf<String>(),
            LISTED_CONSUMERS - consumers,
        )
    }

    @Test
    fun `the census recognises a use and ignores a mention`() {
        assertEquals(true, COMPOSITE.containsMatchIn("private val settings: SettingsRepository,"))
        assertEquals(false, COMPOSITE.containsMatchIn("private val settings: SettingsRepositoryImpl,"))
        assertEquals(
            false,
            COMPOSITE.containsMatchIn(ProductionSources.stripComments("/** Reads [SettingsRepository]. */")),
        )
    }

    private companion object {
        /** The composite's name as a whole word. */
        val COMPOSITE = Regex("""\bSettingsRepository\b""")

        /** The composite's own declaration, which is not a consumer. */
        const val DECLARATION = "/domain/repositories/SettingsRepository.kt"

        /** Dropped from each path to keep the list readable: `main/data/local/…`. */
        const val PACKAGE_PATH = "java/app/knotwork/android/"

        /**
         * The production files that still depend on the composite, by source set and package path.
         * 87 when the sections were introduced; the target is the few that span every
         * section — the implementation, its Hilt module, the settings screen's ViewModel and the
         * end-to-end test entry point.
         */
        val LISTED_CONSUMERS = sortedSetOf(
            "main/data/engine/LiteRTLlmEngine.kt",
            "main/data/engine/ModelNetworkGate.kt",
            "main/data/engine/retry/CloudRetryWrapper.kt",
            "main/data/local/AgentWorkspaceImpl.kt",
            "main/data/local/SettingsManager.kt",
            "main/data/mcp/KoogMcpClient.kt",
            "main/data/mcp/McpConnectionPool.kt",
            "main/data/prompt/MemorySummaryVariableProvider.kt",
            "main/data/repositories/ToolRepositoryImpl.kt",
            "main/data/services/MemoryCompactionScheduler.kt",
            "main/data/services/MemoryCompactionWorker.kt",
            "main/data/services/ModelDownloadWorker.kt",
            "main/data/services/PendingInteractionMaintenanceWorker.kt",
            "main/data/testing/AppFunctionsE2ETestEntryPoint.kt",
            "main/data/tools/local/SearchTool.kt",
            "main/data/tools/local/executors/HttpRequestExecutor.kt",
            "main/data/tools/local/executors/ReadFileExecutor.kt",
            "main/di/SettingsModule.kt",
            "main/domain/engine/NodeInputComposer.kt",
            "main/domain/engine/executors/CloudLlmNodeExecutor.kt",
            "main/domain/engine/executors/LiteRtNodeExecutor.kt",
            "main/domain/engine/executors/PipelineNodeExecutor.kt",
            "main/domain/engine/executors/SystemNodeExecutor.kt",
            "main/domain/engine/executors/ToolInvocationGate.kt",
            "main/domain/engine/executors/ToolNodeExecutor.kt",
            "main/domain/services/ChatHistoryCompressionCoordinator.kt",
            "main/domain/services/EmbeddingProviderResolver.kt",
            "main/domain/services/MemoryAutoExtractionCoordinator.kt",
            "main/domain/services/PipelineCompositionValidator.kt",
            "main/domain/usecases/CompressChatHistoryUseCase.kt",
            "main/domain/usecases/EstimateCompactionUseCase.kt",
            "main/domain/usecases/EvaluateIfConditionUseCase.kt",
            "main/domain/usecases/ExportMemoryBaseUseCase.kt",
            "main/domain/usecases/GetContextWindowUseCase.kt",
            "main/domain/usecases/InstallDiscoveredModelUseCase.kt",
            "main/domain/usecases/MemoryCompactionUseCase.kt",
            "main/domain/usecases/MemoryExtractionUseCase.kt",
            "main/domain/usecases/ParkedRunResumer.kt",
            "main/domain/usecases/PrepareInferenceBackendUseCase.kt",
            "main/domain/usecases/ResetSamplingDefaultsUseCase.kt",
            "main/domain/usecases/ResetToRecommendedDefaultsUseCase.kt",
            "main/domain/usecases/ResolveRunCeilingsUseCase.kt",
            "main/domain/usecases/ResumePipelineRunUseCase.kt",
            "main/domain/usecases/RetrieveRelevantMemoryUseCase.kt",
            "main/domain/usecases/TestBackendUseCase.kt",
            "main/presentation/notifications/ScheduledTaskNotifierImpl.kt",
            "main/presentation/ui/chat/home/ChatHomeReattachDelegate.kt",
            "main/presentation/ui/chat/home/ChatHomeViewModel.kt",
            "main/presentation/ui/chat/home/ChatHomeVoiceDelegate.kt",
            "main/presentation/ui/discover/DiscoverDetailViewModel.kt",
            "main/presentation/ui/memory/MemoryViewModel.kt",
            "main/presentation/ui/models/ModelsViewModel.kt",
            "main/presentation/ui/orchestrator/OrchestratorViewModel.kt",
            "main/presentation/ui/settings/GenerationSettingsDelegate.kt",
            "main/presentation/ui/settings/MemorySettingsDelegate.kt",
            "main/presentation/ui/settings/ModelsSettingsDelegate.kt",
            "main/presentation/ui/settings/PipelinesSettingsDelegate.kt",
            "main/presentation/ui/settings/SettingsViewModel.kt",
            "main/presentation/ui/settings/ToolsSettingsDelegate.kt",
            "main/presentation/ui/settings/provider/ProviderDetailScreen.kt",
            "main/presentation/ui/settings/runlimits/RunLimitsViewModel.kt",
            "main/presentation/ui/tools/AllowedDomainsViewModel.kt",
            "main/presentation/ui/tools/McpServerConfigViewModel.kt",
            "main/presentation/ui/tools/ToolsViewModel.kt",
        )
    }
}
