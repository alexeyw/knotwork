package app.knotwork.design.screens.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * Roborazzi snapshot baseline for the model-discovery detail surface
 * (`DiscoverDetailContent`): loading, loaded (mixed file states), gated
 * (token field), the license-confirmation dialog and the error state, in both
 * themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class DiscoverDetailContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun discover_detail_loading_light() = snapshot(name = "loading", dark = false) {
        DiscoverDetailContent(state = DiscoverPreview.detailLoading())
    }

    @Test
    fun discover_detail_loading_dark() = snapshot(name = "loading", dark = true) {
        DiscoverDetailContent(state = DiscoverPreview.detailLoading())
    }

    @Test
    fun discover_detail_loaded_light() = snapshot(name = "loaded", dark = false) {
        DiscoverDetailContent(state = DiscoverPreview.detailLoaded())
    }

    @Test
    fun discover_detail_loaded_dark() = snapshot(name = "loaded", dark = true) {
        DiscoverDetailContent(state = DiscoverPreview.detailLoaded())
    }

    @Test
    fun discover_detail_gated_light() = snapshot(name = "gated", dark = false) {
        DiscoverDetailContent(state = DiscoverPreview.detailGated())
    }

    @Test
    fun discover_detail_gated_dark() = snapshot(name = "gated", dark = true) {
        DiscoverDetailContent(state = DiscoverPreview.detailGated())
    }

    @Test
    fun discover_detail_license_dialog_light() = snapshot(name = "license_dialog", dark = false) {
        DiscoverDetailContent(state = DiscoverPreview.detailLicenseDialog())
    }

    @Test
    fun discover_detail_license_dialog_dark() = snapshot(name = "license_dialog", dark = true) {
        DiscoverDetailContent(state = DiscoverPreview.detailLicenseDialog())
    }

    @Test
    fun discover_detail_error_light() = snapshot(name = "error", dark = false) {
        DiscoverDetailContent(state = DiscoverPreview.detailError())
    }

    @Test
    fun discover_detail_error_dark() = snapshot(name = "error", dark = true) {
        DiscoverDetailContent(state = DiscoverPreview.detailError())
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
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/discover_detail_${name}_$themeTag.png",
        )
    }
}
