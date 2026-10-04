package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.repositories.RunSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RunSettings] over the app's preferences DataStore: the step and token ceilings (interactive and
 * background), the PIPELINE-node nesting depth, the structured-output repair budget, the resume and
 * background-approval windows, and the scheduled-task result notifications.
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`).
 *
 * @property dataStore The app's one preferences DataStore.
 */
@Singleton
class RunSettingsStore @Inject constructor(private val dataStore: DataStore<Preferences>) : RunSettings {

    private object Keys {
        val PIPELINE_MAX_STEPS = intPreferencesKey("pipeline_max_steps")
        val PIPELINE_MAX_STEPS_BACKGROUND = intPreferencesKey("pipeline_max_steps_background")
        val RUN_MAX_TOKENS = intPreferencesKey("run_max_tokens")
        val RUN_MAX_TOKENS_BACKGROUND = intPreferencesKey("run_max_tokens_background")
        val PIPELINE_MAX_NESTING_DEPTH = intPreferencesKey("pipeline_max_nesting_depth")
        val STRUCTURED_OUTPUT_MAX_REPAIRS = intPreferencesKey("structured_output_max_repairs")
        val RESUME_MAX_AGE_HOURS = intPreferencesKey("resume_max_age_hours")
        val BACKGROUND_APPROVAL_WINDOW_HOURS = intPreferencesKey("background_approval_window_hours")
        val SCHEDULED_TASK_NOTIFICATIONS = booleanPreferencesKey("scheduled_task_notifications")
    }

    override val pipelineMaxSteps: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.PIPELINE_MAX_STEPS] ?: SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT
        }

    override suspend fun setPipelineMaxSteps(steps: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.PIPELINE_MAX_STEPS] = steps.coerceIn(
                SettingsDefaults.PIPELINE_MAX_STEPS_MIN,
                SettingsDefaults.PIPELINE_MAX_STEPS_MAX,
            )
        }
    }

    override val pipelineMaxStepsBackground: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            // Falls back to the *configured* interactive cap, not to a constant.
            // Until this key existed one setting governed every origin, so a user
            // who had raised the cap to 40 would otherwise find their triggers
            // silently dropped to the shipped default on upgrade — the exact
            // capability regression the background default was chosen to avoid.
            // A constant is only reached when the user never set either.
            preferences[Keys.PIPELINE_MAX_STEPS_BACKGROUND]
                ?: preferences[Keys.PIPELINE_MAX_STEPS]
                ?: SettingsDefaults.PIPELINE_MAX_STEPS_BACKGROUND_DEFAULT
        }

    /**
     * True once the background key exists in storage, whatever its value.
     *
     * Deliberately keyed on presence, not on the number: the default background
     * ceiling equals the interactive one, so a user who deliberately sets 15
     * and a user who has never touched it produce the same figure and only the
     * stored key tells them apart.
     */
    override val pipelineMaxStepsBackgroundIsSet: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences -> preferences.contains(Keys.PIPELINE_MAX_STEPS_BACKGROUND) }

    override suspend fun setPipelineMaxStepsBackground(steps: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.PIPELINE_MAX_STEPS_BACKGROUND] = steps.coerceIn(
                SettingsDefaults.PIPELINE_MAX_STEPS_MIN,
                SettingsDefaults.PIPELINE_MAX_STEPS_MAX,
            )
        }
    }

    override val runMaxTokens: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.RUN_MAX_TOKENS] ?: SettingsDefaults.RUN_MAX_TOKENS_DEFAULT
        }

    override suspend fun setRunMaxTokens(tokens: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.RUN_MAX_TOKENS] = tokens.coerceIn(
                SettingsDefaults.RUN_MAX_TOKENS_MIN,
                SettingsDefaults.RUN_MAX_TOKENS_MAX,
            )
        }
    }

    override val runMaxTokensBackground: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.RUN_MAX_TOKENS_BACKGROUND]
                ?: SettingsDefaults.RUN_MAX_TOKENS_BACKGROUND_DEFAULT
        }

    override suspend fun setRunMaxTokensBackground(tokens: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.RUN_MAX_TOKENS_BACKGROUND] = tokens.coerceIn(
                SettingsDefaults.RUN_MAX_TOKENS_MIN,
                SettingsDefaults.RUN_MAX_TOKENS_MAX,
            )
        }
    }

    override val pipelineMaxNestingDepth: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.PIPELINE_MAX_NESTING_DEPTH]
                ?: SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_DEFAULT
        }

    override suspend fun setPipelineMaxNestingDepth(depth: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.PIPELINE_MAX_NESTING_DEPTH] = depth.coerceIn(
                SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MIN,
                SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_MAX,
            )
        }
    }

    override val structuredOutputMaxRepairs: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.STRUCTURED_OUTPUT_MAX_REPAIRS]
                ?: SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_DEFAULT
        }

    override suspend fun setStructuredOutputMaxRepairs(count: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.STRUCTURED_OUTPUT_MAX_REPAIRS] = count.coerceIn(
                SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MIN,
                SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_MAX,
            )
        }
    }

    override val resumeMaxAgeHours: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.RESUME_MAX_AGE_HOURS] ?: SettingsDefaults.RESUME_MAX_AGE_HOURS_DEFAULT
        }

    override suspend fun setResumeMaxAgeHours(hours: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.RESUME_MAX_AGE_HOURS] = hours.coerceIn(
                SettingsDefaults.RESUME_MAX_AGE_HOURS_MIN,
                SettingsDefaults.RESUME_MAX_AGE_HOURS_MAX,
            )
        }
    }

    override val backgroundApprovalWindowHours: Flow<Int> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.BACKGROUND_APPROVAL_WINDOW_HOURS]
                ?: SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_DEFAULT
        }

    override suspend fun setBackgroundApprovalWindowHours(hours: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.BACKGROUND_APPROVAL_WINDOW_HOURS] = hours.coerceIn(
                SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_MIN,
                SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_MAX,
            )
        }
    }

    override val scheduledTaskNotificationsEnabled: Flow<Boolean> = dataStore.preferencesOrEmpty()
        .map { preferences ->
            preferences[Keys.SCHEDULED_TASK_NOTIFICATIONS]
                ?: SettingsDefaults.SCHEDULED_TASK_NOTIFICATIONS_ENABLED_DEFAULT
        }

    override suspend fun setScheduledTaskNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.SCHEDULED_TASK_NOTIFICATIONS] = enabled
        }
    }

    /**
     * Writes the run-ceiling defaults of this section — the step and token ceilings, the nesting
     * depth and the structured-output repair budget — into [preferences]. Part of both resets: the
     * LLM-parameters card's "Reset to defaults" and "Reset all settings", which share it so the two
     * cannot drift.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeSamplingDefaults(preferences: MutablePreferences) {
        preferences[Keys.PIPELINE_MAX_STEPS] = SettingsDefaults.PIPELINE_MAX_STEPS_DEFAULT
        // REMOVED, not written back to its default. Writing the key is exactly
        // what marks the background ceiling as independently chosen, so writing
        // it here would make a reset do the one thing a reset must not: leave
        // the user with a deliberate-looking decision they never made, silently
        // detached from the interactive ceiling it is supposed to follow.
        // Removing it restores the inheritance, which *is* the default state.
        preferences.remove(Keys.PIPELINE_MAX_STEPS_BACKGROUND)
        preferences[Keys.RUN_MAX_TOKENS] = SettingsDefaults.RUN_MAX_TOKENS_DEFAULT
        preferences[Keys.RUN_MAX_TOKENS_BACKGROUND] = SettingsDefaults.RUN_MAX_TOKENS_BACKGROUND_DEFAULT
        preferences[Keys.PIPELINE_MAX_NESTING_DEPTH] = SettingsDefaults.PIPELINE_MAX_NESTING_DEPTH_DEFAULT
        preferences[Keys.STRUCTURED_OUTPUT_MAX_REPAIRS] = SettingsDefaults.STRUCTURED_OUTPUT_MAX_REPAIRS_DEFAULT
    }

    /**
     * Writes every recommended default of this section into [preferences], as part of the one atomic
     * edit of "Reset all settings": the run ceilings, the resume and background-approval windows, and
     * the scheduled-task notifications.
     *
     * @param preferences The preferences being edited by the reset.
     */
    internal fun writeRecommendedDefaults(preferences: MutablePreferences) {
        writeSamplingDefaults(preferences)
        preferences[Keys.RESUME_MAX_AGE_HOURS] = SettingsDefaults.RESUME_MAX_AGE_HOURS_DEFAULT
        preferences[Keys.BACKGROUND_APPROVAL_WINDOW_HOURS] = SettingsDefaults.BACKGROUND_APPROVAL_WINDOW_HOURS_DEFAULT
        preferences[Keys.SCHEDULED_TASK_NOTIFICATIONS] = SettingsDefaults.SCHEDULED_TASK_NOTIFICATIONS_ENABLED_DEFAULT
    }
}
