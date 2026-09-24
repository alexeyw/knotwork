package app.knotwork.android.data.repositories

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Tests for [ShareAdmissionRepositoryImpl] against a real preferences DataStore on
 * disk — the atomicity the share ceiling rests on is DataStore's, so a mocked store
 * would prove nothing about it.
 */
class ShareAdmissionRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var ledger: ShareAdmissionRepositoryImpl

    @Before
    fun setUp() {
        val file = tempFolder.newFile("share-admissions.preferences_pb")
        // A 0-byte file reads as a corrupt blob; let DataStore create it.
        file.delete()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        ledger = ShareAdmissionRepositoryImpl(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun admit(now: Long, limit: Int = LIMIT): Boolean =
        ledger.admitWithinCeiling(nowMillis = now, windowStartEpochMs = now - WINDOW, limitPerWindow = limit)

    private suspend fun stored(): String? = dataStore.data.first()[KEY]

    @Test
    fun `given the ledger is empty when shares arrive then exactly the limit is admitted`() = runTest {
        val results = (0 until LIMIT + 2).map { admit(NOW + it) }

        assertEquals(List(LIMIT) { true } + listOf(false, false), results)
    }

    @Test
    fun `given a burst of concurrent shares when admitted then no more than the limit get through`() = runTest {
        // The whole point of counting in one edit: fifty starts racing each other
        // must not each read the same pre-burst count.
        val results = withContext(Dispatchers.Default) {
            (0 until 50).map { i -> async { admit(NOW + i, limit = 30) } }.awaitAll()
        }

        assertEquals(30, results.count { it })
    }

    @Test
    fun `given admissions older than the window when a share arrives then they no longer count`() = runTest {
        repeat(LIMIT) { admit(NOW + it) }
        assertFalse(admit(NOW + LIMIT))

        assertTrue(admit(NOW + WINDOW + LIMIT))
    }

    @Test
    fun `given a refused share when refused then it is not recorded`() = runTest {
        repeat(LIMIT) { admit(NOW) }
        admit(NOW)

        assertEquals(LIMIT, stored()!!.split(",").size)
    }

    @Test
    fun `given the ledger when admitting then it keeps only admission times inside the window`() = runTest {
        admit(NOW)
        admit(NOW + WINDOW + 1)

        // The first time left the window with the second admission and was dropped:
        // the store holds numbers, never more than the ceiling can count.
        assertEquals("${NOW + WINDOW + 1}", stored())
    }

    @Test
    fun `given times far ahead of now when a share arrives then they do not lock sharing out`() = runTest {
        // Only a clock set back after these were written puts them a window ahead:
        // they must not count for the length of the jump.
        dataStore.edit { it[KEY] = List(LIMIT) { NOW + 10 * WINDOW }.joinToString(",") }

        assertTrue(admit(NOW))
    }

    @Test
    fun `given times slightly ahead of now when a share arrives then they still count`() = runTest {
        // A concurrent share that read the clock later but reached the store first.
        dataStore.edit { it[KEY] = List(LIMIT) { NOW + 5 }.joinToString(",") }

        assertFalse(admit(NOW))
    }

    @Test
    fun `given an unparseable stored value when a share arrives then it reads as an empty ledger`() = runTest {
        dataStore.edit { it[KEY] = "garbage,,-,${NOW - 1}" }

        assertTrue(admit(NOW))
        assertEquals("${NOW - 1},$NOW", stored())
    }

    @Test
    fun `given the store cannot be written when a share arrives then it is refused`() = runTest {
        val broken = mockk<DataStore<Preferences>>()
        coEvery { broken.updateData(any()) } throws IOException("disk full")

        val admitted = ShareAdmissionRepositoryImpl(broken)
            .admitWithinCeiling(nowMillis = NOW, windowStartEpochMs = NOW - WINDOW, limitPerWindow = LIMIT)

        assertFalse(admitted)
    }

    @Test
    fun `given a non-positive limit when admitting then it is a contract violation`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { admit(NOW, limit = 0) }
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val WINDOW = 3_600_000L
        const val LIMIT = 3
        val KEY = stringPreferencesKey("share_admission_times")
    }
}
