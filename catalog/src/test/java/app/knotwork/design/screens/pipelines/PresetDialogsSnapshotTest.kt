package app.knotwork.design.screens.pipelines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * Roborazzi baselines for the save-as-preset form.
 *
 * The dialog had none while it lived in `:app`, and that is exactly how its
 * category chips shipped with selection invisible: Material's `FilterChip` with
 * default colours marks the *chosen* chip by **removing** the outline the others
 * have, so the selected bucket read as the unselected one. It reached a manual
 * device run before anyone saw it.
 *
 * **The body is captured, not the dialog.** A text field inside an `AlertDialog`
 * never reaches idle under Robolectric — `setContent` spins until Espresso
 * raises `AppNotIdleException`. That was re-measured when this dialog moved,
 * against the v2 compose rule and against a clock frozen before `setContent`;
 * neither helps, because it is the host and not the field. `NodeConfigSheet`
 * carries a body for the same class of reason.
 *
 * Both category states are captured. One picture of a chip row proves nothing
 * about selection — the defect above was visible only by comparing a selected
 * chip against an unselected one, which is what these two frames do.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class PresetDialogsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun save_as_preset_light() = snapshot("save_as_preset", dark = false) {
        Body(selectedCategoryId = "OTHER")
    }

    @Test
    fun save_as_preset_dark() = snapshot("save_as_preset", dark = true) {
        Body(selectedCategoryId = "OTHER")
    }

    @Test
    fun save_as_preset_category_chosen_light() = snapshot("save_as_preset_category", dark = false) {
        // A different bucket, so the pair of frames shows selection moving
        // rather than a single chip that might be styled either way.
        Body(selectedCategoryId = "RESEARCH")
    }

    @Composable
    private fun Body(selectedCategoryId: String) {
        SaveAsPresetDialogBody(
            ui = SaveAsPresetPreview.saveAsPreset(),
            name = "Research assistant",
            onNameChange = {},
            description = "Searches, distils, writes the result to a file.",
            onDescriptionChange = {},
            tagsRaw = "research, files",
            onTagsChange = {},
            selectedCategoryId = selectedCategoryId,
            onCategorySelected = {},
            modifier = Modifier.padding(16.dp),
        )
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = 1f),
                LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
            ) {
                KnotworkTheme(darkTheme = dark) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.background(KnotworkTheme.extended.surface1),
                    ) { content() }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/preset_dialog_${name}_$theme.png",
        )
    }
}
