package app.knotwork.design.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
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
 * A tap on any part of the chat's bottom bar belongs to the bar, not to the message
 * scrolled behind it. The status strip sits inside a margin and a run notice takes
 * no touches; before the bar owned its taps, a tap on either reached the list below.
 * A clickable layer stands in for that list.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class ChatHomeBottomBarTapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var tapsBelow = 0

    @Test
    fun `given the status strip when its side margin is tapped then nothing below is hit`() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { tapsBelow++ })
                    Box(Modifier.align(Alignment.BottomCenter).testTag(BAR)) {
                        ChatHomeBottomBar(state = ChatHomePreview.emptyWithCards(), callbacks = noopChatHomeCallbacks())
                    }
                }
            }
        }

        // Left of the strip, inside the bar's horizontal margin, level with the strip.
        composeTestRule.onNodeWithTag(BAR).performTouchInput { click(Offset(x = 4f, y = 20f)) }

        assertEquals("the tap fell through to the layer below", 0, tapsBelow)
    }

    private companion object {
        const val BAR = "bottom-bar"
    }
}
