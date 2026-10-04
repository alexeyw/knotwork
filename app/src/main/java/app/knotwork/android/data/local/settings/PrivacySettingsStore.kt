package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.repositories.PrivacySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PrivacySettings] over the app's preferences DataStore: the crash-reporting opt-in, on-device usage
 * statistics and run-trace retention. Every one of them is a tunable preference with a recommended
 * default, written back by [writeRecommendedDefaults].
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`).
 *
 * @property dataStore The app's one preferences DataStore.
 */
@Singleton
class PrivacySettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : PrivacySettings {

    private object Keys {
        val CRASH_REPORTING_ENABLED = booleanPreferencesKey("crash_reporting_enabled")
        val USAGE_TELEMETRY_ENABLED = booleanPreferencesKey("usage_telemetry_enabled")
        val TRACE_RETENTION_RUNS_PER_SESSION = intPreferencesKey("trace_retention_runs_per_session")
        val TRACE_RETENTION_MAX_AGE_DAYS = intPreferencesKey("trace_retention_max_age_days")
    }

    override val traceRetentionRunsPerSession: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.TRACE_RETENTION_RUNS_PER_SESSION]
                ?: SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_DEFAULT
        }

    override suspend fun setTraceRetentionRunsPerSession(runs: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.TRACE_RETENTION_RUNS_PER_SESSION] = runs.coerceIn(
                SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_MIN,
                SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_MAX,
            )
        }
    }

    override val traceRetentionMaxAgeDays: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.TRACE_RETENTION_MAX_AGE_DAYS]
                ?: SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_DEFAULT
        }

    override suspend fun setTraceRetentionMaxAgeDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.TRACE_RETENTION_MAX_AGE_DAYS] = days.coerceIn(
                SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_MIN,
                SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_MAX,
            )
        }
    }

    override val crashReportingEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.CRASH_REPORTING_ENABLED]
                ?: SettingsDefaults.CRASH_REPORTING_ENABLED_DEFAULT
        }

    override suspend fun setCrashReportingEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.CRASH_REPORTING_ENABLED] = enabled
        }
    }

    override val usageTelemetryEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.USAGE_TELEMETRY_ENABLED]
                ?: SettingsDefaults.USAGE_TELEMETRY_ENABLED_DEFAULT
        }

    override suspend fun setUsageTelemetryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.USAGE_TELEMETRY_ENABLED] = enabled
        }
    }

    /**
     * Writes this section's recommended defaults into [preferences], as part of the one atomic edit
     * of "Reset all settings". Both consent switches return to their defaults with the rest.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeRecommendedDefaults(preferences: MutablePreferences) {
        preferences[Keys.TRACE_RETENTION_RUNS_PER_SESSION] = SettingsDefaults.TRACE_RETENTION_RUNS_PER_SESSION_DEFAULT
        preferences[Keys.TRACE_RETENTION_MAX_AGE_DAYS] = SettingsDefaults.TRACE_RETENTION_MAX_AGE_DAYS_DEFAULT
        preferences[Keys.CRASH_REPORTING_ENABLED] = SettingsDefaults.CRASH_REPORTING_ENABLED_DEFAULT
        preferences[Keys.USAGE_TELEMETRY_ENABLED] = SettingsDefaults.USAGE_TELEMETRY_ENABLED_DEFAULT
    }
}
