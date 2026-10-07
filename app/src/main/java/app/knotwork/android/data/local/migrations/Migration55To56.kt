package app.knotwork.android.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
