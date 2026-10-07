package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
