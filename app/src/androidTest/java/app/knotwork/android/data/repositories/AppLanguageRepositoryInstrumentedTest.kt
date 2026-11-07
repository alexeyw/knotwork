package app.knotwork.android.data.repositories

import android.app.LocaleManager
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * [AppLanguageRepositoryImpl] on a device: the picker's languages come from the
 * `LocaleConfig` the build generated (Robolectric cannot read it), and a pick
 * reaches the process — whose default locale follows it, which is why
 * `$LOCATION` reads the device's languages instead.
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageRepositoryInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun theLanguagesOfferedAreTheBuildsDefaultFirst() {
        // The generated config lists the languages in the order of their resource folders.
        assertEquals(listOf("en", "es", "ru"), AppLanguageRepositoryImpl(context).supportedLanguages())
    }

    @Test
    fun aPickReachesTheSystemAndTheProcessAndIsUndone() {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val repository = AppLanguageRepositoryImpl(context)
        try {
            repository.select("ru")

            assertEquals("ru", localeManager.applicationLocales.toLanguageTags())
            assertEquals("ru", repository.selectedLanguage.value)
            waitFor { Locale.getDefault().language == "ru" }
            assertEquals("ru", Locale.getDefault().language)
        } finally {
            repository.select(null)
        }
        assertEquals(LocaleList.getEmptyLocaleList(), localeManager.applicationLocales)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val POLL_MS = 50L
    }
}
