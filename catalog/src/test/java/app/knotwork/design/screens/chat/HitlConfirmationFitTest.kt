package app.knotwork.design.screens.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tallest approval card fits on the screen whole.
 *
 * The card shows the request above the call, and a long request on a
 * destructive card at the largest font is the tallest it gets: risk pill,
 * two lines of request, the call, the arguments, the typed confirmation and the
 * buttons. Scrolled to its buttons, its top must still be below the app bar —
 * otherwise the risk and the request the user is meant to check scroll away the
 * moment the decision comes into reach. The two-line collapse of a destructive
 * card from font scale 1.5 is what makes it fit; the snapshots show the card,
 * this measures it inside the chat, at the bubble's real width.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HitlConfirmationFitTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi", fontScale = 2f)
    fun `given the tallest card at the largest font when scrolled to its buttons then its top is still on screen`() {
        val state = ChatHomePreview.hitlConfirm(risk = Risk.Destructive, request = LONG_REQUEST)
        composeTestRule.setContent { KnotworkTheme(darkTheme = false) { ChatHomeContent(state = state) } }

        // Scrolled to the very end, the card sits on the composer: if it is taller
        // than the room between the app bar and the composer, its top is under the
        // app bar. The chat is drawn under both and padded clear of them, so they —
        // not the list — bound what the user can see.
        val chat = composeTestRule.onNode(hasScrollAction())
        chat.performScrollToIndex(state.messages.lastIndex)
        chat.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, Float.MAX_VALUE) }

        val appBar = screenPart(composeTestRule.onNodeWithContentDescription("More chat options").fetchSemanticsNode())
        val composer = screenPart(composeTestRule.onNodeWithContentDescription("Send message").fetchSemanticsNode())
        val pill = composeTestRule.onNodeWithContentDescription("Risk level: Destructive").fetchSemanticsNode()
        val allow = composeTestRule.onNodeWithText("Allow once", useUnmergedTree = true).fetchSemanticsNode()
        // Unclipped geometry: a clipped node reports empty bounds, which would
        // pass any "is inside" check.
        assertTrue("the risk pill has no size", pill.size.height > 0)
        assertTrue("Allow once has no size", allow.size.height > 0)
        assertEquals("the app bar is not at the top of the screen", 0f, appBar.top)
        assertTrue("the composer is not below the app bar", composer.top > appBar.bottom)
        assertTrue(
            "the risk pill (top ${pill.positionInRoot.y}) is under the app bar (${appBar.bottom})",
            pill.positionInRoot.y >= appBar.bottom,
        )
        assertTrue(
            "Allow once (bottom ${allow.positionInRoot.y + allow.size.height}) is under the composer (${composer.top})",
            allow.positionInRoot.y + allow.size.height <= composer.top,
        )
    }

    /**
     * Bounds of the top-level part of the screen [node] belongs to — the app bar,
     * the chat, the composer — found by walking up to the child of the screen's root.
     */
    private fun screenPart(node: SemanticsNode): Rect {
        var part = node
        while (part.parent?.parent?.parent != null) part = part.parent!!
        return part.boundsInRoot
    }

    private companion object {
        /** A request the domain clamped to the card's 400 characters, on a word. */
        const val LONG_REQUEST =
            "Go through the notes from last year's planning sessions and delete the archive file " +
                "we no longer need. Keep anything that mentions the budget review, because finance still asks about " +
                "it, and before you delete anything make sure the summary I wrote in March is saved somewhere else. " +
                "If the archive turns out to hold the only copy of the vendor contacts, stop and ask me first, since…"
    }
}
