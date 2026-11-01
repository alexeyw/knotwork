package app.knotwork.design.components.console

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
 * Roborazzi baselines of the Vars tab's rows, one of each kind: short values as they are,
 * a value with line breaks drawn as marks, values cut to a preview with their size under
 * them, an empty value, and a nested node. At 100 % and 200 % font scale.
 *
 * The console's catalog page captures only its Logs pane within the frame, so without
 * these the rows' look had no baseline at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleVarRowSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val rows = listOf(
        ConsoleVarRow(node = "INPUT#7f0a91", key = "text", value = "What's on today?"),
        ConsoleVarRow(node = "TOOL#9c41d2", key = "status", value = "200"),
        ConsoleVarRow(
            node = "TOOL#9c41d2",
            key = "body",
            value = "{\"latitude\":52.37,\"longitude\":4.89,\"hourly\":{\"temperature_2m\":[" +
                (0 until 2_000).joinToString(",") { "${it % 31}.${it % 10}" } + "]}}",
        ),
        ConsoleVarRow(
            node = "LITE_RT#501fab",
            key = "input",
            value = "You are summarising today's calendar for one person.\n\nRules:\n" +
                "- One sentence per event, in time order.\n- Give the start time first.\n" +
                "- Leave out events that were declined.",
            depth = 1,
        ),
        ConsoleVarRow(
            node = "LITE_RT#501fab",
            key = "output",
            value = "Design review at 11:00, vendor call at 15:30.",
            depth = 1,
        ),
        ConsoleVarRow(node = "TOOL#2e7c40", key = "reason", value = ""),
    )

    @Test
    fun console_vars_rows() = snapshot(name = "console_vars_rows", fontScale = 1f)

    @Test
    fun console_vars_rows_fs20() = snapshot(name = "console_vars_rows_fs20", fontScale = FONT_SCALE_200)

    private fun snapshot(name: String, fontScale: Float) {
        rule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) {
                    ConsolePane(
                        tab = ConsoleTab.Vars,
                        onTabChange = {},
                        logs = emptyList(),
                        vars = rows,
                        traces = emptyList(),
                        filter = ConsoleFilter.allOn,
                        onFilterChange = {},
                        onSearch = {},
                        onCopyAll = {},
                        onClear = {},
                        // As the app wires it: cut rows open the value viewer, so they carry a chevron.
                        onOpenVar = {},
                    )
                }
            }
        }
        rule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$name.png",
        )
    }

    private companion object {
        const val FONT_SCALE_200: Float = 2f
    }
}
