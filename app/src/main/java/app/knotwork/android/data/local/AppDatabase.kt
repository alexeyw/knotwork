package app.knotwork.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.knotwork.android.data.local.dao.ChatDao
import app.knotwork.android.data.local.dao.ChatHistorySummaryDao
import app.knotwork.android.data.local.dao.ExternalAutomationJournalDao
import app.knotwork.android.data.local.dao.LocalModelDao
import app.knotwork.android.data.local.dao.MemoryDao
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
import app.knotwork.android.data.local.models.ChatHistorySummaryEntity
import app.knotwork.android.data.local.models.ChatMessageEntity
import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.data.local.models.ConnectionEntity
import app.knotwork.android.data.local.models.ExternalAutomationRequestEntity
import app.knotwork.android.data.local.models.LocalModelEntity
import app.knotwork.android.data.local.models.MemoryChunkEntity
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
 * every bump from here on must add a matching `MIGRATION_<old>_<new>` constant below and
 * register it in [app.knotwork.android.di.AppModule] via `addMigrations(...)`. There is no
 * destructive fallback on upgrade, so an unsupplied migration fails fast in development rather
 * than silently dropping user data. The exported `app/schemas/<package>/<version>.json`
 * snapshots back the `MigrationTestHelper` regression suite.
 */
