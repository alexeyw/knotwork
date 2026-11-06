package app.knotwork.android.data.repositories

import android.content.Context
import android.content.res.AssetManager
import app.knotwork.android.domain.presetl10n.PresetTranslations
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * [BundledPresetTranslations]: which file a language reads, that it is read once,
 * and that every way a file can fail to read leaves the presets as written
 * rather than failing the catalogue.
 */
class BundledPresetTranslationsTest {

    private val assets = mockk<AssetManager>()
    private val context = mockk<Context> { every { assets } returns this@BundledPresetTranslationsTest.assets }
    private lateinit var translations: BundledPresetTranslations

    @Before
    fun setUp() {
        translations = BundledPresetTranslations(context).apply { dispatcher = UnconfinedTestDispatcher() }
    }

    @Test
    fun `given the default language when asked then nothing is read and the presets show as written`() = runTest {
        assertSame(PresetTranslations.NONE, translations.forLanguage("en"))
        verify(exactly = 0) { assets.open(any()) }
    }

    @Test
    fun `given a translation file when asked twice then it is read once and parsed`() = runTest {
        every { assets.open("presets/l10n/ru.json") } answers { VALID.byteInputStream() }

        val first = translations.forLanguage("ru")
        val second = translations.forLanguage("ru")

        assertEquals(setOf("qa"), first.pipelines.keys)
        assertSame(first, second)
        verify(exactly = 1) { assets.open("presets/l10n/ru.json") }
    }

    @Test
    fun `given a language with no file when asked then the presets show as written`() = runTest {
        every { assets.open("presets/l10n/de.json") } throws FileNotFoundException("presets/l10n/de.json")

        assertSame(PresetTranslations.NONE, translations.forLanguage("de"))
    }

    @Test
    fun `given a file that cannot be read when asked then the presets show as written`() = runTest {
        every { assets.open("presets/l10n/ru.json") } throws IOException("damaged")

        assertSame(PresetTranslations.NONE, translations.forLanguage("ru"))
    }

    @Test
    fun `given a malformed file when asked then the presets show as written`() = runTest {
        every { assets.open("presets/l10n/ru.json") } answers {
            """{"formatVersion": 1, "language": "ru", "pipelines": {"qa": {"systemPrompt": {}}}}""".byteInputStream()
        }

        assertSame(PresetTranslations.NONE, translations.forLanguage("ru"))
    }

    private companion object {
        const val VALID =
            """{"formatVersion": 1, "language": "ru", "pipelines": {"qa": {"name": {"en": "Q", "ru": "В"}}}}"""
    }
}
