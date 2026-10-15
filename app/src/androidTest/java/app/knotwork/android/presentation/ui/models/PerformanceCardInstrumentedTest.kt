package app.knotwork.android.presentation.ui.models

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.knotwork.design.screens.models.BenchmarkPhase
import app.knotwork.design.screens.models.PerformanceCallbacks
import app.knotwork.design.screens.models.PerformanceCard
import app.knotwork.design.screens.models.PerformanceCardState
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the model **Performance card**, iterating its state
 * matrix (empty / busy / running / result / populated) and verifying the
 * Run-benchmark action dispatches. The card is the stateless catalog
 * [PerformanceCard] with default (English) [strings], rendered with a synthetic
 * [PerformanceCardState].
 */
class PerformanceCardInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun empty_rendersEmptyState_andRunBenchmarkDispatches() {
        var runCount = 0
        composeTestRule.setContent {
            KnotworkTheme {
                PerformanceCard(
                    state = PerformanceCardState.Empty,
                    callbacks = PerformanceCallbacks(onRunBenchmark = { runCount++ }),
                )
            }
        }

        composeTestRule.onNodeWithText(EMPTY_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(RUN_BENCHMARK).performClick()

        assertEquals("Run-benchmark callback should fire once", 1, runCount)
    }

    @Test
    fun engineBusy_rendersBusyState() {
        composeTestRule.setContent {
            KnotworkTheme { PerformanceCard(state = PerformanceCardState.EngineBusy) }
        }

        composeTestRule.onNodeWithText(BUSY_TITLE).assertIsDisplayed()
    }

    @Test
    fun running_warmingUp_rendersWarmUpTitle() {
        composeTestRule.setContent {
            KnotworkTheme {
                PerformanceCard(state = PerformanceCardState.Running(phase = BenchmarkPhase.WarmingUp))
            }
        }

        composeTestRule.onNodeWithText(WARMING_UP_TITLE).assertIsDisplayed()
    }

    @Test
    fun running_measuring_rendersMeasuringTitle() {
        composeTestRule.setContent {
            KnotworkTheme {
                PerformanceCard(state = PerformanceCardState.Running(phase = BenchmarkPhase.Measuring))
            }
        }

        composeTestRule.onNodeWithText(MEASURING_TITLE).assertIsDisplayed()
    }

    @Test
    fun result_rendersBenchmarkBadgeAndMeasuredFigures() {
        composeTestRule.setContent {
            KnotworkTheme {
                PerformanceCard(
                    state = PerformanceCardState.Result(
                        ttft = TTFT_VALUE,
                        decode = "12.4 tok/s",
                        total = "2.6 s",
                        peakMemory = "1.8 GB",
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText(BENCHMARK_BADGE).assertIsDisplayed()
        composeTestRule.onNodeWithText(TTFT_VALUE).assertIsDisplayed()
    }

    @Test
    fun populated_rendersRollingAverageFigures() {
        composeTestRule.setContent {
            KnotworkTheme {
                PerformanceCard(
                    state = PerformanceCardState.Populated(
                        ttft = TTFT_VALUE,
                        decode = "12.4 tok/s",
                        peakMemory = "1.8 GB",
                        sampleCaption = SAMPLE_CAPTION,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText(TTFT_VALUE).assertIsDisplayed()
        composeTestRule.onNodeWithText(SAMPLE_CAPTION).assertIsDisplayed()
    }

    private companion object {
        // Mirrors the catalog PerformanceStrings English defaults.
        const val EMPTY_TITLE: String = "No runs yet"
        const val RUN_BENCHMARK: String = "Run benchmark"
        const val BUSY_TITLE: String = "Busy with a task"
        const val WARMING_UP_TITLE: String = "Warming up…"
        const val MEASURING_TITLE: String = "Measuring…"
        const val BENCHMARK_BADGE: String = "BENCHMARK"
        const val TTFT_VALUE: String = "420 ms"
        const val SAMPLE_CAPTION: String = "avg · last 8 runs"
    }
}
