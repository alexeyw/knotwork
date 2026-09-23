package app.knotwork.design.components.dialogs

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
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the shared single-field dialog.
 *
 * This shape had been written twice — renaming a preset and naming a pipeline —
 * and only one of the two had a baseline. One component now serves both, so the
 * picture below covers both.
 *
 * **Photographed as a body, not as a dialog.** A text field inside an
 * `AlertDialog` never reaches idle under Robolectric: `setContent` spins until
 * Espresso raises `AppNotIdleException`. That reproduces with the plain Material
 * `OutlinedTextField`, and neither the v2 compose rule nor a clock frozen before
 * `setContent` changes it — the host is what never settles, not the field. The
 * remedy is the one `NodeConfigSheet` already uses for `ModalBottomSheet`:
 * capture the body. The wrapper contributes a title and two buttons, and the
 * confirm gate is a pure rule exercised in `SingleFieldDialogTest`.
 *
 * The empty variant is captured because it is the state the confirm gate acts
 * on, and an empty field is easy to lay out wrongly — a label with nothing
 * under it is where a missing baseline usually shows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class SingleFieldDialogSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val ui = SingleFieldDialogUi(
        title = "Rename pipeline",
        label = "Name",
        initialValue = "Morning brief",
        confirmLabel = "Save",
        cancelLabel = "Cancel",
    )

    @Test
    fun single_field_light() = snapshot("filled", dark = false) {
        SingleFieldDialogBody(ui = ui, value = ui.initialValue, onValueChange = {}, modifier = Modifier.padding(16.dp))
    }

    @Test
    fun single_field_dark() = snapshot("filled", dark = true) {
        SingleFieldDialogBody(ui = ui, value = ui.initialValue, onValueChange = {}, modifier = Modifier.padding(16.dp))
    }

    @Test
    fun single_field_empty_light() = snapshot("empty", dark = false) {
        SingleFieldDialogBody(ui = ui, value = "", onValueChange = {}, modifier = Modifier.padding(16.dp))
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
            filePath = "src/test/snapshots/single_field_dialog_${name}_$theme.png",
        )
    }
}
