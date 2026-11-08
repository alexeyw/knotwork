package app.knotwork.android.data.repositories

import android.app.LocaleManager
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.knotwork.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val languages = AppLanguageRepositoryImpl(context).supportedLanguages()

        assertEquals(AppLanguageRepositoryImpl.DEFAULT_LANGUAGE, languages.first())
        // Only the first place is ours: `LocaleConfig` gathers the generated file's names into a
        // `HashSet`, so the rest come in that set's order, not the file's.
        assertEquals(listOf("en", "es", "ru"), languages.sorted())
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

    /**
     * What a ViewModel keeping resolved text relies on: read at the moment
     * `interfaceLanguage` changes, the application context already answers in the new
     * language. Read when `selectedLanguage` changes, it would still answer in the old one.
     */
    @Test
    fun whenTheInterfaceLanguageChangesTheApplicationContextAlreadyAnswersInIt() = runBlocking {
        val repository = AppLanguageRepositoryImpl(context)
        val resolved = CompletableDeferred<String>()
        // Unconfined: the read runs inside the emission, on the thread that made it.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch {
            repository.interfaceLanguage.drop(1).collect {
                resolved.complete(context.getString(R.string.interface_language_tag))
            }
        }
        try {
            repository.select("es")

            assertEquals("es", withTimeout(WAIT_MS) { resolved.await() })
        } finally {
            scope.cancel()
            repository.select(null)
        }
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
