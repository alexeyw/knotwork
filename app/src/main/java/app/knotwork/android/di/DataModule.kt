package app.knotwork.android.di

import app.knotwork.android.data.audio.AudioRecorderImpl
import app.knotwork.android.data.engine.DefaultTextEmbedderFactory
import app.knotwork.android.data.engine.LiteRTLlmEngine
import app.knotwork.android.data.engine.MediaPipeTextEmbeddingEngine
import app.knotwork.android.data.engine.OpenClAccelerationProbe
import app.knotwork.android.data.engine.TaskQueueManagerImpl
import app.knotwork.android.data.engine.TextEmbedderFactory
import app.knotwork.android.data.local.AgentWorkspaceImpl
import app.knotwork.android.data.local.AndroidNativeMemorySampler
import app.knotwork.android.data.local.ApiKeyManager
import app.knotwork.android.data.local.AttachmentStoreImpl
import app.knotwork.android.data.local.AudioCaptureStoreImpl
import app.knotwork.android.data.local.DatabaseResetServiceImpl
import app.knotwork.android.data.local.DownloadedModelFilesImpl
import app.knotwork.android.data.local.ImageCaptureStoreImpl
import app.knotwork.android.data.local.SettingsManager
import app.knotwork.android.data.local.TransientCacheSweeperImpl
import app.knotwork.android.data.local.crypto.AeadCipher
import app.knotwork.android.data.local.crypto.AndroidKeystoreAeadCipher
import app.knotwork.android.data.mcp.KoogMcpClientFactory
import app.knotwork.android.data.mcp.McpClientFactory
import app.knotwork.android.data.network.AndroidModelDownloadManager
import app.knotwork.android.data.repositories.AssetBundledDocumentationRepository
import app.knotwork.android.data.repositories.AssetBundledSkillSource
import app.knotwork.android.data.repositories.BackgroundPromptRepositoryImpl
import app.knotwork.android.data.repositories.BundledSkillSource
import app.knotwork.android.data.repositories.ChatRepositoryImpl
import app.knotwork.android.data.repositories.ClarificationRepositoryImpl
import app.knotwork.android.data.repositories.ExternalAutomationJournalRepositoryImpl
import app.knotwork.android.data.repositories.IdentityRepositoryImpl
import app.knotwork.android.data.repositories.LocalModelRepositoryImpl
import app.knotwork.android.data.repositories.LocalPipelinePresetRepositoryImpl
import app.knotwork.android.data.repositories.LocalPipelineRepositoryImpl
import app.knotwork.android.data.repositories.LocalPromptPresetRepositoryImpl
import app.knotwork.android.data.repositories.McpServerRepositoryImpl
import app.knotwork.android.data.repositories.MemoryRepositoryImpl
import app.knotwork.android.data.repositories.MetricsRepositoryImpl
import app.knotwork.android.data.repositories.ModelDiscoveryRepositoryImpl
import app.knotwork.android.data.repositories.ModelPerformanceRepositoryImpl
import app.knotwork.android.data.repositories.NetworkActivityTrackerImpl
import app.knotwork.android.data.repositories.NetworkStateRepositoryImpl
import app.knotwork.android.data.repositories.PendingInteractionRepositoryImpl
import app.knotwork.android.data.repositories.PipelineRunRepositoryImpl
import app.knotwork.android.data.repositories.PowerStateRepositoryImpl
import app.knotwork.android.data.repositories.PromptRepositoryImpl
import app.knotwork.android.data.repositories.RunTraceRepositoryImpl
import app.knotwork.android.data.repositories.ShareAdmissionRepositoryImpl
import app.knotwork.android.data.repositories.SkillRepositoryImpl
import app.knotwork.android.data.repositories.ToolRepositoryImpl
import app.knotwork.android.data.repositories.TriggerJournalRepositoryImpl
import app.knotwork.android.data.repositories.TriggerRepositoryImpl
import app.knotwork.android.data.repositories.UsageTelemetryRepositoryImpl
import app.knotwork.android.data.services.WorkManagerMemoryReembedScheduler
import app.knotwork.android.data.services.WorkManagerTriggerScheduler
import app.knotwork.android.domain.engine.HardwareAccelerationProbe
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.TaskQueueManager
import app.knotwork.android.domain.engine.TextEmbeddingEngine
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.BackgroundPromptRepository
import app.knotwork.android.domain.repositories.BundledDocumentationRepository
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.ClarificationRepository
import app.knotwork.android.domain.repositories.ExternalAutomationJournalRepository
import app.knotwork.android.domain.repositories.IdentityRepository
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.McpServerRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.ModelDiscoveryRepository
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.domain.repositories.ModelPerformanceRepository
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import app.knotwork.android.domain.repositories.NetworkStateRepository
import app.knotwork.android.domain.repositories.PendingInteractionRepository
import app.knotwork.android.domain.repositories.PipelinePresetRepository
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.repositories.PowerStateRepository
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.repositories.PromptRepository
import app.knotwork.android.domain.repositories.RunTraceRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.repositories.ShareAdmissionRepository
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.repositories.ToolRepository
import app.knotwork.android.domain.repositories.TriggerJournalRepository
import app.knotwork.android.domain.repositories.TriggerRepository
import app.knotwork.android.domain.repositories.UsageTelemetryRepository
import app.knotwork.android.domain.services.AgentWorkspace
import app.knotwork.android.domain.services.AttachmentStore
import app.knotwork.android.domain.services.AudioCaptureStore
import app.knotwork.android.domain.services.AudioRecorder
import app.knotwork.android.domain.services.DatabaseResetService
import app.knotwork.android.domain.services.DownloadedModelFiles
import app.knotwork.android.domain.services.ImageCaptureStore
import app.knotwork.android.domain.services.MemoryReembedScheduler
import app.knotwork.android.domain.services.NativeMemorySampler
import app.knotwork.android.domain.services.TransientCacheSweeper
import app.knotwork.android.domain.services.TriggerScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Data layer dependency injection module.
 *
 * This module is responsible for binding repository implementations from the
 * data layer to their corresponding interfaces in the domain layer.
 * It is installed in the SingletonComponent to ensure that repositories
 * act as single sources of truth throughout the application lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // Binding-only module: one @Binds per data-layer implementation.
