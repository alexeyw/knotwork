package app.knotwork.design.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the run-limits screen.
 *
 * Both themes for every state, plus **font scale 200 %** — not optional here.
 * The delivered mockups clipped two rows at 200 %: a trailing qualifier chip
 * sharing a line with a title that is free to wrap has nowhere to go, and the
 * chip loses. `LimitSliderRow` and `StatementRow` therefore lay those out in a
 * `FlowRow`, and the 200 % captures below are what keeps that true — recording
 * them from a layout that reproduced the mockup verbatim would have frozen the
 * defect instead of catching it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class RunLimitsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun run_limits_default_light() = snapshot("default", dark = false) {
        RunLimitsContent(state = SettingsPreview.runLimits(), callbacks = RunLimitsCallbacks())
    }

    @Test
    fun run_limits_default_dark() = snapshot("default", dark = true) {
        RunLimitsContent(state = SettingsPreview.runLimits(), callbacks = RunLimitsCallbacks())
    }

    @Test
    fun run_limits_raised_light() = snapshot("raised", dark = false) {
        RunLimitsContent(state = SettingsPreview.runLimitsRaised(), callbacks = RunLimitsCallbacks())
    }

    @Test
    fun run_limits_raised_dark() = snapshot("raised", dark = true) {
        RunLimitsContent(state = SettingsPreview.runLimitsRaised(), callbacks = RunLimitsCallbacks())
    }

    @Test
    fun run_limits_background_set_light() = snapshot("background_set", dark = false) {
        RunLimitsContent(state = SettingsPreview.runLimitsBackgroundSet(), callbacks = RunLimitsCallbacks())
    }

    @Test
    fun run_limits_background_set_dark() = snapshot("background_set", dark = true) {
        RunLimitsContent(state = SettingsPreview.runLimitsBackgroundSet(), callbacks = RunLimitsCallbacks())
    }

    /**
     * The inherited row at 200 %: the label wraps to three lines and the "Same
     * as above" qualifier has to find its own. This is the capture that would
     * have caught the clipped chip.
     */
    @Test
    fun run_limits_font_scale_200_light() = snapshot("fontscale200", dark = false, fontScale = FONT_SCALE_200) {
        RunLimitsContent(state = SettingsPreview.runLimits(), callbacks = RunLimitsCallbacks())
    }

    @Test
    fun run_limits_font_scale_200_dark() = snapshot("fontscale200", dark = true, fontScale = FONT_SCALE_200) {
        RunLimitsContent(state = SettingsPreview.runLimits(), callbacks = RunLimitsCallbacks())
    }

    /**
     * The bottom of the screen, which does not fit the 720 x 1520 capture.
     *
     * Taken on a taller device rather than by scrolling, because the states that
     * matter down here are static: the spend statement — the one axis the
     * product declines to measure and therefore has to explain — and the note
     * about the fixed 75 % warning point. Without this capture neither has a
     * baseline at all.
     */
    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun run_limits_spend_light() = snapshot("spend", dark = false) {
        RunLimitsContent(state = SettingsPreview.runLimits(), callbacks = RunLimitsCallbacks())
    }

    @Test
    @Config(sdk = [36], qualifiers = "w360dp-h1400dp-xhdpi")
    fun run_limits_spend_dark() = snapshot("spend", dark = true) {
        RunLimitsContent(state = SettingsPreview.runLimits(), callbacks = RunLimitsCallbacks())
    }

    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) { content() }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/run_limits_${name}_$themeTag.png",
        )
    }

    private companion object {
        const val FONT_SCALE_200: Float = 2f
    }
}
