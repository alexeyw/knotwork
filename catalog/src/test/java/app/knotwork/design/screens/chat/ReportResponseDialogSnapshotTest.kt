package app.knotwork.design.screens.chat

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
 * Roborazzi baselines for the content-report dialog.
 *
 * This is the app's only in-product reporting surface — the one an app that
 * generates content with a model is obliged to have — and it had no baseline at
 * all while it lived in `:app`.
 *
 * Two selections are captured rather than one. The disclosure line is captured
 * with them deliberately: "nothing is sent from this screen" is the claim that
 * makes this dialog consistent with the app's privacy story, and a claim that
 * can silently disappear from a layout is worth photographing.
 *
 * The body rather than the dialog, for the reason `SaveAsPresetDialogBody`
 * records: a text area inside an `AlertDialog` never lets the harness idle.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ReportResponseDialogSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun report_light() = snapshot("report", dark = false) { Body(selected = "OTHER", note = "") }

    @Test
    fun report_dark() = snapshot("report", dark = true) { Body(selected = "OTHER", note = "") }

    @Test
    fun report_filled_light() = snapshot("report_filled", dark = false) {
        Body(selected = "MISLEADING", note = "It invented a citation that does not exist.")
    }

    @Composable
    private fun Body(selected: String, note: String) {
        ReportResponseDialogBody(
            ui = ReportResponseDialogPreview.dialog(),
            selectedReasonId = selected,
            onReasonSelected = {},
            note = note,
            onNoteChange = {},
            onCopy = {},
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
            filePath = "src/test/snapshots/chat_dialog_${name}_$theme.png",
        )
    }
}
