package app.knotwork.design.screens.monitoring

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class MonitoringContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun monitoring_default_light() = snapshot(name = "default", dark = false) {
        MonitoringContent(state = MonitoringPreview.default())
    }

    @Test
    fun monitoring_default_dark() = snapshot(name = "default", dark = true) {
        MonitoringContent(state = MonitoringPreview.default())
    }

    @Test
    fun monitoring_power_saving_light() = snapshot(name = "power_saving", dark = false) {
        MonitoringContent(state = MonitoringPreview.powerSaving())
    }

    @Test
    fun monitoring_empty_dark() = snapshot(name = "empty", dark = true) {
        MonitoringContent(state = MonitoringPreview.empty())
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
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/monitoring_${name}_$themeTag.png",
        )
    }
}

internal object MonitoringPreview {
    private fun stats(): List<MonitoringStat> = listOf(
        MonitoringStat(label = "INFERENCE TIME", value = "42 ms"),
        MonitoringStat(label = "TOKENS/S", value = "23.4"),
        MonitoringStat(label = "TOTAL TOKENS", value = "8 312"),
    )

    fun default(): MonitoringViewState = MonitoringViewState(
        visualState = MonitoringVisualState.Default,
        stats = stats(),
        totalExecutionLine = "1 240 ms",
        perNodeBreakdown = listOf(
            NodeBreakdownRow(nodeType = "LITE_RT", totalLabel = "640 ms"),
            NodeBreakdownRow(nodeType = "TOOL", totalLabel = "412 ms"),
            NodeBreakdownRow(nodeType = "OUTPUT", totalLabel = "188 ms"),
        ),
        logs = listOf(
            MonitoringLogLine(timestamp = "12:04:01", message = "Pipeline 'Travel planner' started"),
            MonitoringLogLine(timestamp = "12:04:11", message = "Tool 'flight_search' completed"),
        ),
    )

    fun powerSaving(): MonitoringViewState = default().copy(powerSavingActive = true)

    fun empty(): MonitoringViewState = MonitoringViewState(visualState = MonitoringVisualState.Empty)
}
