package app.knotwork.design.components.prompt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
 * Roborazzi baselines for the prompt-preview sheet.
 *
 * Three states, and the pairing is the point. The sheet exists to make an
 * **unresolved** placeholder obvious, and "obvious" is a claim about contrast:
 * it only means anything next to a resolved value in the same picture. So the
 * mixed prompt is captured in both themes, with a fully-resolved prompt as the
 * control and the loading state as the third.
 *
 * The body rather than the sheet: a `ModalBottomSheet` does not lay out under
 * Robolectric, which is why `NodeConfigSheet` carries a body too.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class PromptPreviewSheetSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun prompt_preview_mixed_light() = snapshot("mixed", dark = false) {
        PromptPreviewSheetBody(segments = PromptPreviewPreview.mixed(), ui = PromptPreviewPreview.ui())
    }

    @Test
    fun prompt_preview_mixed_dark() = snapshot("mixed", dark = true) {
        PromptPreviewSheetBody(segments = PromptPreviewPreview.mixed(), ui = PromptPreviewPreview.ui())
    }

    @Test
    fun prompt_preview_resolved_light() = snapshot("resolved", dark = false) {
        PromptPreviewSheetBody(segments = PromptPreviewPreview.resolved(), ui = PromptPreviewPreview.ui())
    }

    @Test
    fun prompt_preview_loading_light() = snapshot("loading", dark = false) {
        PromptPreviewSheetBody(segments = null, ui = PromptPreviewPreview.ui())
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = 1f),
                LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
            ) {
                KnotworkTheme(darkTheme = dark) {
                    Box(modifier = Modifier.background(KnotworkTheme.extended.surface1)) { content() }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/prompt_preview_${name}_$theme.png",
        )
    }
}
