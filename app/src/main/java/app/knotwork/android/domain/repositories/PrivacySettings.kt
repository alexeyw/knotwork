package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.Flow

/**
 * What the app records about itself: the crash-reporting opt-in, on-device usage statistics, and
 * how long run traces are kept.
 *
 * One of the sections [SettingsRepository] is made of.
 */
interface PrivacySettings {

    /**
     * A [Flow] representing how many most-recent pipeline runs the retention
     * pass preserves per chat session. Terminal runs beyond this count are
     * deleted (together with their persisted traces) during the daily
     * maintenance window; non-terminal runs — including runs parked on a
     * background approval or clarification — are never removed by retention.
     * Valid range: 5–100.
     */
    val traceRetentionRunsPerSession: Flow<Int>

    /**
     * Updates the per-session run-retention count.
     *
     * @param runs The new count. Callers should keep it within the range
     *   5–100 (validation of user-entered values lives in the Settings
     *   ViewModel).
     */
    suspend fun setTraceRetentionRunsPerSession(runs: Int)

    /**
     * A [Flow] representing the maximum age, in days, a terminal pipeline run
     * (and its trace) is kept before the retention pass deletes it regardless
     * of the per-session count. Valid range: 7–180.
     */
    val traceRetentionMaxAgeDays: Flow<Int>

    /**
     * Updates the max-age run-retention window.
     *
     * @param days The new age limit in days. Callers should keep it within
     *   the range 7–180 (validation of user-entered values lives in the
     *   Settings ViewModel).
     */
    suspend fun setTraceRetentionMaxAgeDays(days: Int)

    /**
     * A [Flow] indicating whether the user has opted in to anonymous crash
     * reporting via Firebase Crashlytics. Defaults to `false` — the project's
     * on-device privacy positioning forbids any automatic data egress.
     *
     * When `false`, the implementation must short-circuit every
     * `CrashReportingRepository` call to a no-op so no payload ever leaves
     * the device. When `true`, Firebase Crashlytics collection is enabled
     * and exceptions / custom keys are forwarded.
     */
    val crashReportingEnabled: Flow<Boolean>

    /**
     * Updates the user's opt-in for anonymous crash reporting.
     *
     * @param enabled `true` to enable Crashlytics collection,
     *                `false` to disable and force all reporting calls into a no-op.
     */
    suspend fun setCrashReportingEnabled(enabled: Boolean)

    /**
     * A [Flow] indicating whether fully on-device usage statistics are recorded.
     *
     * Gates the privacy-preserving local telemetry feature: when `true`, terminal
     * run outcomes and background trigger firings advance local counters
     * ([app.knotwork.android.domain.repositories.UsageTelemetryRepository]); when
     * `false`, no counter advances. **Nothing on this path ever leaves the
     * device regardless of the flag** — the toggle only controls whether the
     * local figures are gathered at all. Defaults to `true`.
     */
    val usageTelemetryEnabled: Flow<Boolean>

    /**
     * Updates the opt-in for on-device usage statistics recording.
     *
     * @param enabled `true` to record local usage counters, `false` to stop
     *   recording (already-stored statistics are untouched — use the Usage
     *   statistics screen's reset action to clear them).
     */
    suspend fun setUsageTelemetryEnabled(enabled: Boolean)
}
