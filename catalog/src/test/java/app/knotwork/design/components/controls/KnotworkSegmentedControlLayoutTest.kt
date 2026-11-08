package app.knotwork.design.components.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.text.TextBreakAssertions
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The segmented control keeps its options in one row while each fits its share of the
 * width, and stacks them when they do not — so a larger font never cuts an option's label.
 *
 * Native graphics, so text has real widths and a long label can fail to fit at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class KnotworkSegmentedControlLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun show(width: Dp?, fontScale: Float) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) {
                    Box(modifier = if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)) {
                        KnotworkSegmentedControl(options = OPTIONS, selectedIndex = 1, onSelect = {})
                    }
                }
            }
        }
    }

    private fun top(label: String): Float =
        composeTestRule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value

    @Test
    fun `given options that fit their share of the width when laid out then they share one row`() {
        show(width = null, fontScale = 1f)

        val tops = OPTIONS.map(::top)
        assertEquals("tops $tops", 1, tops.distinct().size)
    }

    @Test
    fun `given a narrow control at double font when laid out then every option is whole`() {
        show(width = NARROW, fontScale = 2f)

        TextBreakAssertions.assertWhole(composeTestRule, *OPTIONS.toTypedArray())
    }

    @Test
    fun `given a narrow control at double font when laid out then the options stack in order`() {
        show(width = NARROW, fontScale = 2f)

        val tops = OPTIONS.map(::top)
        assertTrue("tops $tops", tops.zipWithNext().all { (above, below) -> below > above })
    }

    private companion object {
        /** The tool-approval options, with the longest label the app ships in English. */
        val OPTIONS = listOf("All", "Sensitive +", "Never")

        /** About the width the settings row leaves the control on a 360 dp phone. */
        val NARROW = 200.dp
    }
}
