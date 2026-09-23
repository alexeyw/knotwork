package app.knotwork.design.screens.pipelines

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
 * Roborazzi snapshot baseline for `PipelineLibraryContent` across the 7
 * documented states (`compose/screens/README.md §C3 · Pipeline library`)
 * in both themes.
 *
 * Reduced-motion is pinned via [FixedKnotworkA11y] so the chip-row tween
 * collapses to its steady-state per `decisions.md §14`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class PipelineLibraryContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun library_empty_light() = snapshot(name = "empty", dark = false) {
        PipelineLibraryContent(state = PipelineLibraryPreview.empty())
    }

    @Test
    fun library_empty_dark() = snapshot(name = "empty", dark = true) {
        PipelineLibraryContent(state = PipelineLibraryPreview.empty())
    }

    @Test
    fun library_loading_light() = snapshot(name = "loading", dark = false) {
        PipelineLibraryContent(state = PipelineLibraryPreview.loading())
    }

    @Test
    fun library_loading_dark() = snapshot(name = "loading", dark = true) {
        PipelineLibraryContent(state = PipelineLibraryPreview.loading())
    }

    @Test
    fun library_populated_light() = snapshot(name = "populated", dark = false) {
        PipelineLibraryContent(state = PipelineLibraryPreview.populated())
    }

    @Test
    fun library_populated_dark() = snapshot(name = "populated", dark = true) {
        PipelineLibraryContent(state = PipelineLibraryPreview.populated())
    }

    @Test
    fun library_swipe_open_light() = snapshot(name = "swipe_open", dark = false) {
        PipelineLibraryContent(state = PipelineLibraryPreview.swipeOpen())
    }

    @Test
    fun library_swipe_open_dark() = snapshot(name = "swipe_open", dark = true) {
        PipelineLibraryContent(state = PipelineLibraryPreview.swipeOpen())
    }

    @Test
    fun library_multi_select_light() = snapshot(name = "multi_select", dark = false) {
        PipelineLibraryContent(state = PipelineLibraryPreview.multiSelect())
    }

    @Test
    fun library_multi_select_dark() = snapshot(name = "multi_select", dark = true) {
        PipelineLibraryContent(state = PipelineLibraryPreview.multiSelect())
    }

    @Test
    fun library_error_light() = snapshot(name = "error", dark = false) {
        PipelineLibraryContent(state = PipelineLibraryPreview.error())
    }

    @Test
    fun library_error_dark() = snapshot(name = "error", dark = true) {
        PipelineLibraryContent(state = PipelineLibraryPreview.error())
    }

    /**
     * Wraps the content under the standard test rule, pins reduced-motion so
     * looping animations don't randomise the snapshot, and writes the PNG to
     * `src/test/snapshots/pipeline_library_<name>_<theme>.png`.
     */
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
            filePath = "src/test/snapshots/pipeline_library_${name}_$themeTag.png",
        )
    }
}
