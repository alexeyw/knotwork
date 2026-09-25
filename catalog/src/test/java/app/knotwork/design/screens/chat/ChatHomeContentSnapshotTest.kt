package app.knotwork.design.screens.chat

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
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi snapshot baseline for `ChatHomeContent` across every documented
 * state of `compose/screens/README.md §C1`.
 *
 * Coverage:
 *  - 9 single-variant states × 2 themes (Empty / Idle / Generating /
 *    Clarification / Interrupted / CeilingPause / Error / DrawerOpen /
 *    ConsoleExpanded).
 *  - HitlConfirm × 3 risk variants × 2 themes (Readonly / Sensitive /
 *    Destructive) — the destructive variant doubles as the typed-confirm
 *    gating snapshot.
 *  - 2 dynamic-type variants at `fontScale = 2.0` (idle + destructive HITL)
 *    pinned per `decisions.md §14`.
 *  - The empty state's suggestion cards — the path the app renders — in both
 *    themes, on a short screen and at `fontScale = 2.0`.
 *
 * Reduced-motion is pinned via [FixedKnotworkA11y] so the `KnotworkLoader`
 * and any other looping animation collapse to a deterministic steady-state
 * per `decisions.md §14`. The snapshot baseline therefore doubles as the
 * reduced-motion fallback verification.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ChatHomeContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chat_home_empty_light() = snapshot(name = "empty", dark = false) {
        ChatHomeContent(state = ChatHomePreview.empty())
    }

    @Test
    fun chat_home_empty_dark() = snapshot(name = "empty", dark = true) {
        ChatHomeContent(state = ChatHomePreview.empty())
    }

    @Test
    fun chat_home_empty_cards_light() = snapshot(name = "empty_cards", dark = false) {
        ChatHomeContent(state = ChatHomePreview.emptyWithCards())
    }

    @Test
    fun chat_home_empty_cards_dark() = snapshot(name = "empty_cards", dark = true) {
        ChatHomeContent(state = ChatHomePreview.emptyWithCards())
    }

    @Test
    @Config(qualifiers = "w360dp-h560dp-xhdpi")
    fun chat_home_empty_cards_short_screen_light() = snapshot(name = "empty_cards_short_screen", dark = false) {
        // Too short for six cards: the column scrolls from the top rather than
        // cutting off the last ones (ChatHomeEmptyFitTest scrolls to them).
        ChatHomeContent(state = ChatHomePreview.emptyWithCards())
    }

    @Test
    fun chat_home_empty_cards_font_scale_2x_light() =
        snapshot(name = "empty_cards_font_scale_2x", dark = false, fontScale = 2f) {
            ChatHomeContent(state = ChatHomePreview.emptyWithCards())
        }

    @Test
    fun chat_home_idle_light() = snapshot(name = "idle", dark = false) {
        ChatHomeContent(state = ChatHomePreview.idle())
    }

    @Test
    fun chat_home_idle_dark() = snapshot(name = "idle", dark = true) {
        ChatHomeContent(state = ChatHomePreview.idle())
    }

    @Test
    fun chat_home_generating_light() = snapshot(name = "generating", dark = false) {
        ChatHomeContent(state = ChatHomePreview.generating())
    }

    @Test
    fun chat_home_generating_dark() = snapshot(name = "generating", dark = true) {
        ChatHomeContent(state = ChatHomePreview.generating())
    }

    @Test
    fun chat_home_waiting_in_queue_light() = snapshot(name = "waiting_in_queue", dark = false) {
        ChatHomeContent(state = ChatHomePreview.waitingInQueue())
    }

    @Test
    fun chat_home_waiting_in_queue_dark() = snapshot(name = "waiting_in_queue", dark = true) {
        ChatHomeContent(state = ChatHomePreview.waitingInQueue())
    }

    @Test
    fun chat_home_hitl_confirm_readonly_light() = snapshot(name = "hitl_confirm_readonly", dark = false) {
        ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Readonly))
    }

    @Test
    fun chat_home_hitl_confirm_readonly_dark() = snapshot(name = "hitl_confirm_readonly", dark = true) {
        ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Readonly))
    }

    @Test
    fun chat_home_hitl_confirm_sensitive_light() = snapshot(name = "hitl_confirm_sensitive", dark = false) {
        ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Sensitive))
    }

    @Test
    fun chat_home_hitl_confirm_sensitive_dark() = snapshot(name = "hitl_confirm_sensitive", dark = true) {
        ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Sensitive))
    }

    @Test
    fun chat_home_hitl_confirm_destructive_light() = snapshot(name = "hitl_confirm_destructive", dark = false) {
        ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Destructive))
    }

    @Test
    fun chat_home_hitl_confirm_destructive_dark() = snapshot(name = "hitl_confirm_destructive", dark = true) {
        ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Destructive))
    }

    @Test
    fun chat_home_clarification_light() = snapshot(name = "clarification", dark = false) {
        ChatHomeContent(state = ChatHomePreview.clarification())
    }

    @Test
    fun chat_home_clarification_dark() = snapshot(name = "clarification", dark = true) {
        ChatHomeContent(state = ChatHomePreview.clarification())
    }

    @Test
    fun chat_home_interrupted_light() = snapshot(name = "interrupted", dark = false) {
        ChatHomeContent(state = ChatHomePreview.interrupted())
    }

    @Test
    fun chat_home_interrupted_dark() = snapshot(name = "interrupted", dark = true) {
        ChatHomeContent(state = ChatHomePreview.interrupted())
    }

    @Test
    fun chat_home_ceiling_pause_light() = snapshot(name = "ceiling_pause", dark = false) {
        ChatHomeContent(state = ChatHomePreview.ceilingPause())
    }

    @Test
    fun chat_home_ceiling_pause_dark() = snapshot(name = "ceiling_pause", dark = true) {
        ChatHomeContent(state = ChatHomePreview.ceilingPause())
    }

    @Test
    fun chat_home_error_light() = snapshot(name = "error", dark = false) {
        ChatHomeContent(state = ChatHomePreview.error())
    }

    @Test
    fun chat_home_error_dark() = snapshot(name = "error", dark = true) {
        ChatHomeContent(state = ChatHomePreview.error())
    }

    @Test
    fun chat_home_stopped_by_ceiling_light() = snapshot(name = "stopped_ceiling", dark = false) {
        ChatHomeContent(state = ChatHomePreview.stoppedByCeiling())
    }

    @Test
    fun chat_home_stopped_by_ceiling_dark() = snapshot(name = "stopped_ceiling", dark = true) {
        ChatHomeContent(state = ChatHomePreview.stoppedByCeiling())
    }

    @Test
    fun chat_home_stopped_housekeeping_light() = snapshot(name = "stopped_housekeeping", dark = false) {
        ChatHomeContent(state = ChatHomePreview.stoppedHousekeeping())
    }

    @Test
    fun chat_home_run_notice_light() = snapshot(name = "run_notice", dark = false) {
        ChatHomeContent(state = ChatHomePreview.approachingCeiling())
    }

    @Test
    fun chat_home_run_notice_dark() = snapshot(name = "run_notice", dark = true) {
        ChatHomeContent(state = ChatHomePreview.approachingCeiling())
    }

    @Test
    fun chat_home_run_notice_stuck_light() = snapshot(name = "run_notice_stuck", dark = false) {
        ChatHomeContent(state = ChatHomePreview.looksStuck())
    }

    @Test
    fun chat_home_run_notice_stuck_dark() = snapshot(name = "run_notice_stuck", dark = true) {
        ChatHomeContent(state = ChatHomePreview.looksStuck())
    }

    @Test
    fun chat_home_drawer_open_light() = snapshot(name = "drawer_open", dark = false) {
        ChatHomeContent(state = ChatHomePreview.drawerOpen())
    }

    @Test
    fun chat_home_drawer_open_dark() = snapshot(name = "drawer_open", dark = true) {
        ChatHomeContent(state = ChatHomePreview.drawerOpen())
    }

    @Test
    fun chat_home_drawer_row_menu_light() = snapshot(name = "drawer_row_menu", dark = false) {
        ChatHomeContent(state = ChatHomePreview.drawerOpen(openThreadMenuId = "t2"))
    }

    @Test
    fun chat_home_drawer_swipe_open_light() = snapshot(name = "drawer_swipe_open", dark = false) {
        ChatHomeContent(state = ChatHomePreview.drawerOpen(revealedThreadId = "t1"))
    }

    @Test
    fun chat_home_drawer_swipe_open_dark() = snapshot(name = "drawer_swipe_open", dark = true) {
        ChatHomeContent(state = ChatHomePreview.drawerOpen(revealedThreadId = "t1"))
    }

    @Test
    fun chat_home_drawer_with_archive_light() = snapshot(name = "drawer_with_archive", dark = false) {
        ChatHomeContent(state = ChatHomePreview.drawerOpen(archivedCount = 6))
    }

    @Test
    fun chat_home_archived_read_only_light() = snapshot(name = "archived_read_only", dark = false) {
        ChatHomeContent(state = ChatHomePreview.archivedReadOnly())
    }

    @Test
    fun chat_home_archived_read_only_dark() = snapshot(name = "archived_read_only", dark = true) {
        ChatHomeContent(state = ChatHomePreview.archivedReadOnly())
    }

    @Test
    fun chat_home_drawer_open_font_scale_2x_light() =
        snapshot(name = "drawer_open_font_scale_2x", dark = false, fontScale = 2f) {
            // At 200 % the row drops its status dot and gives the space to the
            // title; the trailing controls keep their targets.
            ChatHomeContent(state = ChatHomePreview.drawerOpen(archivedCount = 6))
        }

    @Test
    fun chat_home_console_expanded_light() = snapshot(name = "console_expanded", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleExpanded())
    }

    @Test
    fun chat_home_console_expanded_dark() = snapshot(name = "console_expanded", dark = true) {
        ChatHomeContent(state = ChatHomePreview.consoleExpanded())
    }

    @Test
    fun chat_home_idle_font_scale_2x_light() = snapshot(name = "idle_font_scale_2x", dark = false, fontScale = 2f) {
        ChatHomeContent(state = ChatHomePreview.idle())
    }

    @Test
    fun chat_home_hitl_destructive_font_scale_2x_light() =
        snapshot(name = "hitl_destructive_font_scale_2x", dark = false, fontScale = 2f) {
            ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Destructive))
        }

    /**
     * Wraps the content under the standard test rule, pins reduced-motion +
     * fontScale via [FixedKnotworkA11y] for determinism, and writes the PNG
     * to `src/test/snapshots/chat_home_<name>_<theme>.png`.
     */
    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            // Override LocalDensity so Material text actually renders at the
            // requested font scale — FixedKnotworkA11y only adjusts the
            // a11y-aware Knotwork primitives, not the underlying typography.
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) { content() }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/chat_home_${name}_$themeTag.png",
        )
    }
}
