package app.knotwork.android.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.knotwork.android.data.local.crypto.SecretStore
import app.knotwork.android.data.local.settings.AppStateSettingsStore
import app.knotwork.android.data.local.settings.EntryPointSettingsStore
import app.knotwork.android.data.local.settings.PrivacySettingsStore

/**
 * Builds a [SettingsManager] from its section stores over [dataStore], the way Hilt wires it: one
 * instance of each store, shared by the composite. The one place in the test sources that knows the
 * composite's constructor, so splitting another section out of it changes only this function.
 *
 * @param dataStore The preferences DataStore every store reads and writes.
 * @param secretStore The encrypted store backing the secret payloads.
 * @return The composite over freshly built stores.
 */
internal fun testSettingsManager(dataStore: DataStore<Preferences>, secretStore: SecretStore): SettingsManager =
    SettingsManager(
        dataStore = dataStore,
        secretsStore = secretStore,
        appState = AppStateSettingsStore(dataStore),
        privacy = PrivacySettingsStore(dataStore),
        entryPoints = EntryPointSettingsStore(dataStore),
    )
