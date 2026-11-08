package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.design.components.dialogs.typedConfirmMatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The clear-memory dialog tells the user the word its gate takes. The word is a
 * resource in the interface language, and the hint once named the English word
 * whatever the language — so a Russian dialog asked for "yes" and accepted only
 * the Russian word.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewStateBuildersTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun clearMemoryDialog() = checkNotNull(
        destructiveActionFor(
            SettingsUiState(pendingDestructive = PendingDestructiveAction.ClearMemory),
            context,
            PendingDestructiveAction.ClearMemory,
        ),
    )

    @Test
    fun `given the app in English when the clear-memory dialog is built then its hint reads as before`() {
        val dialog = clearMemoryDialog()

        assertEquals("Type yes to confirm", dialog.hint)
        assertEquals("yes", dialog.keyword)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when the clear-memory dialog is built then the hint names the word the gate takes`() {
        val dialog = clearMemoryDialog()

        assertTrue("hint must name ${dialog.keyword}: ${dialog.hint}", dialog.hint.contains(dialog.keyword))
        assertTrue(typedConfirmMatches(input = dialog.keyword, keyword = dialog.keyword))
        assertEquals("да", dialog.keyword)
    }
}
