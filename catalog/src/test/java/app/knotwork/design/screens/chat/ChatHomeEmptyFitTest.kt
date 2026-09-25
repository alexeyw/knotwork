package app.knotwork.design.screens.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A new chat's suggestion cards must all be reachable, however little height the
 * screen leaves them.
 *
 * The empty body stacks a brand tile, a title, a caption and up to six cards, and
 * shares the screen with the top bar, the status strip and the composer (in the app
 * also the bottom navigation and, when open, the keyboard). It used to be a column
 * that did not scroll: past the available height its spacers collapsed and the last
 * cards were cut off or measured to nothing. The snapshots never showed it — they
 * rendered the older chip row on a tall canvas.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatHomeEmptyFitTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h560dp-xhdpi")
    fun `given a short screen when the new chat shows six cards then the last one scrolls into view`() {
        render()

        composeTestRule.onNodeWithText(LAST_CARD_TITLE).performScrollTo().assertIsDisplayed()
        assertTrue(lastCardHeightPx() > 0)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi", fontScale = 2f)
    fun `given the largest font when the new chat shows six cards then the last one scrolls into view`() {
        render()

        composeTestRule.onNodeWithText(LAST_CARD_TITLE).performScrollTo().assertIsDisplayed()
        assertTrue(lastCardHeightPx() > 0)
    }

    private fun render() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) { ChatHomeContent(state = ChatHomePreview.emptyWithCards()) }
        }
    }

    private fun lastCardHeightPx(): Float =
        composeTestRule.onNodeWithText(LAST_CARD_TITLE).fetchSemanticsNode().boundsInRoot.height

    private companion object {
        val LAST_CARD_TITLE = ChatHomePreview.emptyWithCards().samplePromptCards.last().title
    }
}
