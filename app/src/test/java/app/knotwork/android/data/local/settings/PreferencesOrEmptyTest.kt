package app.knotwork.android.data.local.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `preferencesOrEmpty`: readable preferences pass through, an `IOException` becomes empty
 * preferences, any other failure propagates.
 */
class PreferencesOrEmptyTest {

    private val key = intPreferencesKey("k")
    private val dataStore = mockk<DataStore<Preferences>>()

    @Test
    fun `given readable preferences when read then they pass through unchanged`() = runTest {
        // Given
        every { dataStore.data } returns flowOf(preferencesOf(key to 7))

        // When
        val read = dataStore.preferencesOrEmpty().first()

        // Then
        assertEquals(7, read[key])
    }

    @Test
    fun `given an IOException when read then empty preferences are emitted instead of the failure`() = runTest {
        // Given
        every { dataStore.data } returns flow { throw IOException("corrupt") }

        // When
        val emitted = dataStore.preferencesOrEmpty().toList()

        // Then
        assertEquals(1, emitted.size)
        assertTrue(emitted.single().asMap().isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `given a failure other than IOException when read then it propagates`() = runTest {
        // Given
        every { dataStore.data } returns flow { throw IllegalStateException("not an I/O error") }

        // When / Then
        dataStore.preferencesOrEmpty().first()
    }
}
