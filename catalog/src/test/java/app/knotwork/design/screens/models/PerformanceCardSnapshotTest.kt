package app.knotwork.design.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * Roborazzi baseline for `PerformanceCard`. Covers every documented state:
 * empty / populated / benchmark running (warm-up + measure) / benchmark result /
 * engine busy / no model, with both themes for the persistent states.
 *
 * Reduced motion is pinned on so the running-state spinner/segment animations
 * render in their static frame for deterministic snapshots.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class PerformanceCardSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun perf_empty_light() = snapshot(name = "empty", dark = false) {
        PerformanceCard(state = PerformanceCardState.Empty)
    }

    @Test
    fun perf_empty_dark() = snapshot(name = "empty", dark = true) {
        PerformanceCard(state = PerformanceCardState.Empty)
    }

    @Test
    fun perf_populated_light() = snapshot(name = "populated", dark = false) {
        PerformanceCard(state = PerformancePreview.populated())
    }

    @Test
    fun perf_populated_dark() = snapshot(name = "populated", dark = true) {
        PerformanceCard(state = PerformancePreview.populated())
    }

    @Test
    fun perf_result_light() = snapshot(name = "result", dark = false) {
        PerformanceCard(state = PerformancePreview.result())
    }

    @Test
    fun perf_result_dark() = snapshot(name = "result", dark = true) {
        PerformanceCard(state = PerformancePreview.result())
    }

    @Test
    fun perf_running_warmup_light() = snapshot(name = "running_warmup", dark = false) {
        PerformanceCard(state = PerformanceCardState.Running(BenchmarkPhase.WarmingUp))
    }

    @Test
    fun perf_running_measure_light() = snapshot(name = "running_measure", dark = false) {
        PerformanceCard(state = PerformanceCardState.Running(BenchmarkPhase.Measuring))
    }

    @Test
    fun perf_busy_light() = snapshot(name = "busy", dark = false) {
        PerformanceCard(state = PerformanceCardState.EngineBusy)
    }

    @Test
    fun perf_no_model_light() = snapshot(name = "no_model", dark = false) {
        PerformanceCard(state = PerformanceCardState.NoModel)
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            // The provider sits INSIDE KnotworkTheme. It used to be mandatory —
            // the theme re-installed `DefaultKnotworkA11y` and swallowed an
            // outer override, leaving the running-state animations unpinned.
            // The theme no longer does that, but this placement is still the
            // one to prefer: it survives the theme provisioning the local again.
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                    ) {
                        content()
                    }
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/perf_${name}_$themeTag.png",
        )
    }
}

/** Snapshot fixtures for the Performance card. */
internal object PerformancePreview {
    fun populated(): PerformanceCardState.Populated = PerformanceCardState.Populated(
        ttft = "420 ms",
        decode = "12.4 tok/s",
        peakMemory = "1.8 GB",
        sampleCaption = "avg · last 8 runs",
    )

    fun result(): PerformanceCardState.Result = PerformanceCardState.Result(
        ttft = "0.39 s",
        decode = "13.1 tok/s",
        total = "2.6 s",
        peakMemory = "1.8 GB",
    )
}