@Database(
    entities = [
        LocalModelEntity::class,
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        MemoryChunkEntity::class,
        PipelineEntity::class,
        NodeEntity::class,
        ConnectionEntity::class,
        PromptTemplateEntity::class,
        TraceStepEntity::class,
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
    ],
    version = 61,
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

    companion object {
        /**
         * Canonical on-disk file name of the encrypted Room database. Single
         * source of truth shared by the Hilt provider, the passphrase
         * provider's "does the database already exist" invariant check, and
         * the explicit wipe path — so the three can never drift apart.
         */
        const val DATABASE_NAME: String = "agent_database.db"

        /**
         * Migration from version 9 to 10.
         * Adds the `prompt_templates` table.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_templates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `text` TEXT NOT NULL, 
                        `category` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Migration from version 10 to 11.
         * Updates the `prompt_templates` table to make `category` NOT NULL.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_templates_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `text` TEXT NOT NULL, 
                        `category` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `prompt_templates_new` (`id`, `name`, `text`, `category`)
                    SELECT `id`, `name`, `text`, COALESCE(`category`, 'Default') FROM `prompt_templates`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `prompt_templates`")
                db.execSQL("ALTER TABLE `prompt_templates_new` RENAME TO `prompt_templates`")
            }
        }

        /**
         * Migration from version 11 to 12.
         * Updates previously created default prompts that had the 'Default' category to their correct NodeType.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `prompt_templates` SET `category` = 'INTENT_ROUTER' " +
                        "WHERE `category` = 'Default' AND `name` = 'Classifier'",
                )
                db.execSQL(
                    "UPDATE `prompt_templates` SET `category` = 'DECOMPOSITION' " +
                        "WHERE `category` = 'Default' AND `name` = 'Decomposer'",
                )
                db.execSQL(
                    "UPDATE `prompt_templates` SET `category` = 'SUMMARY' " +
                        "WHERE `category` = 'Default' AND `name` = 'Summarizer'",
                )
                db.execSQL(
                    "UPDATE `prompt_templates` SET `category` = 'TOOL' " +
                        "WHERE `category` = 'Default' AND `name` = 'Tool Picker'",
                )

                // For any other prompts that somehow ended up as 'Default', reassign them to CUSTOM or something safe, or leave them.
                // We'll leave the rest as is, but users can edit them.
            }
        }

        /**
         * Migration from version 12 to 13.
         * Adds `modelPath` column to `pipeline_nodes` table.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `modelPath` TEXT")
            }
        }

        /**
         * Migration from version 13 to 14.
         * Adds `cloudProvider` column to `pipeline_nodes` table.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `cloudProvider` TEXT")
            }
        }

        /**
         * Migration from version 14 to 15.
         * Adds `trace_steps` table.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trace_steps` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `nodeName` TEXT NOT NULL,
                        `outputText` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_steps_sessionId` ON `trace_steps` (`sessionId`)")
            }
        }

        /**
         * Migration from version 15 to 16.
         * Adds `durationMs` and `tokenCount` columns to `trace_steps` so that per-node
         * execution time and token usage can be persisted alongside the trace output.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `tokenCount` INTEGER")
            }
        }

        /**
         * Migration from version 16 to 17.
         * Adds `clarificationTimeoutMs` column to `pipeline_nodes` for the new
         * CLARIFICATION node type, which suspends the pipeline until the user replies
         * (or the configured timeout elapses).
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `clarificationTimeoutMs` INTEGER")
            }
        }

        /**
         * Migration from version 17 to 18.
         *
         * Adds the `context_config` column to `pipeline_nodes` that stores the
         * per-node [app.knotwork.android.domain.models.NodeContextConfig] as a JSON
         * blob. The default value enables every flag (`chatHistory`,
         * `originalTask`, `nodeInput`, `longTermMemory`, `toolResults`) so that
         * existing pipelines keep receiving the full context on every node.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pipeline_nodes` ADD COLUMN `context_config` TEXT NOT NULL " +
                        "DEFAULT '{\"chatHistory\":true,\"originalTask\":true,\"nodeInput\":true," +
                        "\"longTermMemory\":true,\"toolResults\":true}'",
                )
            }
        }

        /**
         * Migration from version 18 to 19 — pipeline binding to chat.
         *
         * Adds the nullable `pipelineId` column to `chat_sessions`. `NULL` means the
         * chat uses the application-wide default pipeline (the user-marked
         * `SettingsRepository.defaultPipelineId`), preserving the prior
         * default for every existing row without requiring a data backfill.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `pipelineId` TEXT")
            }
        }

        /**
         * Adds the `isFinal` and `isStarred` columns to `chat_messages`.
         *
         * - `isFinal` — distinguishes user-facing messages (USER input, final AGENT
         *   answers) from intermediate node outputs (tool observations, internal
         *   SYSTEM logs). Backfilled to `1` so every pre-existing message keeps
         *   rendering in the main chat list.
         * - `isStarred` — backs the new "save message" action; defaults to `0`
         *   for all legacy rows.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `isFinal` INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `isStarred` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Migration from version 20 to 21 — pipeline editor.
         *
         * Adds the nullable `config_json` column to `pipeline_nodes`. The column
         * stores the per-type `NodeConfig` payload edited by the
         * `NodeConfigSheet` (catalog `pipelineeditor.NodeConfig`) as a JSON blob.
         *
         * `NULL` is the canonical "no payload yet" value for every pre-existing
         * row; on first edit the editor derives a default config
         * from the flat columns (`systemPrompt`, `cloudProvider`, `toolName`,
         * `conditionComplexity`, …) and writes the encoded payload here. The
         * flat columns are kept untouched so the orchestrator runtime path
         * (which still reads them) keeps working unchanged.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `config_json` TEXT")
            }
        }

        /**
         * Migration from version 21 to 22 — chat-session favorites.
         *
         * Adds the `isStarred` column to `chat_sessions` so the drawer can
         * surface favorited chats at the top of the list. Backfilled to `0`
         * for every existing row.
         *
         * Distinct from the message-level `isStarred` introduced in
         * `MIGRATION_19_20` on `chat_messages`.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chat_sessions` ADD COLUMN `isStarred` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Migration from version 22 to 23 — memory chunk pinning.
         *
         * Adds the `isPinned` column to `memory_chunks` so users can mark a
         * memory chunk as pinned. Pinned rows sort ahead of unpinned rows on
         * the memory surface and are exempt from future `compactMemory()`
         * passes. Backfilled to `0` for every existing row.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memory_chunks` ADD COLUMN `isPinned` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Migration from version 23 to 24 — pipeline presets.
         *
         * Adds the `pipeline_presets` table backing the user-saved
         * preset catalogue. Bundled presets live in
         * `assets/presets/pipelines` and never reach this table.
         *
         * The graph is stored as a JSON blob produced by
         * `PipelinePresetJsonSerializer.serialize(...)` so preset rows
         * are self-contained and the existing pipeline schema can evolve
         * independently of stored presets.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pipeline_presets` (
                        `id` TEXT PRIMARY KEY NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `categoryKey` TEXT NOT NULL,
                        `graphJson` TEXT NOT NULL,
                        `tagsCsv` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Migration from version 24 to 25 — prompt presets.
         *
         * Adds the `prompt_presets` table backing the user-saved
         * prompt-preset catalogue. Bundled presets live in
         * `assets/presets/prompts` and never reach this table.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_presets` (
                        `id` TEXT PRIMARY KEY NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `nodeTypeKey` TEXT NOT NULL,
                        `systemPrompt` TEXT NOT NULL,
                        `tagsCsv` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Migration from version 25 to 26 — memory chunk provenance.
         *
         * Adds the `source` column to `memory_chunks` recording each chunk's
         * provenance ([app.knotwork.android.domain.models.MemorySource]) as a
         * compact JSON string. Existing rows predate source attribution, so
         * they are backfilled to the `Unknown` encoding — identical to what
         * `Converters.fromMemorySource(MemorySource.Unknown)` produces and to
         * the entity's column default, keeping the Room-generated schema and
         * this migration in agreement.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `memory_chunks` ADD COLUMN `source` TEXT NOT NULL " +
                        "DEFAULT '{\"type\":\"unknown\"}'",
                )
            }
        }

        /**
         * Migration from version 26 to 27.
         *
         * Adds three additive columns to `memory_chunks` for the redesigned
         * Memory surface: `tagsCsv` (comma-separated tag list, default empty),
         * `useCount` (retrieval counter, default `0`) and `lastUsedAt`
         * (nullable epoch-millis of the most recent retrieval). All defaults
         * match the entity column defaults so the Room-generated schema and
         * this migration agree; existing rows keep their data untouched.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `tagsCsv` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `useCount` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `lastUsedAt` INTEGER")
            }
        }

        /**
         * Migration from version 27 to 28 — memory export/import.
         *
         * Adds the `needsReembedding` column to `memory_chunks`. Set to `1` on
         * chunks imported from a device whose active embedding provider differs
         * from the local one: their stored vectors live in an incompatible
         * space and are re-computed lazily on the next retrieval
         * ([app.knotwork.android.domain.usecases.RecomputePendingEmbeddingsUseCase]).
         * Backfilled to `0` for every existing row — locally-written chunks are
         * already in the active provider's space. The default matches the entity
         * column default so the Room-generated schema and this migration agree.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memory_chunks` ADD COLUMN `needsReembedding` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration from version 28 to 29 — binary embedding storage.
         *
         * Rebuilds `memory_chunks` so the `embedding` column changes type from
         * TEXT (comma-separated floats) to BLOB (little-endian IEEE-754 floats,
         * 4 bytes per component, no header — see
         * [app.knotwork.android.data.local.EmbeddingBlobCodec]). SQLite cannot
         * change a column type in place, so the migration creates the new
         * table, streams every row through a Kotlin-side string → binary
         * conversion, then drops the old table and renames the new one.
         *
         * A legacy embedding string that cannot be parsed (blank or carrying a
         * non-numeric component) converts to a **zero-length blob** rather than
         * dropping the row: such rows are already invisible to similarity
         * retrieval, but their `text` is intact and the re-embedding repair
         * path can still recompute a fresh vector from it — deleting them here
         * would silently destroy user data. The empty blob decodes to `null`
         * at the entity boundary, preserving the pre-migration semantics
         * exactly.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `memory_chunks_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `text` TEXT NOT NULL,
                        `embedding` BLOB NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `source` TEXT NOT NULL DEFAULT '{"type":"unknown"}',
                        `tagsCsv` TEXT NOT NULL DEFAULT '',
                        `useCount` INTEGER NOT NULL DEFAULT 0,
                        `lastUsedAt` INTEGER,
                        `needsReembedding` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.query(
                    "SELECT `id`, `text`, `embedding`, `timestamp`, `isPinned`, `source`, " +
                        "`tagsCsv`, `useCount`, `lastUsedAt`, `needsReembedding` FROM `memory_chunks`",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "INSERT INTO `memory_chunks_new` (`id`, `text`, `embedding`, `timestamp`, " +
                                "`isPinned`, `source`, `tagsCsv`, `useCount`, `lastUsedAt`, `needsReembedding`) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                cursor.getLong(0),
                                cursor.getString(1),
                                parseLegacyEmbedding(cursor.getString(2)),
                                cursor.getLong(3),
                                cursor.getLong(4),
                                cursor.getString(5),
                                cursor.getString(6),
                                cursor.getLong(7),
                                if (cursor.isNull(8)) null else cursor.getLong(8),
                                cursor.getLong(9),
                            ),
                        )
                    }
                }
                db.execSQL("DROP TABLE `memory_chunks`")
                db.execSQL("ALTER TABLE `memory_chunks_new` RENAME TO `memory_chunks`")
            }

            /**
             * Parses the legacy comma-separated embedding string into the
             * binary BLOB form. This is the only remaining home of the
             * pre-BLOB string codec — kept private to the migration so no
             * production read/write path can resurrect the text encoding.
             *
             * @param value The legacy column value.
             * @return The encoded bytes, or a zero-length array when [value]
             *   is blank or contains a non-numeric component.
             */
            private fun parseLegacyEmbedding(value: String?): ByteArray {
                if (value.isNullOrBlank()) return ByteArray(0)
                val parts = value.split(",")
                val floats = FloatArray(parts.size)
                for (index in parts.indices) {
                    floats[index] = parts[index].toFloatOrNull() ?: return ByteArray(0)
                }
                return EmbeddingBlobCodec.encode(floats)
            }
        }

        /**
         * Migration from version 29 to 30.
         * Adds the `pipeline_runs` table — the persistent record of pipeline
         * runs that survives process death (see `PipelineRunEntity`). Purely
         * additive: no existing rows are touched. `sessionId` deliberately
         * carries no foreign key (a run may be created before its session row
         * exists — scheduler-originated sessions are materialised on first
         * message save); the index pair backs the per-session queries and the
         * status-driven orphan sweep.
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pipeline_runs` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `pipelineId` TEXT,
                        `origin` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `currentNodeId` TEXT,
                        `startedAt` INTEGER NOT NULL,
                        `finishedAt` INTEGER,
                        `errorMessage` TEXT,
                        `graphContentHash` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pipeline_runs_sessionId` " +
                        "ON `pipeline_runs` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pipeline_runs_status` " +
                        "ON `pipeline_runs` (`status`)",
                )
            }
        }

        /**
         * Migration from version 30 to 31 — persistent run trace.
         *
         * Extends `trace_steps` from per-session node outputs into the full
         * per-run trace (see `TraceStepEntity`): `runId` (FK to `pipeline_runs`
         * with CASCADE delete, indexed), `seq` (monotonic in-run position used
         * by the console replay/live seam), `recordKind` (`NODE_IO` vs
         * `CONSOLE_EVENT` discriminator), `consoleEventType`, `nodeId` and
         * `inputText`.
         *
         * SQLite cannot add a foreign key to an existing table, so the
         * migration recreates it: new table, `INSERT … SELECT` copy, drop,
         * rename, then index recreation. Legacy rows are preserved with
         * `runId = NULL` (no run attribution existed before this version),
         * `seq = 0` and `recordKind = 'NODE_IO'` — every pre-31 row is a node
         * output by construction.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trace_steps_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `nodeName` TEXT NOT NULL,
                        `outputText` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `tokenCount` INTEGER,
                        `runId` TEXT,
                        `seq` INTEGER NOT NULL,
                        `recordKind` TEXT NOT NULL,
                        `consoleEventType` TEXT,
                        `nodeId` TEXT,
                        `inputText` TEXT,
                        FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`runId`) REFERENCES `pipeline_runs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `trace_steps_new` (
                        `id`, `sessionId`, `nodeName`, `outputText`, `timestamp`,
                        `durationMs`, `tokenCount`, `runId`, `seq`, `recordKind`,
                        `consoleEventType`, `nodeId`, `inputText`
                    )
                    SELECT
                        `id`, `sessionId`, `nodeName`, `outputText`, `timestamp`,
                        `durationMs`, `tokenCount`, NULL, 0, 'NODE_IO',
                        NULL, NULL, NULL
                    FROM `trace_steps`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `trace_steps`")
                db.execSQL("ALTER TABLE `trace_steps_new` RENAME TO `trace_steps`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_steps_sessionId` ON `trace_steps` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trace_steps_runId` ON `trace_steps` (`runId`)")
            }
        }

        /**
         * Migration from version 31 to 32 — checkpoint/resume support.
         *
         * Purely additive `ALTER TABLE … ADD COLUMN` statements; no existing
         * rows are rewritten:
         *
         * - `trace_steps.conditionResult` / `routingKey` / `resolvedToolName`
         *   — the recorded routing verdicts and tool attribution of `NODE_IO`
         *   rows, needed to restore IF_CONDITION / INTENT_ROUTER / EVALUATION
         *   branches and tool observations when an interrupted run is replayed
         *   from its persisted trace (`NULL` for legacy rows: resume of runs
         *   interrupted under the previous schema falls back gracefully —
         *   branch restoration just lacks the recorded verdicts, exactly as if
         *   the nodes had never recorded them).
         * - `pipeline_runs.userPrompt` — the user message that started the
         *   run, captured at enqueue time; resume feeds it back to the engine
         *   as the immutable original prompt. `NULL` for legacy rows, which
         *   are therefore not resumable.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `conditionResult` INTEGER")
                db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `routingKey` TEXT")
                db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `resolvedToolName` TEXT")
                db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `userPrompt` TEXT")
            }
        }

        /**
         * Migration from version 32 to 33 — persistent background HITL.
         *
         * Adds the `pending_interactions` table holding the parked HITL
         * interaction of a run whose live in-process waiting phase timed out
         * (see `PendingInteractionEntity`). One row per run (`runId` primary
         * key); indexed by `sessionId` for the chat reattach lookup.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_interactions` (" +
                        "`runId` TEXT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`toolName` TEXT, " +
                        "`toolArgs` TEXT, " +
                        "`risk` TEXT, " +
                        "`question` TEXT, " +
                        "`optionsJson` TEXT, " +
                        "`decision` TEXT, " +
                        "`answer` TEXT, " +
                        "`requestedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`runId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_interactions_sessionId` " +
                        "ON `pending_interactions` (`sessionId`)",
                )
            }
        }

        /**
         * Adds the nullable `targetPipelineId` column to `pipeline_nodes`. It
         * carries the callee id of a `PIPELINE` node (the composition primitive
         * that runs another pipeline as a sub-step); `null` for every other node
         * type, so existing rows need no backfill.
         */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `targetPipelineId` TEXT")
            }
        }

        /**
         * Migration from version 34 to 35 — nested-pipeline run tree and trace
         * nesting level.
         *
         * Purely additive `ALTER TABLE … ADD COLUMN` statements; no existing
         * rows are rewritten:
         *
         * - `pipeline_runs.parentRunId` — the parent run of a sub-pipeline run
         *   spawned by a `PIPELINE` node (`NULL` for top-level runs, including
         *   every legacy row). Declared as a self-referential foreign key with
         *   `ON DELETE CASCADE` so retention of a root run removes its whole
         *   sub-tree; indexed for the child-run lookups that rebuild the tree.
         *   SQLite permits adding a column with a `REFERENCES` clause in place
         *   precisely because the default value is `NULL`, so no table rebuild
         *   (and no risky drop of the `trace_steps` foreign-key parent) is
         *   needed.
         * - `trace_steps.depth` — pipeline-nesting level of the run that
         *   produced the record (`0` top-level, `1` a direct sub-pipeline, …),
         *   so the console can render a sub-pipeline's logs/vars/trace spans
         *   nested under the spawning `PIPELINE` node. Backfilled to `0` for
         *   every existing row, matching the entity column default.
         */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pipeline_runs` ADD COLUMN `parentRunId` TEXT " +
                        "REFERENCES `pipeline_runs`(`id`) ON DELETE CASCADE",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pipeline_runs_parentRunId` " +
                        "ON `pipeline_runs` (`parentRunId`)",
                )
                db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `depth` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v35 → v36: adds the `skills` table backing the skill catalogue
         * (bundled + user skills). Additive — no existing data is touched.
         * `toolAllowlistCsv` is nullable on purpose: `NULL` encodes the
         * "all tools" (unrestricted) state, distinct from an empty string
         * which encodes "no tools".
         */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skills` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `instruction` TEXT NOT NULL,
                        `toolAllowlistCsv` TEXT,
                        `contextConfig` TEXT NOT NULL,
                        `isBundled` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the nullable `skillId` column to `pipeline_nodes`, backing
         * [NodeType.SKILL][app.knotwork.android.domain.models.NodeType.SKILL]
         * nodes. Additive and nullable, so existing rows (every node predates
         * the skill feature) default to `NULL` — "no skill referenced".
         */
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `skillId` TEXT")
            }
        }

        /**
         * Adds the `chat_history_summaries` table backing long-session chat
         * compression. One row per session (PK = `sessionId`), with a
         * `ON DELETE CASCADE` foreign key onto `chat_sessions(id)` so a summary
         * is cleaned up with its conversation. Additive — existing sessions
         * start with no summary (the engine renders the full history until the
         * background compressor produces one).
         */
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_history_summaries` (
                        `sessionId` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `coveredMessageCount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the image-attachment columns to `chat_messages`: the store-relative
         * path, MIME type, and pixel dimensions of an image attached to a user
         * message. All nullable and additive — pre-existing rows keep `NULL`
         * (no attachment). The image bytes live in the on-device attachment store
         * (`filesDir/attachments/`), not the database.
         */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentPath` TEXT")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentMimeType` TEXT")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentWidth` INTEGER")
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `attachmentHeight` INTEGER")
            }
        }

        /**
         * Adds the `supportsVision` flag to `local_models`. A user-set marker
         * declaring a model vision-capable (able to read an attached image),
         * read by the multimodal pre-flight send guard. Additive and backfilled
         * to `0` (text-only) for every existing row, matching the entity column
         * default — the LiteRT-LM runtime exposes no capability probe, so vision
         * support is opt-in rather than auto-detected.
         */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_models` ADD COLUMN `supportsVision` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the `supportsAudio` flag to `local_models`. A user-set marker
         * declaring a model audio-capable (able to transcribe a recorded/picked
         * audio clip), read by the voice-input transcription pre-flight.
         * Additive and backfilled to `0` (audio-incapable) for every existing
         * row, matching the entity column default — like vision support, the
         * LiteRT-LM runtime exposes no capability probe, so audio support is
         * opt-in rather than auto-detected.
         */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_models` ADD COLUMN `supportsAudio` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the `model_performance_samples` table backing the model screen's
         * Performance card (per-model TTFT, decode speed and peak native memory)
         * and the controlled benchmark. Additive — no existing rows are touched.
         *
         * Samples are keyed by `modelPath` (the on-disk path of the model that
         * ran) rather than a foreign key onto `local_models`, so attribution
         * never depends on resolving the "Active model" sentinel and a sample
         * survives the model row being edited. The `(modelPath, id)` index backs
         * the "most recent N for this model" rolling-window query.
         */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `model_performance_samples` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `modelPath` TEXT NOT NULL,
                        `ttftMs` INTEGER NOT NULL,
                        `decodeTokensPerSec` REAL NOT NULL,
                        `totalMs` INTEGER NOT NULL,
                        `tokenCount` INTEGER NOT NULL,
                        `peakNativeHeapBytes` INTEGER NOT NULL,
                        `isBenchmark` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_model_performance_samples_modelPath_id` " +
                        "ON `model_performance_samples` (`modelPath`, `id`)",
                )
            }
        }

        /**
         * Adds an index on `chat_messages.sessionId`, the filter column of every
         * hot chat query (loading a chat, deleting a session's messages,
         * collecting its attachment paths). Additive — index-only, no row is
         * touched; the name matches Room's generated `index_<table>_<column>`.
         */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` " +
                        "ON `chat_messages` (`sessionId`)",
                )
            }
        }

        /**
         * Adds the nullable `modelName` column to `chat_messages` so an AGENT
         * answer records the model that generated it (snapshotted at save time)
         * and the chat keeps attributing it correctly after the active model is
         * switched. Additive and nullable — legacy rows keep `NULL`.
         */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_messages` ADD COLUMN `modelName` TEXT")
            }
        }

        /**
         * Adds the `triggers` table backing user-defined automation triggers
         * (a persisted `condition → bound pipeline` rule). Additive — no
         * existing rows are touched.
         *
         * The activation condition is stored as a JSON string in `conditionJson`
         * (see [app.knotwork.android.domain.triggerio.TriggerConditionCodec]) so
         * the schema stays narrow across condition shapes. `pipelineId` carries
         * no foreign key: a trigger may outlive its bound pipeline, and the fire
         * path auto-disables a trigger whose pipeline has been deleted. The
         * `enabled` index backs the active-trigger query the scheduler sync runs.
         */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `triggers` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `pipelineId` TEXT,
                        `prompt` TEXT NOT NULL,
                        `conditionJson` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `armed` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL,
                        `lastFiredAt` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_triggers_enabled` ON `triggers` (`enabled`)",
                )
            }
        }

        /**
         * Adds the `sessionId` column to the `triggers` table backing the bound
         * chat session a trigger's background runs land in. Additive — the column
         * is nullable with no default, so existing trigger rows keep `NULL` and
         * lazily bind a session on their next fire.
         *
         * No foreign key: a trigger may outlive (or be reconfigured past) the
         * bound session, which is detected at fire time and replaced rather than
         * cascaded here.
         */
        val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `triggers` ADD COLUMN `sessionId` TEXT")
            }
        }

        /**
         * Adds the local usage-telemetry tables backing the privacy-preserving
         * Usage statistics screen. Additive — no existing rows are touched.
         *
         * - `usage_counter` — a generic `(category, counterKey) → count` tally
         *   (terminal root runs per pipeline and per outcome, trigger firings per
         *   kind). The composite primary key matches the entity so atomic UPSERT
         *   increments target a single row.
         * - `usage_active_day` — the set of distinct device-local active days
         *   (one row per ISO `yyyy-MM-dd` day), backing the daily-active count.
         *
         * Both tables live in the SQLCipher-encrypted database and nothing they
         * hold ever leaves the device.
         */
        val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_counter` (
                        `category` TEXT NOT NULL,
                        `counterKey` TEXT NOT NULL,
                        `count` INTEGER NOT NULL,
                        PRIMARY KEY(`category`, `counterKey`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_active_day` (
                        `day` TEXT NOT NULL,
                        PRIMARY KEY(`day`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v47 → v48: adds the nullable `conditionHasImage` flag to `pipeline_nodes`,
         * backing the IF_CONDITION node's "branch True when the input carries an image"
         * deterministic check. Purely additive `ALTER TABLE … ADD COLUMN`; existing rows
         * read `NULL` (image-presence branching off, treated identically to `false`).
         *
         * The column is nullable on purpose — SQLite cannot add a NOT NULL column without a
         * constant default, and (more importantly) this migration already shipped to dev
         * builds as a nullable `INTEGER`; tightening it to NOT NULL afterwards would leave
         * those devices' column nullable while the entity expected NOT NULL, crashing Room's
         * post-migration schema validation. A migration that has run must never be mutated.
         */
        val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `conditionHasImage` INTEGER")
            }
        }

        /**
         * v48 → v49: adds the non-null `hadImage` flag to `pipeline_runs`, recording
         * whether the run's originating message carried an image. Persisted so a
         * checkpoint resume can still report image presence to IF/router nodes that
         * execute live past the resume point (a resumed run never re-delivers the
         * image). Purely additive `ALTER TABLE … ADD COLUMN`; existing rows default
         * to `0` (no image).
         */
        val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `hadImage` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v49 → v50: adds the non-null `samplePrompts` column to `pipelines`,
         * storing the pipeline's starter ("quick action") suggestions shown on
         * the new-chat empty state as a JSON array string. Purely additive
         * `ALTER TABLE … ADD COLUMN`; existing rows default to `'[]'` (no
         * suggestions), which the `Converters` type converter reads as an empty
         * list.
         */
        val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipelines` ADD COLUMN `samplePrompts` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * Adds the `trigger_evaluations` table backing the trigger-evaluation
         * journal — one row per evaluated *(trigger × moment)* recording the
         * verdict (fire / re-arm / typed skip) and, for a fire, the eventual fate
         * of the enqueued run. Additive — no existing rows are touched.
         *
         * The sealed verdict / run-outcome are flattened into string
         * discriminators (`verdictKind`, `skipReason`, `outcomeKind`,
         * `outcomeError`) rather than a JSON blob so the "by trigger, by time"
         * read and the run-outcome update stay plain indexed SQL. Two indexes are
         * created: `(triggerId, evaluatedAt)` backs the newest-first per-trigger
         * journal query, and `runId` backs the outcome-attribution update.
         *
         * No foreign key on `triggerId`: the journal is a diagnostic observer that
         * deliberately survives its trigger's deletion (its growth is bounded by
         * the retention pass, not a cascade), mirroring the FK-free convention of
         * `pipeline_runs` / `pending_interactions`. The table lives in the
         * SQLCipher-encrypted database and nothing it holds ever leaves the device.
         */
        val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trigger_evaluations` (
                        `id` TEXT NOT NULL,
                        `triggerId` TEXT NOT NULL,
                        `evaluatedAt` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `verdictKind` TEXT NOT NULL,
                        `skipReason` TEXT,
                        `runId` TEXT,
                        `outcomeKind` TEXT,
                        `outcomeError` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trigger_evaluations_triggerId_evaluatedAt` " +
                        "ON `trigger_evaluations` (`triggerId`, `evaluatedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trigger_evaluations_runId` " +
                        "ON `trigger_evaluations` (`runId`)",
                )
            }
        }

        /**
         * v51 → v52: adds the `onboarding_milestone` table holding the write-once
         * markers of the install → first-value path (onboarding opened, scenario
         * chosen, model download started/finished, first value), which turn the
         * "< 10 minutes to first value" metric into a repeatable measurement
         * instead of a stopwatch reading.
         *
         * Purely additive: no existing table is touched, so upgrading installs
         * simply start with an empty marker set (their journey happened before
         * the markers existed and is therefore not measurable — by design, the
         * metric is taken on a clean install). The marker key is the primary key,
         * which is what backs the `INSERT OR IGNORE` write-once semantics. The
         * table lives in the SQLCipher-encrypted database and nothing it holds
         * ever leaves the device.
         */
        val MIGRATION_51_52 = object : Migration(51, 52) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `onboarding_milestone` (
                        `milestoneKey` TEXT NOT NULL,
                        `atMillis` INTEGER NOT NULL,
                        `detail` TEXT,
                        PRIMARY KEY(`milestoneKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v52 → v53: adds the nullable `memoryRetrievalQuery` column to
         * `pipelines` — the long-term-memory search key a pipeline declares for
         * its background (trigger / scheduled / tile) runs, whose authored
         * prompt is too generic to be a useful semantic key
         * (`DESCRIPTION.md` §6.10.1).
         *
         * Purely additive and nullable: every existing pipeline upgrades to
         * "declares nothing" and keeps the previous behaviour exactly (its
         * background runs fall through to the first memory-aware node's input,
         * then to the run prompt). Nullable rather than `NOT NULL DEFAULT ''`
         * so "never declared" and "declared blank" cannot drift apart.
         */
        val MIGRATION_52_53 = object : Migration(52, 53) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipelines` ADD COLUMN `memoryRetrievalQuery` TEXT")
            }
        }

        /**
         * v53 → v54: adds the `isArchived` column to `chat_sessions` — the
         * chat-archive flag that moves a conversation out of the main thread
         * list without deleting anything it owns.
         *
         * Purely additive: a new column introduced on a new schema version, so
         * `NOT NULL DEFAULT 0` is safe — every pre-existing session upgrades to
         * "not archived" and the thread list keeps showing exactly what it
         * showed before.
         */
        val MIGRATION_53_54 = object : Migration(53, 54) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `chat_sessions` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v54 → v55: adds the nullable `archivedAt` column to `chat_sessions` —
         * the instant the user archived the conversation.
         *
         * The archive surface orders by it and renders it as each row's label
         * ("Archived 2 h ago"). It cannot be folded into `updatedAt`: a
         * background trigger run is allowed to write into an archived chat
         * without un-archiving it (v53 → v54's contract), which bumps
         * `updatedAt` and would silently reshuffle the archive and mislabel the
         * row. Comparing the two is also what surfaces "run finished after
         * archiving" to the user.
         *
         * Nullable rather than `NOT NULL DEFAULT 0`: `null` means "not
         * archived", so the flag and the instant cannot drift into a state
         * where a never-archived row claims an archive instant of the epoch.
         * The archive query `COALESCE`s to `updatedAt`, so the (in practice
         * empty) set of rows archived under v54 still orders sensibly.
         */
        val MIGRATION_54_55 = object : Migration(54, 55) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_sessions` ADD COLUMN `archivedAt` INTEGER")
            }
        }

        /**
         * v55 → v56: adds the human-in-the-loop columns to `trigger_evaluations`
         * — how many approval / clarification gates the fired run raised, which
         * kind the latest one was, how it resolved, and whether it had to park on
         * a durable record first.
         *
         * Until now the journal recorded only the run's terminal outcome, which
         * makes an *answered* background approval indistinguishable from a run
         * that never asked (only the unanswered case surfaced, as
         * `HITL_TIMEOUT`). That is a silent gap on exactly the interaction the
         * user is most likely to miss, and it forced the background-approval
         * criterion of the soak protocol to be argued from operator memory
         * instead of from the journal dump.
         *
         * Purely additive: the counter and the parked flag default to "no gate",
         * so every pre-v56 row reads back as a run that never asked — which is
         * what those rows actually mean, since nothing recorded gates before.
         */
        val MIGRATION_55_56 = object : Migration(55, 56) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `trigger_evaluations` ADD COLUMN `hitlGateCount` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `trigger_evaluations` ADD COLUMN `hitlLastKind` TEXT")
                db.execSQL("ALTER TABLE `trigger_evaluations` ADD COLUMN `hitlLastResolution` TEXT")
                db.execSQL("ALTER TABLE `trigger_evaluations` ADD COLUMN `hitlParked` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v56 → v57: creates `usage_pipeline_day`, the `(day, pipeline)` activity
         * set behind the weekly-retention figures of the local usage statistics.
         *
         * The existing counters are all-time totals carrying no date, so "which
         * pipelines are alive this week" is not derivable from them; this table
         * adds exactly that one missing dimension as a set (composite primary
         * key + `INSERT OR IGNORE`), not as a second run counter.
         *
         * Purely additive, and deliberately **not** back-filled: the historical
         * counters hold no dates to back-fill from, so on an upgraded install the
         * *live-pipelines* figure starts at zero and fills as the app is used.
         * (The day-based figures are unaffected — they read `usage_active_day`,
         * which has been recorded since v47.) Fabricating a history here would be
         * inventing data, which is worse than a week of honest zeros.
         */
        val MIGRATION_56_57 = object : Migration(56, 57) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage_pipeline_day` (" +
                        "`day` TEXT NOT NULL, `pipelineId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`day`, `pipelineId`))",
                )
            }
        }

        /**
         * v57 → v58: adds the `external_automation_requests` journal — one row per
         * request a third-party app sent to the external-automation entry point,
         * admitted or refused.
         *
         * Additive: a fresh table, no existing column touched. Deliberately without
         * a foreign key on `runId`, mirroring `trigger_evaluations`: the record must
         * survive the run it describes (and most rows describe requests that never
         * produced one). Growth is bounded by the retention pass, not by a cascade —
         * which matters more here than anywhere else in the schema, because the
         * write rate at this entry point is set by another app on the device.
         */
        val MIGRATION_57_58 = object : Migration(57, 58) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `external_automation_requests` (
                        `id` TEXT NOT NULL,
                        `requestId` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `action` TEXT NOT NULL,
                        `targetKind` TEXT,
                        `targetValue` TEXT,
                        `declaredReturnPackage` TEXT,
                        `returnAction` TEXT NOT NULL,
                        `attestedSenderPackage` TEXT,
                        `statusKind` TEXT NOT NULL,
                        `statusReason` TEXT,
                        `runId` TEXT,
                        `repeatCount` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_external_automation_requests_receivedAt` " +
                        "ON `external_automation_requests` (`receivedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_external_automation_requests_runId` " +
                        "ON `external_automation_requests` (`runId`)",
                )
            }
        }

        /**
         * v58 → v59: gives `pipeline_runs` the two spend counters and the typed
         * termination reason the autonomous-run ceilings need.
         *
         * Purely additive. The counters default to zero, which is the truth for
         * every pre-v59 row: nothing was counted before, so nothing was spent as
         * far as the ceiling mechanism is concerned, and an in-flight run picked
         * up across an upgrade simply starts its accounting here.
         * `terminationReason` is nullable and stays null for historical rows —
         * "why did this stop" was only ever recorded as prose, and prose is not
         * something a migration should try to parse back into an enum.
         *
         * The `DEFAULT 0` on both counters is load-bearing rather than
         * decorative: the entity declares `@ColumnInfo(defaultValue = "0")`, and
         * Room's `TableInfo` check compares the two sides. Drop it on either
         * side and the schema-validation suite fails — which is instrumented, so
         * `./gradlew check` would not be the thing that told you.
         */
        val MIGRATION_58_59 = object : Migration(58, 59) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `stepsSpent` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `tokensSpent` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `terminationReason` TEXT")
            }
        }

        /**
         * Five per-node settings the editor could already show and the engine
         * could not read: the INTENT_ROUTER fallback class, the CLARIFICATION
         * quick replies, the TOOL always-confirm switch, the DECOMPOSITION
         * sub-task cap and the QUEUE_PROCESSOR stop-on-error switch.
         *
         * Every column is nullable with no default, and `null` means "behave
         * exactly as before" for all five. That is what lets an existing
         * pipeline cross this migration without changing how it runs.
         */
        val MIGRATION_59_60 = object : Migration(59, 60) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `fallbackClass` TEXT")
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `quickReplies` TEXT")
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `alwaysConfirm` INTEGER")
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `maxSubtasks` INTEGER")
                db.execSQL("ALTER TABLE `pipeline_nodes` ADD COLUMN `stopOnError` INTEGER")
            }
        }

        /**
         * The ceiling pause: a run that spends a ceiling now stops to ask
         * instead of ending, and both halves of that exchange need somewhere to
         * live.
         *
         * On `pipeline_runs`, two per-axis grant counters. Two columns rather
         * than one encoded value because the answer buys a portion **of the axis
         * that bound** — a run waved past its step ceiling has not been granted
         * more tokens. `NOT NULL DEFAULT 0` (matching the `@ColumnInfo` on the
         * entity exactly, or `TableInfo` rejects the schema at open): every
         * existing run has been granted nothing, which is a number.
         *
         * On `pending_interactions`, the question itself — which axis, and the
         * two numbers the card has to state. Nullable, because the other two
         * kinds of park have no ceiling to describe, and because every row
         * written before this migration is one of them.
         */
        val MIGRATION_60_61 = object : Migration(60, 61) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pipeline_runs` ADD COLUMN `stepCeilingExtensions` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `pipeline_runs` ADD COLUMN `tokenCeilingExtensions` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `ceilingAxis` TEXT")
                db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `ceilingLimit` INTEGER")
                db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `ceilingSpent` INTEGER")
            }
        }
    }
}
