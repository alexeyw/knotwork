package app.knotwork.design.components.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

/**
 * [FullHash] keeps every group of eight whole: when four groups do not fit on a
 * line, fewer go on it, and no group breaks across lines character by character.
 *
 * Measured, not pictured: the hash's height must be exactly the number of lines its
 * whole groups need at the width given, in heights of one line of the same text.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
// Real text metrics: without native graphics Robolectric measures a group of eight
// characters 8 px wide, and nothing would ever fail to fit.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullHashLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sha256 = "e8da759015ee12083a6b4f0c9d2e7a15b3c8f4d6a0e9b1c7d5f2a8e4b6c0d3f1"

    @Test
    fun `given a width four groups do not fit when the full hash is drawn then each group stays on one line`() {
        var hashHeight = 0
        var lineHeight = 0
        var groupWidth = 0
        var gap = 0
        composeTestRule.setContent {
            KnotworkTheme {
                gap = with(LocalDensity.current) { KnotworkTheme.spacing.sp2.roundToPx() }
                Box(Modifier.width(NARROW_WIDTH)) {
                    FullHash(
                        sha256 = sha256,
                        what = "Run digest",
                        color = Color.Black,
                        modifier = Modifier.onGloballyPositioned { hashHeight = it.size.height },
                    )
                }
                // One group alone, in the same style: the unit the hash is measured in.
                Text(
                    text = sha256.take(GROUP),
                    style = KnotworkTextStyles.MonoSm,
                    softWrap = false,
                    modifier = Modifier.onGloballyPositioned {
                        lineHeight = it.size.height
                        groupWidth = it.size.width
                    },
                )
            }
        }
        composeTestRule.waitForIdle()

        val widthPx = with(composeTestRule.density) { NARROW_WIDTH.roundToPx() }
        val groupsPerLine = minOf(MAX_PER_LINE, (widthPx + gap) / (groupWidth + gap))
        val lines = ceil(GROUPS.toDouble() / groupsPerLine).toInt()
        assertEquals(
            "a hash of $GROUPS groups at $groupsPerLine/line takes $lines lines of $lineHeight px",
            lines * lineHeight,
            hashHeight,
        )
    }

    private companion object {
        /** Narrow enough that the four groups of the design's line do not fit. */
        val NARROW_WIDTH = 200.dp

        /** Characters in a group. */
        const val GROUP = 8

        /** Groups in a SHA-256. */
        const val GROUPS = 8

        /** The design's most groups on a line at normal font scale. */
        const val MAX_PER_LINE = 4
    }
}
