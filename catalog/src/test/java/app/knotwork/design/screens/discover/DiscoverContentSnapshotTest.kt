package app.knotwork.design.screens.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot baseline for the model-discovery list surface
 * (`DiscoverContent`) across its loading / populated / empty / error states in
 * both themes. Reduced-motion is pinned so the pull-to-refresh indicator and
 * any tweens collapse to a deterministic steady state.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class DiscoverContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun discover_loading_light() = snapshot(name = "loading", dark = false) {
        DiscoverContent(state = DiscoverPreview.loading())
    }

    @Test
    fun discover_loading_dark() = snapshot(name = "loading", dark = true) {
        DiscoverContent(state = DiscoverPreview.loading())
    }

    @Test
    fun discover_populated_light() = snapshot(name = "populated", dark = false) {
        DiscoverContent(state = DiscoverPreview.populated())
    }

    @Test
    fun discover_populated_dark() = snapshot(name = "populated", dark = true) {
        DiscoverContent(state = DiscoverPreview.populated())
    }

    @Test
    fun discover_empty_light() = snapshot(name = "empty", dark = false) {
        DiscoverContent(state = DiscoverPreview.empty())
    }

    @Test
    fun discover_empty_dark() = snapshot(name = "empty", dark = true) {
        DiscoverContent(state = DiscoverPreview.empty())
    }

    @Test
    fun discover_error_light() = snapshot(name = "error", dark = false) {
        DiscoverContent(state = DiscoverPreview.error())
    }

    @Test
    fun discover_error_dark() = snapshot(name = "error", dark = true) {
        DiscoverContent(state = DiscoverPreview.error())
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/discover_${name}_$themeTag.png",
        )
    }
}
