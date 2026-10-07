package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
