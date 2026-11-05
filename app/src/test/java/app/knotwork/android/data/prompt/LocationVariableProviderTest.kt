package app.knotwork.android.data.prompt

import android.app.LocaleManager
import android.os.LocaleList
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * `$LOCATION` takes the region from the device's languages — not from the
 * process default, which a language picked for the app alone replaces with a
 * country-less one (`ru`), as measured on API 34.
 */
@RunWith(RobolectricTestRunner::class)
class LocationVariableProviderTest {

    private lateinit var previousDefault: Locale

    @Before
    fun setUp() {
        previousDefault = Locale.getDefault()
        // What the process looks like once the user picked Russian for the app alone.
        Locale.setDefault(Locale.forLanguageTag("ru"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousDefault)
    }

    @Test
    fun `given a country-less app language when resolved then the device's region is used`() = runTest {
        val localeManager = localeManager(systemLocales = "en-US")

        val provider = LocationVariableProvider { deviceRegionLocale(localeManager) }

        assertEquals("US", provider.resolve())
    }

    @Test
    fun `given device languages whose first has no country when resolved then the first with one wins`() {
        val localeManager = localeManager(systemLocales = "eo,de-DE,fr-FR")

        assertEquals("DE", deviceRegionLocale(localeManager).country)
    }

    @Test
    fun `given no device language with a country when resolved then it falls back to the process default`() = runTest {
        val provider = LocationVariableProvider { deviceRegionLocale(localeManager(systemLocales = "eo")) }

        // The process default is `ru` — no country either, so the variable says so.
        assertEquals("unknown", provider.resolve())
    }

    @Test
    fun `given no locale service when resolved then it falls back to the process default`() {
        assertEquals(Locale.getDefault(), deviceRegionLocale(null))
    }

    private fun localeManager(systemLocales: String): LocaleManager = mockk {
        every { this@mockk.systemLocales } returns LocaleList.forLanguageTags(systemLocales)
    }
}
