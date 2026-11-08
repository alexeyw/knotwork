package app.knotwork.design.screens.help

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Help list's top bar leaves the screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HelpListContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `given the list when the back arrow is tapped then it raises back`() {
        var backs = 0
        val strings = HelpPreview.strings()
        composeTestRule.setContent {
            KnotworkTheme {
                HelpListContent(
                    state = HelpPreview.list(),
                    strings = strings,
                    callbacks = HelpListCallbacks(onBack = { backs++ }),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(strings.backDescription).performClick()

        composeTestRule.runOnIdle { assertEquals(1, backs) }
    }
}
