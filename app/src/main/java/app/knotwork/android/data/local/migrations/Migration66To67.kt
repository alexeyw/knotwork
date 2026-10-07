package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v66 → v67: makes a pipeline run checkable.
 *
 * - `pipeline_runs` gains the run header — the seed and sampler every
 *   on-device call of the run uses, the app and runtime versions and the
 *   device — as seven nullable `header*` columns written together when a
 *   root run starts.
 * - `trace_steps` gains `visit` and the SHA-256 of a node record's input
 *   and output (`inputSha256`, `outputSha256`).
 * - A new `model_calls` table keeps every model call a node makes: an
 *   on-device call's full prompt and output with the sampling, model file
 *   hash, backend and context window that produced it; a cloud call's
 *   provider and model. It cascades with its run and its session like the
 *   rest of the trace.
 *
 * Everything is additive and nullable with no back-fill: a run recorded
 * before this migration has no header, no hashes and no model calls, and
 * reads back as one that cannot be checked.
 */
val MIGRATION_66_67 = object : Migration(66, 67) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `headerSeed` INTEGER")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `headerTemperature` REAL")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `headerTopK` INTEGER")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `headerTopP` REAL")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `headerAppVersion` TEXT")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `headerRuntimeVersion` TEXT")
        db.execSQL("ALTER TABLE `pipeline_runs` ADD COLUMN `headerDevice` TEXT")
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `visit` INTEGER")
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `inputSha256` TEXT")
        db.execSQL("ALTER TABLE `trace_steps` ADD COLUMN `outputSha256` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `model_calls` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` TEXT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`depth` INTEGER NOT NULL, `nodeId` TEXT NOT NULL, `nodeType` TEXT NOT NULL, " +
                "`visit` INTEGER NOT NULL, `callIndex` INTEGER NOT NULL, `engine` TEXT NOT NULL, " +
                "`seed` INTEGER, `temperature` REAL, `topK` INTEGER, `topP` REAL, `modelPath` TEXT, " +
                "`modelSha256` TEXT, `backend` TEXT, `contextWindow` INTEGER, " +
                "`hadImage` INTEGER NOT NULL, `prompt` TEXT, `output` TEXT, `promptSha256` TEXT, " +
                "`outputSha256` TEXT, `cloudProvider` TEXT, `cloudModel` TEXT, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`runId`) REFERENCES `pipeline_runs`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_model_calls_sessionId` ON `model_calls` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_model_calls_runId` ON `model_calls` (`runId`)")
    }
}
