package app.knotwork.design.screens.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
 * Roborazzi baselines for the save-prompt-as-preset form.
 *
 * Three states, and the two refusals are the point. A dialog whose Save button
 * is disabled with nothing to explain why is the shape that makes a user retype
 * the same name twice — so both the blank-prompt refusal and the over-length
 * name say so inline, and both are photographed. The ordinary form is the
 * control that makes those two legible as deviations.
 *
 * The body is captured rather than the dialog: a text field inside an
 * `AlertDialog` never reaches idle under Robolectric. See
 * `SavePromptAsPresetDialogBody`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class SavePromptAsPresetSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun save_prompt_light() = snapshot("save_prompt", dark = false) { Body(name = "Concise answers") }

    @Test
    fun save_prompt_dark() = snapshot("save_prompt", dark = true) { Body(name = "Concise answers") }

    @Test
    fun save_prompt_blank_source_light() = snapshot("save_prompt_blank_source", dark = false) {
        Body(name = "Concise answers", ui = SavePromptAsPresetPreview.blankPrompt())
    }

    @Test
    fun save_prompt_name_too_long_light() = snapshot("save_prompt_name_too_long", dark = false) {
        // One character over the cap: the boundary is where the message has to
        // appear, and a wildly long name would prove nothing about the edge.
        Body(name = "x".repeat(SavePromptAsPresetPreview.CAP + 1))
    }

    @Composable
    private fun Body(name: String, ui: SavePromptAsPresetDialogUi = SavePromptAsPresetPreview.form()) {
        SavePromptAsPresetDialogBody(
            ui = ui,
            name = name,
            onNameChange = {},
            description = "",
            onDescriptionChange = {},
            tagsRaw = "concise, short",
            onTagsChange = {},
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
                    Box(modifier = Modifier.background(KnotworkTheme.extended.surface1)) { content() }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/prompt_dialog_${name}_$theme.png",
        )
    }
}
