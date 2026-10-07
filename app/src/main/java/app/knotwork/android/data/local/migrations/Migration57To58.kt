package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
