package app.knotwork.android.data.local.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.knotwork.android.data.local.crypto.FakeAeadCipher
import app.knotwork.android.data.local.crypto.InMemorySharedPreferences
import app.knotwork.android.data.local.crypto.KeystoreBackedPrefsStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The tool switches of [ToolSettingsStore] — `setMcpToolsEnabled` and `setAppFunctionsEnabled` — on
 * a real file-backed DataStore.
 *
 * Each call changes only the ids it names, and applies that change to the stored set inside one
 * DataStore transaction. That is what keeps switches flipped in quick succession — a whole server's
 * tools turned off, then one turned back on — from erasing one another: a caller that read the set,
 * changed its copy and wrote it back lost every write that landed in between.
 */
class ToolSettingsStoreToolSwitchesTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE) } returns InMemorySharedPreferences()
    }
    private val secretStore = KeystoreBackedPrefsStore(
        context = context,
        prefsName = SECRETS_PREFS,
        keyAlias = "knotwork.settings_secrets",
        cipher = FakeAeadCipher(),
    )

    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = dataStoreScope,
        produceFile = { tempFolder.root.resolve("tool-switches.preferences_pb") },
    )

    /** A DataStore whose every write fails, so a test can tell whether a call wrote at all. */
    private val unwritableDataStore = object : DataStore<Preferences> by dataStore {
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("write attempted")
    }

    private val store = ToolSettingsStore(dataStore, secretStore)

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `given disabled MCP tools when more are switched off then they join the set and the rest stay`() = runTest {
        // Given
        store.setMcpToolsEnabled(toolIds = setOf(OTHER_SERVER_TOOL), enabled = false)

        // When
        store.setMcpToolsEnabled(toolIds = setOf(TOOL_A, TOOL_B), enabled = false)

        // Then
        assertEquals(setOf(OTHER_SERVER_TOOL, TOOL_A, TOOL_B), store.disabledMcpTools.first())
    }

    @Test
    fun `given disabled MCP tools when some are switched on then only those leave the set`() = runTest {
        // Given
        store.setMcpToolsEnabled(toolIds = setOf(OTHER_SERVER_TOOL, TOOL_A, TOOL_B), enabled = false)

        // When
        store.setMcpToolsEnabled(toolIds = setOf(TOOL_A, TOOL_B, NEVER_DISABLED), enabled = true)

        // Then
        assertEquals(setOf(OTHER_SERVER_TOOL), store.disabledMcpTools.first())
    }

    @Test
    fun `given many MCP switches flipped at once when they all finish then every one of them is kept`() = runTest {
        // When
        val ids = (1..SWITCHES).map { "mcp:cafebabe:tool_$it" }
        withContext(Dispatchers.Default) {
            ids.map { id -> async { store.setMcpToolsEnabled(toolIds = setOf(id), enabled = false) } }.awaitAll()
        }

        // Then
        assertEquals(ids.toSet(), store.disabledMcpTools.first())
    }

    @Test
    fun `given an empty id set when MCP tools are switched then nothing is written`() = runTest {
        // Given
        val unwritable = ToolSettingsStore(unwritableDataStore, secretStore)

        // When — a write would throw
        unwritable.setMcpToolsEnabled(toolIds = emptySet(), enabled = false)
        unwritable.setMcpToolsEnabled(toolIds = emptySet(), enabled = true)

        // Then
        assertEquals(emptySet<String>(), unwritable.disabledMcpTools.first())
    }

    @Test
    fun `given disabled local tools when one is switched on and another off then each changes alone`() = runTest {
        // Given
        store.setAppFunctionsEnabled(functions = setOf(LOCAL_A, LOCAL_B), enabled = false)

        // When
        store.setAppFunctionsEnabled(functions = setOf(LOCAL_A), enabled = true)
        store.setAppFunctionsEnabled(functions = setOf(LOCAL_C), enabled = false)

        // Then
        assertEquals(setOf(LOCAL_B, LOCAL_C), store.disabledAppFunctions.first())
    }

    @Test
    fun `given many local switches flipped at once when they all finish then every one of them is kept`() = runTest {
        // When
        val names = (1..SWITCHES).map { "com.example/fn_$it" }
        withContext(Dispatchers.Default) {
            names.map { name -> async { store.setAppFunctionsEnabled(functions = setOf(name), enabled = false) } }
                .awaitAll()
        }

        // Then
        assertEquals(names.toSet(), store.disabledAppFunctions.first())
    }

    @Test
    fun `given an empty name set when local tools are switched then nothing is written`() = runTest {
        // Given
        val unwritable = ToolSettingsStore(unwritableDataStore, secretStore)

        // When — a write would throw
        unwritable.setAppFunctionsEnabled(functions = emptySet(), enabled = false)

        // Then
        assertEquals(emptySet<String>(), unwritable.disabledAppFunctions.first())
    }

    private companion object {
        const val SECRETS_PREFS = "secure_settings_secrets"
        const val TOOL_A = "mcp:cafebabe:search"
        const val TOOL_B = "mcp:cafebabe:fetch"
        const val OTHER_SERVER_TOOL = "mcp:deadbeef:search"
        const val NEVER_DISABLED = "mcp:cafebabe:list"
        const val LOCAL_A = "com.example/a"
        const val LOCAL_B = "com.example/b"
        const val LOCAL_C = "com.example/c"

        /** Enough concurrent writers that a read-modify-write outside the transaction loses some. */
        const val SWITCHES = 50
    }
}
