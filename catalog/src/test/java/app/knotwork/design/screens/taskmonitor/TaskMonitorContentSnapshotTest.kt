package app.knotwork.design.screens.taskmonitor

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
class TaskMonitorContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun taskmonitor_default_light() = snapshot(name = "default", dark = false) {
        TaskMonitorContent(state = TaskMonitorPreview.default())
    }

    @Test
    fun taskmonitor_default_dark() = snapshot(name = "default", dark = true) {
        TaskMonitorContent(state = TaskMonitorPreview.default())
    }

    @Test
    fun taskmonitor_empty_light() = snapshot(name = "empty", dark = false) {
        TaskMonitorContent(state = TaskMonitorPreview.empty())
    }

    @Test
    fun taskmonitor_empty_dark() = snapshot(name = "empty", dark = true) {
        TaskMonitorContent(state = TaskMonitorPreview.empty())
    }

    @Test
    fun taskmonitor_cancel_all_light() = snapshot(name = "cancel_all", dark = false) {
        TaskMonitorContent(state = TaskMonitorPreview.confirmingCancelAll())
    }

    @Test
    fun taskmonitor_cancel_all_dark() = snapshot(name = "cancel_all", dark = true) {
        TaskMonitorContent(state = TaskMonitorPreview.confirmingCancelAll())
    }

    @Test
    fun taskmonitor_detail_light() = snapshot(name = "detail", dark = false) {
        TaskMonitorDetailSheetBody(
            detail = TaskMonitorPreview.detail(),
            strings = TaskMonitorStrings(),
            callbacks = noopTaskMonitorCallbacks(),
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
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/taskmonitor_${name}_$themeTag.png",
        )
    }
}

internal object TaskMonitorPreview {
    fun default(): TaskMonitorViewState = TaskMonitorViewState(
        visualState = TaskMonitorVisualState.Default,
        filter = TaskFilterKind.All,
        rows = listOf(
            TaskMonitorRow(
                id = "1",
                title = "Plan flight to Madrid",
                subtitle = "CLOUD · gpt-4.1-mini",
                status = TaskRowStatus.Running,
                progress = 0.42f,
                isCancellable = true,
            ),
            TaskMonitorRow(
                id = "2",
                title = "Re-embed memory chunks",
                subtitle = "Background · WorkManager",
                status = TaskRowStatus.Queued,
                isCancellable = true,
            ),
            TaskMonitorRow(
                id = "3",
                title = "Summarise yesterday's chat",
                subtitle = "LITE_RT · gemma-4-E2B-it",
                status = TaskRowStatus.Success,
            ),
        ),
    )

    fun empty(): TaskMonitorViewState = TaskMonitorViewState(
        visualState = TaskMonitorVisualState.Empty,
    )

    /**
     * Two scheduled tasks queued and the bulk-cancel confirmation open — the
     * recovery path out of a task that keeps re-scheduling itself, and the only
     * state in which the top-bar action is offered at all.
     */
    fun confirmingCancelAll(): TaskMonitorViewState = default().copy(
        rows = listOf(
            TaskMonitorRow(
                id = "s1",
                title = "write the evening journal entry",
                subtitle = "Scheduled task · every 6 h · Evening journal",
                status = TaskRowStatus.Queued,
                isCancellable = true,
            ),
            TaskMonitorRow(
                id = "s2",
                title = "check the inbox and summarise anything new",
                subtitle = "Scheduled task · Inbox triage",
                status = TaskRowStatus.Running,
                progress = -1f,
                isCancellable = true,
            ),
        ),
        scheduledTaskCount = 2,
        confirmingCancelAll = true,
    )

    fun detail(): TaskMonitorDetail = TaskMonitorDetail(
        id = "1",
        title = "Plan flight to Madrid",
        subtitle = "CLOUD · gpt-4.1-mini",
        status = TaskRowStatus.Running,
        logs = listOf(
            "12:04:01 [INPUT] Plan a flight to Madrid for tomorrow morning",
            "12:04:02 [DECOMPOSITION] 1) Check calendar 2) Search flights 3) Compare prices",
            "12:04:05 [TOOL] calendar_lookup → empty 09:00-12:00",
            "12:04:11 [TOOL] flight_search → 12 options",
        ),
        // Session-style task — exercises the visible Open chat CTA;
        // the background-task variant with the CTA hidden is exercised
        // indirectly via the default-state list snapshot.
        canOpenChat = true,
    )
}
