package app.knotwork.android.data.repositories

import android.app.Application
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [AppLanguageRepositoryImpl] over Robolectric's `LocaleManager`: what a pick
 * writes, what the repository reports, and that a change made on the system's
 * settings page reaches it through a configuration change.
 */
@RunWith(RobolectricTestRunner::class)
class AppLanguageRepositoryImplTest {

    private lateinit var application: Application
    private lateinit var localeManager: LocaleManager

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        localeManager = application.getSystemService(LocaleManager::class.java)
        localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
    }

    @After
    fun tearDown() {
        localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
    }

    @Test
    fun `given no pick when created then the app follows the device`() {
        val repository = AppLanguageRepositoryImpl(application)

        assertNull(repository.selectedLanguage.value)
    }

    @Test
    fun `when a language is picked then the system holds it and the repository reports it`() {
        val repository = AppLanguageRepositoryImpl(application)

        repository.select("ru")

        assertEquals("ru", localeManager.applicationLocales.toLanguageTags())
        assertEquals("ru", repository.selectedLanguage.value)
    }

    @Test
    fun `when the device language is picked again then the per-app language is cleared`() {
        val repository = AppLanguageRepositoryImpl(application)
        repository.select("ru")

        repository.select(null)

        assertEquals(0, localeManager.applicationLocales.size())
        assertNull(repository.selectedLanguage.value)
    }

    @Test
    fun `given a pick made on the system page when the configuration changes then the repository reports it`() {
        val repository = AppLanguageRepositoryImpl(application)

        // The system's "App languages" page writes the choice without calling the app…
        localeManager.applicationLocales = LocaleList.forLanguageTags("ru")
        assertNull("nothing told the repository yet", repository.selectedLanguage.value)
        // …and the app hears of it as a configuration change.
        application.onConfigurationChanged(Configuration(application.resources.configuration))

        assertEquals("ru", repository.selectedLanguage.value)
    }

    @Test
    fun `given the config's languages when listed then the default comes first, once`() {
        val repository = AppLanguageRepositoryImpl(application) { LocaleList.forLanguageTags("ru,en,ru") }

        assertEquals(listOf("en", "ru"), repository.supportedLanguages())
    }

    @Test
    fun `given an unreadable config when listed then only the default language is offered`() {
        val repository = AppLanguageRepositoryImpl(application) { null }

        // The language every string exists in; never an empty picker.
        assertEquals(listOf(AppLanguageRepositoryImpl.DEFAULT_LANGUAGE), repository.supportedLanguages())
    }
}
