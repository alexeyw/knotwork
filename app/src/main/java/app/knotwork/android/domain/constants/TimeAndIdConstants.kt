package app.knotwork.android.domain.constants

/**
 * Cross-module numeric constants for time-unit conversion.
 *
 * Grouped together because each value is a "shared math constant" referenced by
 * multiple unrelated callers (metrics, UI countdown formatting, memory
 * compaction). Centralising them prevents the same literal from drifting across
 * files. Notification ids, once partitioned here too, are decided by
 * [NotificationIds].
 */
object TimeAndIdConstants {
    /** Number of milliseconds in one second. */
    const val MS_PER_SECOND: Long = 1_000L

    /** Number of milliseconds in one minute. */
    const val MS_PER_MINUTE: Long = 60_000L

    /**
     * Number of milliseconds in one day. Used by the long-term memory
     * compaction worker to translate a "consolidate chunks older than N days"
     * age window into an absolute timestamp cutoff.
     */
    const val MS_PER_DAY: Long = 86_400_000L
}
