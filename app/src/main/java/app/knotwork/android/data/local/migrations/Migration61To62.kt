package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Approval requests get an identity of their own: `requestId` on
 * `pending_interactions`, the token every surface answers with, so an
 * answer settles the request it was shown for rather than whatever its
 * session is waiting on.
 *
 * Nullable, because clarifications and ceiling pauses have no approval
 * request to name. Approval rows parked before this migration are
 * back-filled with their own `runId`: their notifications were posted
 * carrying only the run id, and the receiver answers such a
 * notification with that id — so the back-fill is what keeps a request
 * that was waiting across the update answerable from the notification
 * it already has. A run id is unique per row (primary key), so the
 * back-filled identities are as distinct as minted ones.
 */
val MIGRATION_61_62 = object : Migration(61, 62) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pending_interactions` ADD COLUMN `requestId` TEXT")
        db.execSQL("UPDATE `pending_interactions` SET `requestId` = `runId` WHERE `kind` = 'APPROVAL'")
    }
}