abstract class DataModule {

    /**
     * Binds the Android-Keystore-backed [AeadCipher] implementation used by the secret
     * stores ([ApiKeyManager], [app.knotwork.android.data.local.EncryptedDbPassphraseProvider]).
     */
    @Binds
    @Singleton
    abstract fun bindAeadCipher(cipher: AndroidKeystoreAeadCipher): AeadCipher

    /**
     * Binds the [ApiKeyManager] implementation to the [ApiKeyRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindApiKeyRepository(apiKeyManager: ApiKeyManager): ApiKeyRepository

    /**
     * Binds the [DatabaseResetServiceImpl] implementation to the [DatabaseResetService] interface.
     */
    @Binds
    @Singleton
    abstract fun bindDatabaseResetService(service: DatabaseResetServiceImpl): DatabaseResetService

    /**
     * Binds the [LocalModelRepositoryImpl] implementation to the [LocalModelRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindLocalModelRepository(repository: LocalModelRepositoryImpl): LocalModelRepository

    /**
     * Binds the asset-backed implementation to the [BundledDocumentationRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindBundledDocumentationRepository(
        repository: AssetBundledDocumentationRepository,
    ): BundledDocumentationRepository

    /**
     * Binds the [SettingsManager] implementation to the [SettingsRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(settingsManager: SettingsManager): SettingsRepository

    /**
     * Binds the [LiteRTLlmEngine] implementation to the [LlmInferenceEngine] interface.
     */
    @Binds
    @Singleton
    abstract fun bindLlmInferenceEngine(engine: LiteRTLlmEngine): LlmInferenceEngine

    /**
     * Binds the OpenCL-backed [HardwareAccelerationProbe] used to decide whether
     * a first-time install may default to the GPU backend.
     */
    @Binds
    @Singleton
    abstract fun bindHardwareAccelerationProbe(probe: OpenClAccelerationProbe): HardwareAccelerationProbe

    /**
     * Binds the [MediaPipeTextEmbeddingEngine] implementation to the [TextEmbeddingEngine] interface.
     */
    @Binds
    @Singleton
    abstract fun bindTextEmbeddingEngine(engine: MediaPipeTextEmbeddingEngine): TextEmbeddingEngine

