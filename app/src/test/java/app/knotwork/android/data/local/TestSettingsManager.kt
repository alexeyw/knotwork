package app.knotwork.android.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.knotwork.android.data.local.crypto.SecretStore
import app.knotwork.android.data.local.settings.AppStateSettingsStore
import app.knotwork.android.data.local.settings.EntryPointSettingsStore
import app.knotwork.android.data.local.settings.GenerationSettingsStore
import app.knotwork.android.data.local.settings.MemorySettingsStore
import app.knotwork.android.data.local.settings.NetworkSettingsStore
import app.knotwork.android.data.local.settings.PrivacySettingsStore
import app.knotwork.android.data.local.settings.RunSettingsStore
import app.knotwork.android.data.local.settings.SettingsResetStore
import app.knotwork.android.data.local.settings.ToolSettingsStore

/**
 * Builds a [SettingsManager] from its section stores over [dataStore], the way Hilt wires it: one
 * instance of each store, shared by the composite and by the reset store. The one place in the test
 * sources that knows how the composite is put together.
 *
 * @param dataStore The preferences DataStore every store reads and writes.
 * @param secretStore The encrypted store backing the secret payloads.
 * @return The composite over freshly built stores.
 */
internal fun testSettingsManager(dataStore: DataStore<Preferences>, secretStore: SecretStore): SettingsManager {
    val generation = GenerationSettingsStore(dataStore, secretStore)
    val network = NetworkSettingsStore(dataStore)
    val memory = MemorySettingsStore(dataStore)
    val tool = ToolSettingsStore(dataStore, secretStore)
    val run = RunSettingsStore(dataStore)
    val entryPoints = EntryPointSettingsStore(dataStore)
    val privacy = PrivacySettingsStore(dataStore)
    return SettingsManager(
        generation = generation,
        network = network,
        memory = memory,
        tool = tool,
        run = run,
        entryPoints = entryPoints,
        privacy = privacy,
        appState = AppStateSettingsStore(dataStore),
        reset = SettingsResetStore(dataStore, generation, network, run, memory, tool, privacy, entryPoints),
    )
}
