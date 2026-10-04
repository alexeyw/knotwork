package app.knotwork.android.domain.repositories

/**
 * Domain-level gateway for anonymous crash reporting.
 *
 * The project ships an on-device-first privacy posture, so every method
 * below must be a strict no-op until the user has explicitly opted in
 * through the in-app consent dialog. The opt-in flag lives in
 * [PrivacySettings.crashReportingEnabled]; the production implementation
 * reads that flag and short-circuits the entire surface to a no-op when it
 * is `false`.
 *
 * The interface intentionally has zero Android dependencies. The Firebase
 * Crashlytics integration is confined to the data-layer implementation,
 * keeping the domain layer pure Kotlin and the rest of the codebase
 * testable without the Firebase SDK on the classpath.
 */
interface CrashReportingRepository {

    /**
     * Toggles anonymous crash reporting on or off.
     *
     * Crash reporting is the whole of it: analytics collection is deliberately
     * not part of this contract, and no implementation may quietly widen it.
     *
     * The implementation persists the choice through the Firebase SDK so it
     * survives process death even before the next [SettingsRepository] read.
     *
     * @param enabled `true` to enable Crashlytics collection,
     *                `false` to disable it and prevent any further egress.
     */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Forwards a non-fatal [Throwable] to Crashlytics for aggregation, with
     * optional contextual key/value pairs attached as transient log breadcrumbs
     * (Crashlytics' `log(...)` channel) so they appear in the report's log
     * trail without persisting into the session-wide custom-key namespace.
     *
     * When crash reporting is disabled the call must be a strict no-op —
     * the throwable is neither logged nor buffered for later upload.
     *
     * @param throwable The exception to record.
     * @param extras Optional per-report context (e.g. the call-site message
     *               and tag). For session-global values that should appear on
     *               every subsequent crash, use [setCustomKey] instead.
     */
    suspend fun recordException(throwable: Throwable, extras: Map<String, String> = emptyMap())

    /**
     * Attaches a Crashlytics custom key that will be included with every
     * subsequent fatal / non-fatal crash report from this process until the
     * key is overwritten.
     *
     * When crash reporting is disabled the call must be a strict no-op.
     *
     * @param key The custom-key identifier (Crashlytics-side string).
     * @param value The stringified value attached to the key.
     */
    suspend fun setCustomKey(key: String, value: String)
}
