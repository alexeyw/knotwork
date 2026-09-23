package app.knotwork.design.components.console

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
import app.knotwork.design.screens.chat.ChatHomeContent
import app.knotwork.design.screens.chat.ChatHomePreview
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baseline for the console entry strip in every status state.
 *
 * It lives in its own class because until now the strip had **no** visual
 * coverage at all: no `ChatHomeContent` fixture set `agentStatusLine`, so every
 * chat baseline rendered the screen without it. The control the closed-test
 * tester could not find was the one control the snapshots never showed — the
 * same shape as the inert magnifier that survived every visual review because
 * it sat outside the captured frame.
 *
 * Reduced motion is pinned like the rest of the suite, so the chevron angle and
 * the generating loader are deterministic.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ConsoleEntryStripSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chat_console_strip_idle_light() = snapshot(name = "idle", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.IDLE))
    }

    @Test
    fun chat_console_strip_idle_dark() = snapshot(name = "idle", dark = true) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.IDLE))
    }

    @Test
    fun chat_console_strip_generating() = snapshot(name = "generating", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.GENERATING))
    }

    /**
     * The overflow case: at 360 dp this line does not fit, so the baseline is
     * the record of WHICH end survives. The backend and the token count are the
     * two things that exist nowhere else on screen; the word `generating`
     * repeats the bubble directly above it.
     */
    @Test
    fun chat_console_strip_generating_long() = snapshot(name = "generating_long", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.GENERATING_LONG))
    }

    @Test
    fun chat_console_strip_preparing() = snapshot(name = "preparing", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.PREPARING))
    }

    @Test
    fun chat_console_strip_waiting_in_queue() = snapshot(name = "waiting_in_queue", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.WAITING_IN_QUEUE))
    }

    /**
     * The strip keeps the END of an overflowing line, so this status puts the word that
     * matters last: at 200 % only "…queued" survives, and that still says it. The first
     * wording ("waiting for another run to finish") read "…finish" here.
     */
    @Test
    fun chat_console_strip_waiting_in_queue_fontscale200() =
        snapshot(name = "waiting_in_queue_fontscale200", dark = false, fontScale = 2f) {
            ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.WAITING_IN_QUEUE))
        }

    @Test
    fun chat_console_strip_hitl() = snapshot(name = "hitl", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.HITL))
    }

    @Test
    fun chat_console_strip_clarification() = snapshot(name = "clarification", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.CLARIFICATION))
    }

    @Test
    fun chat_console_strip_error() = snapshot(name = "error", dark = false) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.ERROR))
    }

    @Test
    fun chat_console_strip_open_light() = snapshot(name = "open", dark = false) {
        ChatHomeContent(
            state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.GENERATING, consoleOpen = true),
        )
    }

    @Test
    fun chat_console_strip_open_dark() = snapshot(name = "open", dark = true) {
        ChatHomeContent(
            state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.GENERATING, consoleOpen = true),
        )
    }

    @Test
    fun chat_console_strip_idle_fontscale200() = snapshot(name = "idle_fontscale200", dark = false, fontScale = 2f) {
        ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.IDLE))
    }

    /** The longest status string, at the scale where the strip has to stay one line. */
    @Test
    fun chat_console_strip_preparing_fontscale200() =
        snapshot(name = "preparing_fontscale200", dark = false, fontScale = 2f) {
            ChatHomeContent(state = ChatHomePreview.consoleStrip(ChatHomePreview.StripStatus.PREPARING))
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
            filePath = "src/test/snapshots/chat_console_strip_${name}_$themeTag.png",
        )
    }
}
