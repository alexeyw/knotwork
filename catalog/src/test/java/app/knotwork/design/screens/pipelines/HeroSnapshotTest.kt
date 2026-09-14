package app.knotwork.design.screens.pipelines

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
 * Roborazzi baseline for the README "Pipeline library" hero shot at the
 * canonical 1080 × 2400 resolution. Renders the populated state — the
 * surface readers actually land on after running through onboarding.
 *
 * After the test passes, the generated baselines should be copied from
 * `catalog/src/test/snapshots/hero_pipeline_library_{light,dark}.png`
 * into `docs/images/hero-pipeline-library{,-dark}.png`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xxhdpi")
class HeroSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hero_pipeline_library_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    PipelineLibraryContent(state = PipelineLibraryPreview.populated())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_pipeline_library_light.png",
        )
    }

    @Test
    fun hero_pipeline_library_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    PipelineLibraryContent(state = PipelineLibraryPreview.populated())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_pipeline_library_dark.png",
        )
    }
}
