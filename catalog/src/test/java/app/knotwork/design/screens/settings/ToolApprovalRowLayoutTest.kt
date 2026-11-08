package app.knotwork.design.screens.settings

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.R
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "Approve tool calls" row keeps its control beside the label while the options fit
 * there on one row, and puts the label above the control when they do not.
 *
 * Native graphics, so text has real widths.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w720dp-h1200dp-xhdpi")
class ToolApprovalRowLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val title = ApplicationProvider.getApplicationContext<Context>()
        .getString(R.string.knotwork_settings_restrictions_approve)

    private fun show(width: Dp, fontScale: Float) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            val hints = remember { SettingsHintController { SettingsHint(text = "What this setting does.") } }
            KnotworkTheme {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                    LocalSettingsHints provides hints,
                ) {
                    Box(modifier = Modifier.width(width)) {
                        SettingsAnchor(anchorKey = SettingsRowAnchors.TOOL_APPROVAL_POLICY) {
                            ToolApprovalRow(options = OPTIONS, selectedIndex = 1, onSelect = {})
                        }
                    }
                }
            }
        }
    }

    private fun bounds(text: String): Rect = with(
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot(),
    ) { Rect(left.value, top.value, right.value, bottom.value) }

    @Test
    fun `given room beside the label when laid out then the control sits beside it`() {
        show(width = WIDE, fontScale = 1f)

        val label = bounds(title)
        val first = bounds(OPTIONS.first())
        assertTrue("label $label, first option $first", first.left >= label.right)
        assertTrue("label $label, first option $first", first.top < label.bottom && label.top < first.bottom)
    }

    @Test
    fun `given no room beside the label when laid out then the label goes above the control`() {
        show(width = PHONE_ROW, fontScale = 2f)

        val label = bounds(title)
        val first = bounds(OPTIONS.first())
        assertTrue("label $label, first option $first", first.top >= label.bottom)
    }

    private companion object {
        /** The tool-approval options, with the longest label the app ships in English. */
        val OPTIONS = listOf("All", "Sensitive +", "Never")

        /** A tablet's settings column: room for the label and the three options in one row. */
        val WIDE = 600.dp

        /** About the width the settings list gives a row on a 360 dp phone. */
        val PHONE_ROW = 320.dp
    }
}