    /**
     * Binds the [DefaultTextEmbedderFactory] implementation to the [TextEmbedderFactory] interface.
     */
    @Binds
    @Singleton
    abstract fun bindTextEmbedderFactory(factory: DefaultTextEmbedderFactory): TextEmbedderFactory

    /**
     * Binds the [MemoryRepositoryImpl] implementation to the [MemoryRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindMemoryRepository(repository: MemoryRepositoryImpl): MemoryRepository

    /**
     * Binds the [AndroidModelDownloadManager] implementation to the [ModelDownloadManager] interface.
     */
    @Binds
    @Singleton
    abstract fun bindModelDownloadManager(downloadManager: AndroidModelDownloadManager): ModelDownloadManager

    /**
     * Binds the [ModelDiscoveryRepositoryImpl] implementation to the
     * [ModelDiscoveryRepository] interface (Hugging Face model discovery).
     */
    @Binds
    @Singleton
    abstract fun bindModelDiscoveryRepository(repository: ModelDiscoveryRepositoryImpl): ModelDiscoveryRepository

    /**
     * Binds the [ChatRepositoryImpl] implementation to the [ChatRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindChatRepository(repository: ChatRepositoryImpl): ChatRepository

    /**
     * Binds the [PipelineRunRepositoryImpl] implementation to the
     * [PipelineRunRepository] interface backing the persistent
     * pipeline-run records.
     */
    @Binds
    @Singleton
    abstract fun bindPipelineRunRepository(repository: PipelineRunRepositoryImpl): PipelineRunRepository

    /**
     * Binds the [PendingInteractionRepositoryImpl] implementation to the
     * [PendingInteractionRepository] interface backing the parked HITL
     * interaction records of the two-phase waiting protocol.
     */
    @Binds
    @Singleton
    abstract fun bindPendingInteractionRepository(
        repository: PendingInteractionRepositoryImpl,
    ): PendingInteractionRepository

    /**
     * Binds the [BackgroundPromptRepositoryImpl] implementation to the
     * [BackgroundPromptRepository] interface — the encrypted home of the prompts
     * of queued background runs.
     */
    @Binds
    @Singleton
    abstract fun bindBackgroundPromptRepository(repository: BackgroundPromptRepositoryImpl): BackgroundPromptRepository

    /**
     * Binds the [RunTraceRepositoryImpl] implementation to the
     * [RunTraceRepository] interface backing the buffered persistent
     * pipeline-run trace.
     */
    @Binds
    @Singleton
    abstract fun bindRunTraceRepository(repository: RunTraceRepositoryImpl): RunTraceRepository

    /**
     * Binds the [ToolRepositoryImpl] implementation to the [ToolRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindToolRepository(repository: ToolRepositoryImpl): ToolRepository

    /**
     * Binds the [KoogMcpClientFactory] implementation to the [McpClientFactory] interface.
     */
    @Binds
    @Singleton
    abstract fun bindMcpClientFactory(factory: KoogMcpClientFactory): McpClientFactory

    /**
     * Binds [McpServerRepositoryImpl] to [McpServerRepository] — owns per-server
     * MCP connections, tool-list caching, and the connection-status flows
     * consumed by `ToolsViewModel`.
     */
    @Binds
    @Singleton
    abstract fun bindMcpServerRepository(repository: McpServerRepositoryImpl): McpServerRepository

    /**
     * Binds the [MetricsRepositoryImpl] implementation to the [MetricsRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindMetricsRepository(repository: MetricsRepositoryImpl): MetricsRepository

    /**
     * Binds the [UsageTelemetryRepositoryImpl] implementation to the
     * [UsageTelemetryRepository] interface backing the privacy-preserving
     * on-device usage statistics.
     */
    @Binds
    @Singleton
    abstract fun bindUsageTelemetryRepository(repository: UsageTelemetryRepositoryImpl): UsageTelemetryRepository

    /**
     * Binds the [ModelPerformanceRepositoryImpl] implementation to the
     * [ModelPerformanceRepository] interface backing the per-model inference
     * performance samples (TTFT / decode speed / peak native memory).
     */
    @Binds
    @Singleton
    abstract fun bindModelPerformanceRepository(repository: ModelPerformanceRepositoryImpl): ModelPerformanceRepository

