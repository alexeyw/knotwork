package app.knotwork.design.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
 * Roborazzi baseline for the redesigned settings **hub** (category list + inline
 * Basic controls): Default / Loading / RestartRequired × Light/Dark, plus a
 * font-scale 200% variant.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class SettingsHubSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hub_default_light() = snapshot("default", dark = false) {
        SettingsHubContent(state = SettingsPreview.hubDefault())
    }

    @Test
    fun hub_default_dark() = snapshot("default", dark = true) {
        SettingsHubContent(state = SettingsPreview.hubDefault())
    }

    @Test
    fun hub_loading_light() = snapshot("loading", dark = false) {
        SettingsHubContent(state = SettingsPreview.hubLoading())
    }

    @Test
    fun hub_restart_required_light() = snapshot("restart_required", dark = false) {
        SettingsHubContent(state = SettingsPreview.hubRestart())
    }

    @Test
    fun hub_default_font_scale_2x_light() = snapshot("default_font_scale_2x", dark = false, fontScale = 2f) {
        SettingsHubContent(state = SettingsPreview.hubDefault())
    }

    @Test
    fun hub_search_results_light() = snapshot("search_results", dark = false) {
        SettingsHubContent(state = SettingsPreview.hubSearchResults())
    }

    @Test
    fun hub_search_results_dark() = snapshot("search_results", dark = true) {
        SettingsHubContent(state = SettingsPreview.hubSearchResults())
    }

    @Test
    fun hub_search_empty_light() = snapshot("search_empty", dark = false) {
        SettingsHubContent(state = SettingsPreview.hubSearchEmpty())
    }

    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // The hub's six Basic rows carry hints too, and without a controller
            // in scope none of them draws its glyph — which is how the hub
            // shipped as the one screen where the feature was invisible, and how
            // its baselines certified that as correct.
            val hints = remember { SettingsHintController { anchor -> HUB_HINTS[anchor] } }
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                    LocalSettingsHints provides hints,
                ) { content() }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/settings_hub_${name}_$themeTag.png",
        )
    }

    private companion object {
        /**
         * Hints for the hub's Basic rows. Fixture sentences, not copies of the
         * shipped strings — see `SettingsCategorySnapshotTest.SNAPSHOT_HINTS`
         * for why, and for the same hand-maintained caveat.
         */
        val HUB_HINTS: Map<String, SettingsHint> = mapOf(
            SettingsRowAnchors.SYSTEM_PROMPT_PREFIX to SettingsHint("What the agent is told before every request."),
            SettingsRowAnchors.TOOL_APPROVAL_POLICY to SettingsHint("Which tool calls stop and wait for you."),
            SettingsRowAnchors.BLOCK_DESTRUCTIVE_TOOLS to SettingsHint("Destructive calls are refused outright."),
            SettingsRowAnchors.LOCAL_MODEL_BACKEND to SettingsHint("Which chip runs the on-device model."),
            SettingsRowAnchors.CRASH_REPORTING_ENABLED to SettingsHint("Off, nothing leaves the device."),
        )
    }
}
