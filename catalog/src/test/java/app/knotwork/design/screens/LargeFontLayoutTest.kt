package app.knotwork.design.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.R
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.text.TextBreakAssertions
import app.knotwork.design.screens.memory.MemoryContent
import app.knotwork.design.screens.memory.MemoryPreview
import app.knotwork.design.screens.models.PerformanceCard
import app.knotwork.design.screens.models.PerformancePreview
import app.knotwork.design.screens.monitoring.MonitoringContent
import app.knotwork.design.screens.monitoring.MonitoringPreview
import app.knotwork.design.screens.settings.LocalSettingsHints
import app.knotwork.design.screens.settings.SettingsHint
import app.knotwork.design.screens.settings.SettingsHintController
import app.knotwork.design.screens.settings.SettingsHubContent
import app.knotwork.design.screens.settings.SettingsPreview
import app.knotwork.design.screens.settings.ToolsSettingsContent
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At double font on a 360 dp phone, the texts of a row that sets a label beside a value or
 * a control stay whole: each line ends between words and nothing is cut. A row used to
 * measure its fixed part first and hand the label what was left, or split its width in
 * fixed shares, and a larger font then broke words in two.
 *
 * Native graphics, so text has real widths; the screen is tall so that a lazy list composes
 * every row under test.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h2400dp-xhdpi")
class LargeFontLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(fontScale: Float = DOUBLE, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // Every settings row carries a help glyph in the app; it takes width beside the label.
            val hints = remember { SettingsHintController { SettingsHint(text = "What this setting does.") } }
            KnotworkTheme {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                    LocalSettingsHints provides hints,
                ) { content() }
            }
        }
    }

    private fun assertApproveRowWhole() {
        TextBreakAssertions.assertWhole(
            composeTestRule,
            context.getString(R.string.knotwork_settings_restrictions_approve),
            "All",
            "Sensitive +",
            "Never",
        )
    }

    @Test
    fun `given the settings hub at the default font when laid out then the approve row is whole`() {
        show(fontScale = 1f) { SettingsHubContent(state = SettingsPreview.hubDefault()) }

        assertApproveRowWhole()
    }

    @Test
    fun `given the settings hub at double font when laid out then the approve row is whole`() {
        show { SettingsHubContent(state = SettingsPreview.hubDefault()) }

        assertApproveRowWhole()
    }

    @Test
    fun `given the tools settings at double font when laid out then the approve row is whole`() {
        show { ToolsSettingsContent(state = SettingsPreview.tools()) }

        assertApproveRowWhole()
    }

    @Test
    fun `given measured performance at double font when laid out then every text is whole`() {
        show { PerformanceCard(state = PerformancePreview.populated()) }

        TextBreakAssertions.assertEveryTextWhole(composeTestRule)
    }

    @Test
    fun `given a benchmark result at double font when laid out then every text is whole`() {
        show { PerformanceCard(state = PerformancePreview.result()) }

        TextBreakAssertions.assertEveryTextWhole(composeTestRule)
    }

    @Test
    fun `given live metrics at double font when laid out then every text is whole`() {
        show { MonitoringContent(state = MonitoringPreview.default()) }

        TextBreakAssertions.assertEveryTextWhole(composeTestRule)
    }

    @Test
    fun `given the memory overview at double font when laid out then its counts and button are whole`() {
        show { MemoryContent(state = MemoryPreview.populated()) }

        TextBreakAssertions.assertWhole(
            composeTestRule,
            "1248",
            context.getString(R.string.knotwork_memory_count_unit),
            "14.2 MB  ·  compacted 3 d ago",
            context.getString(R.string.knotwork_memory_compact),
        )
    }

    private companion object {
        /** The largest font scale Android offers, at which these rows broke. */
        const val DOUBLE = 2f
    }
}