    /**
     * Binds the [AndroidNativeMemorySampler] implementation to the
     * [NativeMemorySampler] interface, reading native-heap usage via
     * `android.os.Debug` during inference.
     */
    @Binds
    @Singleton
    abstract fun bindNativeMemorySampler(sampler: AndroidNativeMemorySampler): NativeMemorySampler

    /**
     * Binds the [PowerStateRepositoryImpl] implementation to the [PowerStateRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindPowerStateRepository(repository: PowerStateRepositoryImpl): PowerStateRepository

    /**
     * Binds the [NetworkStateRepositoryImpl] implementation to the [NetworkStateRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindNetworkStateRepository(repository: NetworkStateRepositoryImpl): NetworkStateRepository

    /**
     * Binds [NetworkActivityTrackerImpl] to [NetworkActivityTracker]. Records every outbound
     * call the app opens itself — cloud models, embeddings, MCP, network tools, Hugging Face —
     * so the More tab can render the "no network calls in last N m" privacy indicator.
     */
    @Binds
    @Singleton
    abstract fun bindNetworkActivityTracker(tracker: NetworkActivityTrackerImpl): NetworkActivityTracker

    /**
     * Binds the [TaskQueueManagerImpl] implementation to the [TaskQueueManager] interface.
     */
    @Binds
    @Singleton
    abstract fun bindTaskQueueManager(taskQueueManager: TaskQueueManagerImpl): TaskQueueManager

    /**
     * Binds the [LocalPipelineRepositoryImpl] implementation to the [PipelineRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindPipelineRepository(repository: LocalPipelineRepositoryImpl): PipelineRepository

    /**
     * Binds [LocalPipelinePresetRepositoryImpl] to [PipelinePresetRepository] — composes the
     * bundled assets/presets/pipelines catalogue with the user-saved Room rows.
     */
    @Binds
    @Singleton
    abstract fun bindPipelinePresetRepository(repository: LocalPipelinePresetRepositoryImpl): PipelinePresetRepository

    /**
     * Binds [LocalPromptPresetRepositoryImpl] to [PromptPresetRepository] — composes the
     * bundled assets/presets/prompts catalogue with the user-saved Room rows.
     */
    @Binds
    @Singleton
    abstract fun bindPromptPresetRepository(repository: LocalPromptPresetRepositoryImpl): PromptPresetRepository

    /**
     * Binds [SkillRepositoryImpl] to [SkillRepository] — the `skills` table
     * holding both the seeded bundled skills and the user-authored ones.
     */
    @Binds
    @Singleton
    abstract fun bindSkillRepository(repository: SkillRepositoryImpl): SkillRepository

    /**
     * Binds [AssetBundledSkillSource] to [BundledSkillSource] — reads the
     * bundled skills shipped under `assets/presets/skills`.
     */
    @Binds
    @Singleton
    abstract fun bindBundledSkillSource(source: AssetBundledSkillSource): BundledSkillSource

    /**
     * Binds [TriggerRepositoryImpl] to [TriggerRepository] — the `triggers`
     * table holding user-defined automation triggers.
     */
    @Binds
    @Singleton
    abstract fun bindTriggerRepository(repository: TriggerRepositoryImpl): TriggerRepository

    /**
     * Binds [TriggerJournalRepositoryImpl] to [TriggerJournalRepository] — the
     * `trigger_evaluations` table backing the trigger-evaluation journal.
     */
    @Binds
    @Singleton
    abstract fun bindTriggerJournalRepository(repository: TriggerJournalRepositoryImpl): TriggerJournalRepository

    /**
     * Binds [ExternalAutomationJournalRepositoryImpl] to
     * [ExternalAutomationJournalRepository] — the request journal of the
     * external-automation entry point, which doubles as the ledger its rate
     * ceiling counts.
     *
     * @param repository The Room-backed implementation.
     * @return The bound repository interface.
     */
    @Binds
    @Singleton
    abstract fun bindExternalAutomationJournalRepository(
        repository: ExternalAutomationJournalRepositoryImpl,
    ): ExternalAutomationJournalRepository

