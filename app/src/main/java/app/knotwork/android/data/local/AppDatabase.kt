package app.knotwork.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.knotwork.android.data.local.dao.BackgroundPromptDao
import app.knotwork.android.data.local.dao.ChatDao
import app.knotwork.android.data.local.dao.ChatHistorySummaryDao
import app.knotwork.android.data.local.dao.ExternalAutomationJournalDao
import app.knotwork.android.data.local.dao.LocalModelDao
import app.knotwork.android.data.local.dao.MemoryDao
import app.knotwork.android.data.local.dao.MemoryHistoryDao
import app.knotwork.android.data.local.dao.ModelPerformanceDao
import app.knotwork.android.data.local.dao.PendingInteractionDao
import app.knotwork.android.data.local.dao.PipelineDao
import app.knotwork.android.data.local.dao.PipelinePresetDao
import app.knotwork.android.data.local.dao.PipelineRunDao
import app.knotwork.android.data.local.dao.PromptPresetDao
import app.knotwork.android.data.local.dao.PromptTemplateDao
import app.knotwork.android.data.local.dao.SkillDao
import app.knotwork.android.data.local.dao.TraceStepDao
import app.knotwork.android.data.local.dao.TriggerDao
import app.knotwork.android.data.local.dao.TriggerJournalDao
import app.knotwork.android.data.local.dao.UsageTelemetryDao
import app.knotwork.android.data.local.models.BackgroundPromptEntity
import app.knotwork.android.data.local.models.ChatHistorySummaryEntity
import app.knotwork.android.data.local.models.ChatMessageEntity
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.data.local.models.ConnectionEntity
import app.knotwork.android.data.local.models.ExternalAutomationRequestEntity
import app.knotwork.android.data.local.models.LocalModelEntity
import app.knotwork.android.data.local.models.MemoryChunkEntity
import app.knotwork.android.data.local.models.MemoryChunkVersionEntity
import app.knotwork.android.data.local.models.MemoryPendingUpdateEntity
import app.knotwork.android.data.local.models.ModelCallEntity
import app.knotwork.android.data.local.models.ModelPerformanceSampleEntity
import app.knotwork.android.data.local.models.NodeEntity
import app.knotwork.android.data.local.models.OnboardingMilestoneEntity
import app.knotwork.android.data.local.models.PendingInteractionEntity
import app.knotwork.android.data.local.models.PipelineEntity
import app.knotwork.android.data.local.models.PipelinePresetEntity
import app.knotwork.android.data.local.models.PipelineRunEntity
import app.knotwork.android.data.local.models.PromptPresetEntity
import app.knotwork.android.data.local.models.PromptTemplateEntity
import app.knotwork.android.data.local.models.SkillEntity
import app.knotwork.android.data.local.models.TraceStepEntity
import app.knotwork.android.data.local.models.TriggerEntity
import app.knotwork.android.data.local.models.TriggerEvaluationEntity
import app.knotwork.android.data.local.models.UsageActiveDayEntity
import app.knotwork.android.data.local.models.UsageCounterEntity
import app.knotwork.android.data.local.models.UsagePipelineDayEntity

/**
 * Main Room Database for Knotwork.
 *
 * Future entities (e.g., PromptTemplates) will be registered here.
 *
 * **Versioning & migrations.** The current schema [version] is the durability baseline:
 * every bump from here on must add a matching `MIGRATION_<old>_<new>` constant in its own
 * `data/local/migrations/Migration<old>To<new>.kt` file and append it to
 * [app.knotwork.android.data.local.migrations.ALL_MIGRATIONS], the list
 * [app.knotwork.android.di.AppModule] registers. There is no destructive fallback on upgrade, so
 * an unsupplied migration fails fast in development rather than silently dropping user data. The
 * exported `app/schemas/<package>/<version>.json` snapshots back the `MigrationTestHelper`
 * regression suite.
 */
