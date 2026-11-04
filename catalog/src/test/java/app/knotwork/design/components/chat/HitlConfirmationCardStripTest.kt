package app.knotwork.design.components.chat

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The approval card's risk strip runs along its start edge — the left in a left-to-right
 * layout, the right in a right-to-left one. The strip is drawn behind the card rather than
 * laid out as its first child, so the side is the drawing's to choose; the screenshot
 * baselines are all left-to-right and would not see it on the wrong side.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class HitlConfirmationCardStripTest {

    @get:Rule
    val rule = createComposeRule()

    /** The colour at [x] from the card's left edge, halfway down. */
    private fun colourAt(direction: LayoutDirection, x: (width: Int) -> Int): Pair<Color, Color> {
        var strip = Color.Unspecified
        rule.setContent {
            KnotworkTheme {
                strip = KnotworkTheme.extended.riskSensitive
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    HitlConfirmationCard(
                        model = HitlConfirmationModel(
                            risk = Risk.Sensitive,
                            toolName = "write_file",
                            summary = "",
                            arguments = mapOf("path" to "\"inbox/captures.md\""),
                            timestamp = "15:12",
                        ),
                        pendingTypedConfirm = "",
                        onTypedConfirmChange = {},
                        allowOnceEnabled = true,
                        onAllowOnce = {},
                        onAllowAlways = null,
                        onReject = {},
                        modifier = Modifier.width(320.dp).testTag(CARD),
                    )
                }
            }
        }
        val pixels = rule.onNodeWithTag(CARD).captureToImage().toPixelMap()
        return pixels[x(pixels.width), pixels.height / 2] to strip
    }

    @Test
    fun `given a left-to-right layout when drawn then the strip runs along the left edge`() {
        val (left, strip) = colourAt(LayoutDirection.Ltr) { STRIP_INSIDE_BORDER_PX }
        assertEquals(strip, left)
    }

    @Test
    fun `given a left-to-right layout when drawn then the right edge has no strip`() {
        val (right, strip) = colourAt(LayoutDirection.Ltr) { it - 1 - STRIP_INSIDE_BORDER_PX }
        assertNotEquals(strip, right)
    }

    @Test
    fun `given a right-to-left layout when drawn then the strip runs along the right edge`() {
        val (right, strip) = colourAt(LayoutDirection.Rtl) { it - 1 - STRIP_INSIDE_BORDER_PX }
        assertEquals(strip, right)
    }

    @Test
    fun `given a right-to-left layout when drawn then the left edge has no strip`() {
        val (left, strip) = colourAt(LayoutDirection.Rtl) { STRIP_INSIDE_BORDER_PX }
        assertNotEquals(strip, left)
    }

    private companion object {
        const val CARD = "card"

        /**
         * A pixel inside the 1 dp border and inside the 2 dp strip: at xhdpi the border covers
         * pixels 0–1 from the edge and the strip 0–3.
         */
        const val STRIP_INSIDE_BORDER_PX = 3
    }
}
