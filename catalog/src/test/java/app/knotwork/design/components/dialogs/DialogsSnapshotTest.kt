package app.knotwork.design.components.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.screens.settings.MemoryImportDialog
import app.knotwork.design.screens.settings.MemoryImportDialogUi
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the two field-free dialogs that moved out of the
 * settings screen.
 *
 * Both are captured **whole**, unlike the ones carrying a field: without a text
 * field, an `AlertDialog` settles under Robolectric perfectly well — the
 * distinction is the field, not the dialog, and the preset delete confirmation
 * has been captured whole for the same reason all along.
 *
 * The confirm dialog is captured in both tones, and that pair is the reason it
 * exists: the sites it replaced were split between tinting the destructive
 * action and not tinting it, so "this deletes something" was a signal a reader
 * could only sometimes rely on. One picture of one tone would prove nothing
 * about the distinction.
 *
 * For the import dialog the warning state is the one worth having. The dialog
 * is the last thing a user sees before choosing between merging into their
 * memory and wiping it, and the warnings are what say the file may not match
 * what they think. A picture with no warnings would photograph the easy case.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class DialogsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun confirm_light() = snapshot("confirm", dark = false) { Confirm(destructive = false) }

    @Test
    fun confirm_dark() = snapshot("confirm", dark = true) { Confirm(destructive = false) }

    @Test
    fun confirm_destructive_light() = snapshot("confirm_destructive", dark = false) {
        Confirm(destructive = true)
    }

    @Test
    fun single_choice_light() = snapshot("single_choice", dark = false) { SingleChoice() }

    @Test
    fun single_choice_dark() = snapshot("single_choice", dark = true) { SingleChoice() }

    @Test
    fun memory_import_light() = snapshot("memory_import", dark = false) { MemoryImport(warnings = emptyList()) }

    @Test
    fun memory_import_warnings_light() = snapshot("memory_import_warnings", dark = false) {
        MemoryImport(
            warnings = listOf(
                "This file was written by a newer version of the app; some fields may be ignored.",
                "It was embedded with a different provider (gecko-110), so search quality may differ.",
            ),
        )
    }

    @Composable
    private fun Confirm(destructive: Boolean) {
        ConfirmDialog(
            ui = ConfirmDialogUi(
                title = if (destructive) "Delete this conversation?" else "Clear the console?",
                body = if (destructive) {
                    "Its messages and the runs behind them go with it. This cannot be undone."
                } else {
                    "The log is cleared for this session. Nothing that already ran is affected."
                },
                confirmLabel = if (destructive) "Delete" else "Clear",
                cancelLabel = "Cancel",
                destructive = destructive,
            ),
            onConfirm = {},
            onDismiss = {},
        )
    }

    @Composable
    private fun SingleChoice() {
        SingleChoiceDialog(
            ui = SingleChoiceDialogUi(
                title = "Pipeline for the Quick Settings tile",
                options = listOf(
                    SingleChoiceOptionUi(id = null, label = "None"),
                    SingleChoiceOptionUi(id = "p1", label = "Local Q&A"),
                    SingleChoiceOptionUi(id = "p2", label = "Research assistant"),
                    SingleChoiceOptionUi(id = "p3", label = "Morning brief"),
                ),
                selectedId = "p2",
                cancelLabel = "Cancel",
            ),
            onSelect = {},
            onDismiss = {},
        )
    }

    @Composable
    private fun MemoryImport(warnings: List<String>) {
        MemoryImportDialog(
            ui = MemoryImportDialogUi(
                title = "Import memory",
                body = "This file holds 412 entries.",
                warnings = warnings,
                mergeLabel = "Merge",
                replaceLabel = "Replace",
                cancelLabel = "Cancel",
            ),
            onMerge = {},
            onReplace = {},
            onCancel = {},
        )
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = 1f),
                LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
            ) {
                KnotworkTheme(darkTheme = dark) { content() }
            }
        }
        val theme = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/dialog_${name}_$theme.png",
        )
    }
}
