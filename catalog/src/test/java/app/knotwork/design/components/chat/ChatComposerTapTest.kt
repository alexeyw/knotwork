package app.knotwork.design.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A tap anywhere on the composer belongs to the composer.
 *
 * The chat lays its message list out under the composer, so a message scrolled behind
 * it sits right below the input. The composer used to be background and padding with
 * nothing that took a touch, and its text field took touches only across the height
 * of one line — a tap on the rest of the pill fell through to whatever was below and,
 * on a link, opened it. Here a clickable layer stands in for that message.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class ChatComposerTapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var tapsBelow = 0

    @Test
    fun `given a link below the composer when the pill is tapped off the text line then the field takes the tap`() {
        render(ComposerState.Idle)

        // Inside the pill, left of the text: its start padding.
        composeTestRule.onNodeWithTag(COMPOSER).performTouchInput { click(Offset(x = 20f, y = centerY)) }

        assertEquals("the tap fell through to the layer below", 0, tapsBelow)
        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `given a link below the composer when the band around the pill is tapped then nothing below is hit`() {
        render(ComposerState.Idle)

        // The band between the composer's edge and the pill.
        composeTestRule.onNodeWithTag(COMPOSER).performTouchInput { click(Offset(x = centerX, y = 2f)) }

        assertEquals("the tap fell through to the layer below", 0, tapsBelow)
    }

    @Test
    fun `given the text field is not shown when the composer is tapped then nothing below is hit and nothing fails`() {
        // While transcribing, an indicator replaces the text field: a tap must not try
        // to focus a field that is not composed.
        render(ComposerState.Transcribing)

        composeTestRule.onNodeWithTag(COMPOSER).performTouchInput { click(Offset(x = centerX, y = centerY)) }

        assertEquals(0, tapsBelow)
    }

    private fun render(state: ComposerState) {
        composeTestRule.setContent {
            var value by remember { mutableStateOf("") }
            KnotworkTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { tapsBelow++ })
                    ChatComposer(
                        value = value,
                        onValueChange = { value = it },
                        onSend = {},
                        onStop = {},
                        state = state,
                        modifier = Modifier.align(Alignment.BottomCenter).testTag(COMPOSER),
                    )
                }
            }
        }
    }

    private companion object {
        const val COMPOSER = "composer"
    }
}
