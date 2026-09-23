package app.knotwork.design.screens.pipelines

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
 * Roborazzi baselines for the preset picker.
 *
 * Three states, and the third is the one worth the picture: a selection the
 * current filter has hidden. It is reachable in one tap — pick a preset, then
 * switch category — and the confirm CTA has to go disabled, because confirming
 * would otherwise instantiate a preset the user can no longer see.
 *
 * The body rather than the sheet: a `ModalBottomSheet` does not lay out under
 * Robolectric, which is why the host keeps the wrapper and the catalog keeps
 * everything else.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class PresetPickerSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun preset_picker_populated_light() = snapshot("populated", dark = false) {
        Body(PresetPickerPreview.populated())
    }

    @Test
    fun preset_picker_populated_dark() = snapshot("populated", dark = true) {
        Body(PresetPickerPreview.populated())
    }

    @Test
    fun preset_picker_empty_light() = snapshot("empty", dark = false) {
        Body(PresetPickerPreview.empty())
    }

    @Test
    fun preset_picker_selection_filtered_away_light() = snapshot("selection_filtered_away", dark = false) {
        Body(PresetPickerPreview.selectionFilteredAway())
    }

    @Composable
    private fun Body(state: PresetPickerViewState) {
        PresetPickerSheetBody(
            state = state,
            onTabSelected = {},
            onCategorySelected = {},
            onRowSelected = {},
            onUsePreset = {},
            onDismiss = {},
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
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/preset_picker_${name}_$theme.png",
        )
    }
}