    /**
     * Binds [ShareAdmissionRepositoryImpl] to [ShareAdmissionRepository] — the
     * ledger the share target's rate ceiling counts, kept in the preferences store.
     *
     * @param repository The DataStore-backed implementation.
     * @return The bound repository interface.
     */
    @Binds
    @Singleton
    abstract fun bindShareAdmissionRepository(repository: ShareAdmissionRepositoryImpl): ShareAdmissionRepository

    /**
     * Binds the `WorkManager`-backed [WorkManagerTriggerScheduler] to the
     * domain-level [TriggerScheduler] port that registers each active trigger's
     * constraint-gated background watch.
     */
    @Binds
    @Singleton
    abstract fun bindTriggerScheduler(scheduler: WorkManagerTriggerScheduler): TriggerScheduler

    /**
     * Binds the [PromptRepositoryImpl] implementation to the [PromptRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindPromptRepository(repository: PromptRepositoryImpl): PromptRepository

    /**
     * Binds the [ClarificationRepositoryImpl] implementation to the [ClarificationRepository] interface.
     */
    @Binds
    @Singleton
    abstract fun bindClarificationRepository(repository: ClarificationRepositoryImpl): ClarificationRepository

    /**
     * Binds [IdentityRepositoryImpl] to [IdentityRepository]. Surfaces the
     * Settings identity card snapshot (device-id + Keystore probe).
     */
    @Binds
    @Singleton
    abstract fun bindIdentityRepository(repository: IdentityRepositoryImpl): IdentityRepository

    /**
     * Binds [WorkManagerMemoryReembedScheduler] to [MemoryReembedScheduler] —
     * enqueues the background re-embed pass that repairs chunks imported under a
     * different embedding provider.
     */
    @Binds
    @Singleton
    abstract fun bindMemoryReembedScheduler(scheduler: WorkManagerMemoryReembedScheduler): MemoryReembedScheduler

    /**
     * Binds [AgentWorkspaceImpl] to [AgentWorkspace] — the agent's jailed file
     * sandbox under `files/agent_workspace/`. Singleton so the cached total-size
     * counter and the write mutex are shared across all callers.
     */
    @Binds
    @Singleton
    abstract fun bindAgentWorkspace(workspace: AgentWorkspaceImpl): AgentWorkspace

    /**
     * Binds [AttachmentStoreImpl] to [AttachmentStore] — the on-device image
     * attachment store under `files/attachments/`. Singleton so the write mutex
     * is shared across all callers.
     */
    @Binds
    @Singleton
    abstract fun bindAttachmentStore(store: AttachmentStoreImpl): AttachmentStore

    /**
     * Binds [DownloadedModelFilesImpl] to [DownloadedModelFiles] — the read-only
     * listing of model files in the downloads directory, used to register again
     * the models a database reset forgot.
     */
    @Binds
    abstract fun bindDownloadedModelFiles(files: DownloadedModelFilesImpl): DownloadedModelFiles

    /**
     * Binds [AudioCaptureStoreImpl] to [AudioCaptureStore] — the ephemeral
     * voice-input clip store under `cacheDir/audio/`. Singleton so recorder and
     * transcription callers share one view of the cache directory.
     */
    @Binds
    @Singleton
    abstract fun bindAudioCaptureStore(store: AudioCaptureStoreImpl): AudioCaptureStore

    /**
     * Binds [ImageCaptureStoreImpl] to [ImageCaptureStore] — the camera-capture
     * files under `cacheDir/images/`, read once and deleted on ingest.
     */
    @Binds
    @Singleton
    abstract fun bindImageCaptureStore(store: ImageCaptureStoreImpl): ImageCaptureStore

    /**
     * Binds [TransientCacheSweeperImpl] to [TransientCacheSweeper] — the daily
     * backstop over every handoff directory in the cache.
     */
    @Binds
    @Singleton
    abstract fun bindTransientCacheSweeper(sweeper: TransientCacheSweeperImpl): TransientCacheSweeper

    /**
     * Binds [AudioRecorderImpl] to [AudioRecorder] — the platform-`AudioRecord`
     * voice capture writing 16 kHz mono PCM WAV into the audio cache.
     */
    @Binds
    @Singleton
    abstract fun bindAudioRecorder(recorder: AudioRecorderImpl): AudioRecorder
}
