package app.knotwork.design.components.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Roborazzi baselines for the [OutcomeDialog] family — the four import
 * outcomes and the re-import question, in one shape.
 *
 * The pair worth looking at together is `refused` and `parse`: a capability
 * ceiling that held is not an error, so `refused` draws a shield and `parse`
 * is the only red on the surface.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class OutcomeDialogSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ok = OutcomeAction(label = "OK", onClick = {})

    @Test
    fun outcome_mismatch_light() = snapshot(name = "mismatch", dark = false) {
        OutcomeDialog(
            tone = OutcomeTone.INFO,
            headline = "Imported without some settings",
            body = "“Report writer” was written by a different version of the app. " +
                "The prompt came across; these settings did not.",
            namedList = OutcomeNamedList(heading = "LEFT OUT", items = listOf("Version: 99", "temperature")),
            confirm = ok,
            onDismissRequest = {},
        )
    }

    @Test
    fun outcome_refused_light() = snapshot(name = "refused", dark = false) {
        RefusedDialog()
    }

    @Test
    fun outcome_refused_dark() = snapshot(name = "refused", dark = true) {
        RefusedDialog()
    }

    @Test
    fun outcome_parse_light() = snapshot(name = "parse", dark = false) {
        OutcomeDialog(
            tone = OutcomeTone.ERROR,
            headline = "Nothing was imported",
            body = "The settings block at the top of the file is missing or incomplete. " +
                "It has to sit between two lines of three dashes.",
            confirm = ok,
            onDismissRequest = {},
        )
    }

    @Test
    fun outcome_parse_font_scale_200() = snapshot(name = "parse_fontscale200", dark = false) {
        // The body is the only part that can grow without bound, so it is the
        // part that scrolls. This baseline is what proves the action row is
        // still on screen at 200 % rather than pushed off the bottom.
        AtFontScale(scale = 2f) {
            OutcomeDialog(
                tone = OutcomeTone.ERROR,
                headline = "Nothing was imported",
                body = "The settings block at the top of the file is missing or incomplete. " +
                    "It has to sit between two lines of three dashes.",
                confirm = ok,
                onDismissRequest = {},
            )
        }
    }

    @Test
    fun outcome_question_light() = snapshot(name = "question", dark = false) {
        OutcomeDialog(
            tone = OutcomeTone.QUESTION,
            headline = "“Standup digest” is already in your library",
            body = "The file and the saved prompt are not the same. " +
                "Keeping both saves the file as a separate prompt.",
            confirm = OutcomeAction(label = "Keep both", onClick = {}, emphasis = OutcomeActionEmphasis.EMPHASISED),
            neutral = OutcomeAction(label = "Replace", onClick = {}),
            dismiss = OutcomeAction(label = "Cancel", onClick = {}),
            onDismissRequest = {},
        )
    }

    @Composable
    private fun RefusedDialog() {
        OutcomeDialog(
            tone = OutcomeTone.GUARD,
            headline = "Imported as text only",
            body = "“Web summarizer” is now in your library. The file also asked to add tools — " +
                "a prompt can only supply wording, so that part was left out.",
            namedList = OutcomeNamedList(heading = "LEFT OUT", items = listOf("Tools: web_search, fetch_url")),
            confirm = ok,
            onDismissRequest = {},
        )
    }

    /** Renders [content] at a forced font scale, leaving density untouched. */
    @Composable
    private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = base.density, fontScale = scale),
            content = content,
        )
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        // The dialog renders into its own window, so the capture targets the
        // dialog node rather than the (empty) root behind it.
        composeTestRule.assertNoAccentText().onNode(isDialog()).captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/outcome_dialog_${name}_$themeTag.png",
        )
    }
}
