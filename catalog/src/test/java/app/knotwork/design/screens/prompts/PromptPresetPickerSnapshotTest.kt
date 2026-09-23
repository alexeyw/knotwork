package app.knotwork.design.screens.prompts

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
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the prompt-preset picker.
 *
 * This body already lived in `:catalog` — the application only hosts the
 * `ModalBottomSheet` around it — and it still had no baseline. Worth naming,
 * because it is the failure the inventory that started this task kept making:
 * a component is easy to record as "covered" once it is in the right module,
 * and being in the right module is not coverage.
 *
 * Two states. The populated one carries a current row, a multi-tag row and a
 * row with no tags, because those are the three shapes the row layout has to
 * survive at once.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class PromptPresetPickerSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun prompt_picker_populated_light() = snapshot("populated", dark = false) {
        Sheet(PromptPresetPickerPreview.populated())
    }

    @Test
    fun prompt_picker_populated_dark() = snapshot("populated", dark = true) {
        Sheet(PromptPresetPickerPreview.populated())
    }

    @Test
    fun prompt_picker_empty_light() = snapshot("empty", dark = false) {
        Sheet(PromptPresetPickerPreview.empty())
    }

    @Composable
    private fun Sheet(state: PromptPresetPickerViewState) {
        PromptPresetPickerSheet(
            state = state,
            strings = PromptPresetPickerStrings(),
            callbacks = PromptPresetPickerCallbacks(),
        )
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
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/prompt_picker_${name}_$theme.png",
        )
    }
}
