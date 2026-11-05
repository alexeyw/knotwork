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
    fun `a language is named in itself and capitalised as a list item`() {
        assertEquals("English", AppLanguageLabels.autonym("en"))
        assertEquals("Русский", AppLanguageLabels.autonym("ru"))
        assertEquals("Español", AppLanguageLabels.autonym("es"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `a language keeps its own name while the app is in another language`() {
        assertEquals("English", AppLanguageLabels.autonym("en"))
    }

    @Test
    fun `the row names the device language while the app follows it`() {
        assertEquals(context.getString(R.string.settings_language_device), AppLanguageLabels.rowValue(null, context))
        assertEquals("Русский", AppLanguageLabels.rowValue("ru", context))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `the device-language row reads in the app's language`() {
        assertEquals("Язык устройства", AppLanguageLabels.rowValue(null, context))
    }
}
