package app.knotwork.design.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.console.ConsolePane
import app.knotwork.design.components.console.ConsoleTab
import app.knotwork.design.components.console.RunHeaderStrip
import app.knotwork.design.screens.chat.ChatHomeRunPreview.Header
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the run strip in every state the design draws, the
 * Traces and Vars hash chips, the run-again confirmation and the export sheet —
 * over the chat with the console open, in both themes. The console is dark in
 * both; the chat, the sheets and the dialogs follow the theme.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class RunHeaderSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun console_run_line_local_light() = snapshot(name = "console_run_line_local", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false))
    }

    @Test
    fun console_run_line_local_dark() = snapshot(name = "console_run_line_local", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false))
    }

    @Test
    fun console_run_line_fontscale200_light() =
        snapshot(name = "console_run_line_fontscale200", dark = false, fontScale = 2f) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false))
        }

    @Test
    fun console_run_line_fontscale200_dark() =
        snapshot(name = "console_run_line_fontscale200", dark = true, fontScale = 2f) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false))
        }

    @Test
    fun console_run_header_local_cpu_light() = snapshot(name = "console_run_header_local_cpu", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.LOCAL_CPU))
    }

    @Test
    fun console_run_header_local_cpu_dark() = snapshot(name = "console_run_header_local_cpu", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.LOCAL_CPU))
    }

    @Test
    fun console_run_header_fontscale200_light() =
        snapshot(name = "console_run_header_fontscale200", dark = false, fontScale = 2f) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.LOCAL_CPU))
        }

    @Test
    fun console_run_header_fontscale200_dark() =
        snapshot(name = "console_run_header_fontscale200", dark = true, fontScale = 2f) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.LOCAL_CPU))
        }

    @Test
    fun console_run_header_gpu_light() = snapshot(name = "console_run_header_gpu", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.GPU))
    }

    @Test
    fun console_run_header_gpu_dark() = snapshot(name = "console_run_header_gpu", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.GPU))
    }

    @Test
    fun console_run_header_mixed_light() = snapshot(name = "console_run_header_mixed", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.MIXED))
    }

    @Test
    fun console_run_header_mixed_dark() = snapshot(name = "console_run_header_mixed", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.MIXED))
    }

    @Test
    fun console_run_header_cloud_only_light() = snapshot(name = "console_run_header_cloud_only", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.CLOUD_ONLY))
    }

    @Test
    fun console_run_header_cloud_only_dark() = snapshot(name = "console_run_header_cloud_only", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.CLOUD_ONLY))
    }

    @Test
    fun console_run_header_npu_light() = snapshot(name = "console_run_header_npu", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.NPU))
    }

    @Test
    fun console_run_header_npu_dark() = snapshot(name = "console_run_header_npu", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.NPU))
    }

    @Test
    fun console_run_header_pre_version_light() = snapshot(name = "console_run_header_pre_version", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.PRE_VERSION, expanded = false))
    }

    @Test
    fun console_run_header_pre_version_dark() = snapshot(name = "console_run_header_pre_version", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.PRE_VERSION, expanded = false))
    }

    @Test
    fun console_run_header_pre_version_expanded_light() =
        snapshot(name = "console_run_header_pre_version_expanded", dark = false) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.PRE_VERSION))
        }

    @Test
    fun console_run_header_pre_version_expanded_dark() =
        snapshot(name = "console_run_header_pre_version_expanded", dark = true) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.PRE_VERSION))
        }

    @Test
    fun console_run_header_hash_pending_light() = snapshot(name = "console_run_header_hash_pending", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.HASH_PENDING))
    }

    @Test
    fun console_run_header_hash_pending_dark() = snapshot(name = "console_run_header_hash_pending", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.HASH_PENDING))
    }

    @Test
    fun console_run_header_active_light() = snapshot(name = "console_run_header_active", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.ACTIVE))
    }

    @Test
    fun console_run_header_active_dark() = snapshot(name = "console_run_header_active", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.ACTIVE))
    }

    @Test
    fun console_run_header_seed_not_offered_image_light() =
        snapshot(name = "console_run_header_seed_not_offered_image", dark = false) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.SEED_NOT_OFFERED_IMAGE))
        }

    @Test
    fun console_run_header_seed_not_offered_image_dark() =
        snapshot(name = "console_run_header_seed_not_offered_image", dark = true) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.SEED_NOT_OFFERED_IMAGE))
        }

    @Test
    fun console_run_header_seed_not_offered_trigger_light() =
        snapshot(name = "console_run_header_seed_not_offered_trigger", dark = false) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.SEED_NOT_OFFERED_TRIGGER))
        }

    @Test
    fun console_run_header_seed_not_offered_trigger_dark() =
        snapshot(name = "console_run_header_seed_not_offered_trigger", dark = true) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.SEED_NOT_OFFERED_TRIGGER))
        }

    @Test
    fun console_run_header_verify_mismatch_light() =
        snapshot(name = "console_run_header_verify_mismatch", dark = false) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(Header.VERIFY_MISMATCH))
        }

    @Test
    fun console_run_header_verify_mismatch_dark() = snapshot(name = "console_run_header_verify_mismatch", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(Header.VERIFY_MISMATCH))
    }

    @Test
    fun console_traces_hashes_light() = snapshot(name = "console_traces_hashes", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false, tab = ConsoleTab.Traces))
    }

    @Test
    fun console_traces_hashes_dark() = snapshot(name = "console_traces_hashes", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false, tab = ConsoleTab.Traces))
    }

    @Test
    fun console_traces_hashes_fontscale200_light() =
        snapshot(name = "console_traces_hashes_fontscale200", dark = false, fontScale = 2f) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false, tab = ConsoleTab.Traces))
        }

    @Test
    fun console_traces_hashes_fontscale200_dark() =
        snapshot(name = "console_traces_hashes_fontscale200", dark = true, fontScale = 2f) {
            ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false, tab = ConsoleTab.Traces))
        }

    @Test
    fun console_vars_hashes_light() = snapshot(name = "console_vars_hashes", dark = false) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false, tab = ConsoleTab.Vars))
    }

    @Test
    fun console_vars_hashes_dark() = snapshot(name = "console_vars_hashes", dark = true) {
        ConsoleAtFull(state = ChatHomeRunPreview.console(expanded = false, tab = ConsoleTab.Vars))
    }

    @Test
    fun run_again_seed_confirm_light() = snapshot(name = "run_again_seed_confirm", dark = false) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console().withRun(
                ChatHomeRunState(runAgainConfirm = ChatHomeRunPreview.runAgainConfirm()),
            ),
        )
    }

    @Test
    fun run_again_seed_confirm_dark() = snapshot(name = "run_again_seed_confirm", dark = true) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console().withRun(
                ChatHomeRunState(runAgainConfirm = ChatHomeRunPreview.runAgainConfirm()),
            ),
        )
    }

    @Test
    fun export_trace_sheet_light() = snapshot(name = "export_trace_sheet", dark = false) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console().withRun(ChatHomeRunState(export = ChatHomeRunPreview.export())),
        )
    }

    @Test
    fun export_trace_sheet_dark() = snapshot(name = "export_trace_sheet", dark = true) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console().withRun(ChatHomeRunState(export = ChatHomeRunPreview.export())),
        )
    }

    @Test
    fun export_trace_sheet_fontscale200_light() =
        snapshot(name = "export_trace_sheet_fontscale200", dark = false, fontScale = 2f) {
            ChatHomeContent(
                state = ChatHomeRunPreview.console().withRun(ChatHomeRunState(export = ChatHomeRunPreview.export())),
            )
        }

    @Test
    fun export_trace_sheet_fontscale200_dark() =
        snapshot(name = "export_trace_sheet_fontscale200", dark = true, fontScale = 2f) {
            ChatHomeContent(
                state = ChatHomeRunPreview.console().withRun(ChatHomeRunState(export = ChatHomeRunPreview.export())),
            )
        }

    /**
     * Renders under the test rule with reduced motion pinned and [fontScale]
     * applied to the density, and writes `src/test/snapshots/<name>_<theme>.png`.
     */
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
        // The console opens at Full: let the sheet settle there before the capture.
        composeTestRule.mainClock.advanceTimeBy(SHEET_SETTLE_MS)
        composeTestRule.waitForIdle()
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/${name}_$themeTag.png",
        )
    }
}

