package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Each language is named in itself, whatever language the app is shown in. */
@RunWith(RobolectricTestRunner::class)
class AppLanguageLabelsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `given a language tag when named then it reads in that language, capitalised`() {
        assertEquals("English", AppLanguageLabels.autonym("en"))
        assertEquals("Русский", AppLanguageLabels.autonym("ru"))
        assertEquals("Español", AppLanguageLabels.autonym("es"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when English is named then it stays English`() {
        assertEquals("English", AppLanguageLabels.autonym("en"))
    }

    @Test
    fun `given the app follows the device when the row is built then it names the device language`() {
        assertEquals(context.getString(R.string.settings_language_device), AppLanguageLabels.rowValue(null, context))
        assertEquals("Русский", AppLanguageLabels.rowValue("ru", context))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when the device-language row is built then it reads in Russian`() {
        assertEquals("Язык устройства", AppLanguageLabels.rowValue(null, context))
    }
}
