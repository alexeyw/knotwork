package app.knotwork.design.screens.help

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
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the Help list and the document reader.
 *
 * Both themes for every state, plus **font scale 200 %** for each screen. The
 * 200 % captures are load-bearing rather than decorative: a document row puts a
 * delivery glyph inline after a label that is free to wrap, and the reader's
 * bar keeps two fixed 48 dp touch targets beside a title that is allowed to
 * grow to two lines. Both are exactly the arrangements that clip when the text
 * doubles and nothing is watching.
 *
 * The reader's *body* is not captured here. It is supplied by the app, since
 * the catalog carries no Markdown dependency — what these baselines cover is
 * the chrome, which is what this module owns.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class HelpSnapshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun help_list_default_light() = snapshot("list_default", dark = false) { HelpList() }

    @Test
    fun help_list_default_dark() = snapshot("list_default", dark = true) { HelpList() }

    @Test
    fun help_list_offline_refusal_light() =
        snapshot("list_offline_refusal", dark = false) { HelpList(refusedId = "user-guide") }

    @Test
    fun help_list_offline_refusal_dark() =
        snapshot("list_offline_refusal", dark = true) { HelpList(refusedId = "user-guide") }

    @Test
    fun help_list_font_scale_200_light() =
        snapshot("list_fontscale200", dark = false, fontScale = FONT_SCALE_200) { HelpList() }

    @Test
    fun help_list_font_scale_200_dark() =
        snapshot("list_fontscale200", dark = true, fontScale = FONT_SCALE_200) { HelpList() }

    @Test
    fun help_reader_loading_light() =
        snapshot("reader_loading", dark = false) { HelpReader(HelpReaderVisualState.LOADING) }

    @Test
    fun help_reader_loading_dark() =
        snapshot("reader_loading", dark = true) { HelpReader(HelpReaderVisualState.LOADING) }

    @Test
    fun help_reader_error_light() = snapshot("reader_error", dark = false) { HelpReader(HelpReaderVisualState.ERROR) }

    @Test
    fun help_reader_error_dark() = snapshot("reader_error", dark = true) { HelpReader(HelpReaderVisualState.ERROR) }

    @Test
    fun help_reader_offline_bar_light() = snapshot("reader_offline_bar", dark = false) {
        HelpReader(HelpReaderVisualState.CONTENT, offlineBar = true)
    }

    @Test
    fun help_reader_offline_bar_dark() = snapshot("reader_offline_bar", dark = true) {
        HelpReader(HelpReaderVisualState.CONTENT, offlineBar = true)
    }

    @Test
    fun help_reader_font_scale_200_light() = snapshot("reader_fontscale200", dark = false, fontScale = FONT_SCALE_200) {
        HelpReader(HelpReaderVisualState.ERROR)
    }

    @Test
    fun help_reader_font_scale_200_dark() = snapshot("reader_fontscale200", dark = true, fontScale = FONT_SCALE_200) {
        HelpReader(HelpReaderVisualState.ERROR)
    }

    /**
     * The list, with an optional refusal open under one row.
     *
     * @param refusedId Id of the row whose refusal is open, or `null`.
     */
    @Composable
    private fun HelpList(refusedId: String? = null) {
        HelpListContent(
            state = HelpPreview.list(refusedId = refusedId),
            strings = HelpPreview.strings(),
            callbacks = HelpListCallbacks(),
        )
    }

    /**
     * The reader's chrome in one of its three frames.
     *
     * @param visualState Which frame to draw.
     * @param offlineBar Whether the in-body refusal is showing.
     */
    @Composable
    private fun HelpReader(visualState: HelpReaderVisualState, offlineBar: Boolean = false) {
        HelpReaderContent(
            state = HelpPreview.reader(visualState = visualState, offlineBar = offlineBar),
            strings = HelpPreview.strings(),
            callbacks = HelpReaderCallbacks(),
        ) {
            // Stands in for the app's Markdown renderer, which this module
            // deliberately cannot reach.
            HelpPreview.BodyPlaceholder()
        }
    }

    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) { content() }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/help_${name}_$themeTag.png",
        )
    }

    private companion object {
        const val FONT_SCALE_200: Float = 2f
    }
}