@Database(
    entities = [
        LocalModelEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        MemoryChunkEntity::class,
        MemoryChunkVersionEntity::class,
        MemoryPendingUpdateEntity::class,
        PipelineEntity::class,
        NodeEntity::class,
        ConnectionEntity::class,
        PromptTemplateEntity::class,
        TraceStepEntity::class,
        ModelCallEntity::class,
        PipelinePresetEntity::class,
        PromptPresetEntity::class,
        PipelineRunEntity::class,
        PendingInteractionEntity::class,
        SkillEntity::class,
        ChatHistorySummaryEntity::class,
        ModelPerformanceSampleEntity::class,
        TriggerEntity::class,
        TriggerEvaluationEntity::class,
        ExternalAutomationRequestEntity::class,
        UsageCounterEntity::class,
        UsageActiveDayEntity::class,
        UsagePipelineDayEntity::class,
        OnboardingMilestoneEntity::class,
        BackgroundPromptEntity::class,
    ],
    version = 70,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /**
     * Provides access to the LocalModelDao.
     *
     * @return The [LocalModelDao] instance.
     */
    abstract fun localModelDao(): LocalModelDao

    /**
     * Provides access to the ChatDao.
     *
     * @return The [ChatDao] instance.
     */
    abstract fun chatDao(): ChatDao

    /**
     * Provides access to the MemoryDao.
     *
     * @return The [MemoryDao] instance.
     */
    abstract fun memoryDao(): MemoryDao

    /**
     * Provides access to the MemoryHistoryDao.
     *
     * @return The [MemoryHistoryDao] instance.
     */
    abstract fun memoryHistoryDao(): MemoryHistoryDao

    /**
     * Provides access to the PipelineDao.
     *
     * @return The [PipelineDao] instance.
     */
    abstract fun pipelineDao(): PipelineDao

    /**
     * Provides access to the PromptTemplateDao.
     *
     * @return The [PromptTemplateDao] instance.
     */
    abstract fun promptTemplateDao(): PromptTemplateDao

    /**
     * Provides access to the TraceStepDao.
     *
     * @return The [TraceStepDao] instance.
     */
    abstract fun traceStepDao(): TraceStepDao

    /**
     * Provides access to the [PipelinePresetDao] backing the user-saved
     * pipeline-preset catalogue.
     *
     * @return The [PipelinePresetDao] instance.
     */
    abstract fun pipelinePresetDao(): PipelinePresetDao

    /**
     * Provides access to the [PromptPresetDao] backing the user-saved
     * prompt-preset catalogue.
     *
     * @return The [PromptPresetDao] instance.
     */
    abstract fun promptPresetDao(): PromptPresetDao

    /**
     * Provides access to the [PipelineRunDao] backing the persistent
     * pipeline-run records.
     *
     * @return The [PipelineRunDao] instance.
     */
    abstract fun pipelineRunDao(): PipelineRunDao

    /**
     * Provides access to the [PendingInteractionDao] backing the parked
     * HITL interaction records of the two-phase waiting protocol.
     *
     * @return The [PendingInteractionDao] instance.
     */
    abstract fun pendingInteractionDao(): PendingInteractionDao

    /**
     * Provides access to the [SkillDao] backing the skill catalogue (bundled +
     * user skills).
     *
     * @return The [SkillDao] instance.
     */
    abstract fun skillDao(): SkillDao

    /**
     * Provides access to the [ChatHistorySummaryDao] backing the per-session
     * compressed-history summaries.
     *
     * @return The [ChatHistorySummaryDao] instance.
     */
    abstract fun chatHistorySummaryDao(): ChatHistorySummaryDao

    /**
     * Provides access to the [ModelPerformanceDao] backing the per-model
     * inference performance samples (TTFT / decode speed / peak native memory)
     * shown on the model screen.
     *
     * @return The [ModelPerformanceDao] instance.
     */
    abstract fun modelPerformanceDao(): ModelPerformanceDao

    /**
     * Provides access to the [TriggerDao] backing user-defined automation
     * triggers (the `triggers` table).
     *
     * @return The [TriggerDao] instance.
     */
    abstract fun triggerDao(): TriggerDao

    /**
     * Provides access to the [TriggerJournalDao] backing the trigger-evaluation
     * journal (the `trigger_evaluations` table) — the durable, on-device log of
     * every trigger evaluation and the fate of the runs it started.
     *
     * @return The [TriggerJournalDao] instance.
     */
    abstract fun triggerJournalDao(): TriggerJournalDao

    /**
     * Provides access to the [UsageTelemetryDao] backing the privacy-preserving
     * local usage statistics (the `usage_counter` and `usage_active_day`
     * tables). Every figure stays on-device.
     *
     * @return The [UsageTelemetryDao] instance.
     */
    abstract fun usageTelemetryDao(): UsageTelemetryDao

    /**
     * Provides access to the [ExternalAutomationJournalDao] backing the
     * external-automation request journal (the `external_automation_requests`
     * table) — the durable log of every request a third-party app sent to this
     * one, admitted or refused.
     *
     * @return The [ExternalAutomationJournalDao] instance.
     */
    abstract fun externalAutomationJournalDao(): ExternalAutomationJournalDao

    /**
     * Provides access to the [BackgroundPromptDao] backing the prompts of queued
     * background runs (the `background_prompts` table).
     *
     * @return The [BackgroundPromptDao] instance.
     */
    abstract fun backgroundPromptDao(): BackgroundPromptDao

    companion object {
        /**
         * Canonical on-disk file name of the encrypted Room database. Single
         * source of truth shared by the Hilt provider, the passphrase
         * provider's "does the database already exist" invariant check, and
         * the explicit wipe path — so the three can never drift apart.
         */
        const val DATABASE_NAME: String = "agent_database.db"
    }
}
