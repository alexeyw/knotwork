package app.knotwork.design.screens.settings

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
 * Roborazzi baseline for the README "Settings" hero shot at the canonical
 * 1080 × 2400 resolution. Renders the `default` state — the populated
 * Settings stack a returning user sees.
 *
 * After the test passes, the generated baselines should be copied from
 * `catalog/src/test/snapshots/hero_settings_{light,dark}.png` into
 * `docs/images/hero-settings{,-dark}.png`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xxhdpi")
class HeroSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hero_settings_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    SettingsHubContent(state = SettingsPreview.hubDefault())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_settings_light.png",
        )
    }

    @Test
    fun hero_settings_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    SettingsHubContent(state = SettingsPreview.hubDefault())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_settings_dark.png",
        )
    }
}
