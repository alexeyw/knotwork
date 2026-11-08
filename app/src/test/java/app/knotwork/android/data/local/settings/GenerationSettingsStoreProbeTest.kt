package app.knotwork.android.data.local.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.knotwork.android.data.local.crypto.SecretStore
import app.knotwork.android.domain.models.TestProbeFailure
import app.knotwork.android.domain.models.TestProbeResult
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The last backend probe as [GenerationSettingsStore] keeps it, on a real file-backed DataStore: the
 * typed failure the settings row words survives a write and a read, and a record an earlier release
 * stored — the engine's text only — reads as it did.
 */
class GenerationSettingsStoreProbeTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { tempFolder.root.resolve("generation.preferences_pb") },
    )
    private val store = GenerationSettingsStore(dataStore, mockk<SecretStore>(relaxed = true))

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `given a failure the app words when stored and read then the type comes back`() = runTest {
        val probe = TestProbeResult(
            tokensGenerated = 0,
            durationMs = 12L,
            timestampMs = 1_000L,
            success = false,
            failure = TestProbeFailure.MODEL_FILE_MISSING,
        )

        store.setLastTestProbeResult(probe)

        assertEquals(probe, store.lastTestProbeResult.first())
    }

    @Test
    fun `given the engine's text when stored and read then the text comes back without a type`() = runTest {
        val probe = TestProbeResult(0, 5L, 2_000L, success = false, errorMessage = "no dispatch library")

        store.setLastTestProbeResult(probe)

        assertEquals(probe, store.lastTestProbeResult.first())
    }

    @Test
    fun `given a record an earlier release stored when read then its text is kept and no type is invented`() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("last_test_probe_result")] = LEGACY_RECORD
        }

        val read = store.lastTestProbeResult.first()

        assertEquals("No active model selected.", read?.errorMessage)
        assertEquals(null, read?.failure)
    }

    private companion object {
        /** A failed probe as releases before the typed failure stored it. */
        const val LEGACY_RECORD =
            """{"tokens":0,"durationMs":0,"timestampMs":3000,"success":false,"error":"No active model selected."}"""
    }
}
