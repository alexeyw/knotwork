package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.knotwork.android.domain.repositories.SettingsReset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsReset] over the app's preferences DataStore: both resets, each written as **one** atomic
 * edit in which every section's store writes its own part. A section's store owns its keys and their
 * defaults; this class owns only which parts each reset takes.
 *
 * - [resetSamplingDefaults] — the LLM-parameters card's "Reset to defaults": the sampling parameters,
 *   the run ceilings and the cloud-retry budget.
 * - [resetToRecommendedDefaults] — "Reset all settings": every tunable preference of every section.
 *   Each store's `writeRecommendedDefaults` calls its `writeSamplingDefaults`, so the two resets
 *   cannot drift. What a reset never touches — the user's content, bindings, grants, secrets, and
 *   app state — is decided in each store, and `SettingsManagerTest` checks every declared key is
 *   either reset or deliberately left out.
 *
 * A class-level `@Singleton`, like every settings section (`SettingsSingletonScopeTest`).
 *
 * @property dataStore The app's one preferences DataStore, edited once per reset.
 * @property generation The generation section's store.
 * @property network The network section's store.
 * @property run The run section's store.
 * @property memory The memory section's store.
 * @property tool The tool section's store.
 * @property privacy The privacy section's store.
 * @property entryPoints The entry-point section's store.
 */
@Singleton
class SettingsResetStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val generation: GenerationSettingsStore,
    private val network: NetworkSettingsStore,
    private val run: RunSettingsStore,
    private val memory: MemorySettingsStore,
    private val tool: ToolSettingsStore,
    private val privacy: PrivacySettingsStore,
    private val entryPoints: EntryPointSettingsStore,
) : SettingsReset {

    override suspend fun resetSamplingDefaults() {
        dataStore.edit { preferences ->
            generation.writeSamplingDefaults(preferences)
            run.writeSamplingDefaults(preferences)
            network.writeSamplingDefaults(preferences)
        }
    }

    override suspend fun resetToRecommendedDefaults() {
        dataStore.edit { preferences ->
            generation.writeRecommendedDefaults(preferences)
            network.writeRecommendedDefaults(preferences)
            run.writeRecommendedDefaults(preferences)
            memory.writeRecommendedDefaults(preferences)
            tool.writeRecommendedDefaults(preferences)
            privacy.writeRecommendedDefaults(preferences)
            entryPoints.writeRecommendedDefaults(preferences)
        }
    }
}
