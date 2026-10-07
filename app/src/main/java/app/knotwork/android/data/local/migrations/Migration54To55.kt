package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