/** Top inset of the console at its Full snap in the design's frames. */
private val ConsoleFullTop = 76.dp

/**
 * The chat with the console drawn at its Full snap, as the design's frames show it.
 *
 * The console's `ModalBottomSheet` stays at its first anchor under Robolectric, which
 * would leave an open header's actions below the frame; the frames are about the
 * strip, so the pane is drawn in place of the sheet — the same `ConsolePane`, slot
 * and strip the sheet hosts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleAtFull(state: ChatHomeViewState) {
    val header = state.console.runHeader
    Box(modifier = Modifier.fillMaxSize()) {
        ChatHomeContent(state = state.copy(console = state.console.copy(snap = null)))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = ConsoleFullTop)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(KnotworkTheme.extended.consoleBg),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                BottomSheetDefaults.DragHandle(color = KnotworkTheme.extended.consoleFg.copy(alpha = 0.3f))
            }
            ConsolePane(
                tab = state.console.tab,
                onTabChange = {},
                logs = state.console.logs,
                vars = state.console.vars,
                traces = state.console.traces,
                filter = state.console.filter,
                onFilterChange = {},
                onSearch = {},
                onCopyAll = {},
                onClear = {},
                runHeader = header?.let {
                    {
                        RunHeaderStrip(
                            header = it,
                            expanded = state.console.runHeaderExpanded,
                            onToggle = {},
                            onCopySeed = {},
                            onCopyModelSha = {},
                            onCopyDigest = {},
                            onVerify = {},
                            onRunAgain = {},
                            onExport = {},
                            onOpenSettings = {},
                        )
                    }
                },
            )
        }
    }
}

/** [this] with the run surfaces in [run]. */
internal fun ChatHomeViewState.withRun(run: ChatHomeRunState): ChatHomeViewState = copy(run = run)

/** Virtual time for a bottom sheet to settle at its target before a capture. */
internal const val SHEET_SETTLE_MS = 2_000L
