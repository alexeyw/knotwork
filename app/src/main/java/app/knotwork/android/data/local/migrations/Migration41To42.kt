package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
