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
import app.knotwork.design.screens.chat.ChatHomeRunPreview.Check
import app.knotwork.design.screens.chat.ChatHomeRunPreview.Header
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the check of a run: its confirmation, the sheet while
 * calls are repeated, and every ending — all matched, mixed with cloud calls,
 * diverged, nothing verifiable, cancelled, the model failing to load — over the
 * console, in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class VerificationSheetSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    /** The console with its strip closed and the check's sheet at [stage] over it. */
    private fun sheet(stage: Check): ChatHomeViewState = ChatHomeRunPreview.console(expanded = false)
        .withRun(ChatHomeRunState(verification = ChatHomeRunPreview.verification(stage)))

    @Test
    fun verify_confirm_light() = snapshot(name = "verify_confirm", dark = false) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console().withRun(
                ChatHomeRunState(verifyConfirm = ChatHomeRunPreview.verifyConfirm()),
            ),
        )
    }

    @Test
    fun verify_confirm_dark() = snapshot(name = "verify_confirm", dark = true) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console().withRun(
                ChatHomeRunState(verifyConfirm = ChatHomeRunPreview.verifyConfirm()),
            ),
        )
    }

    @Test
    fun verify_confirm_fontscale200_light() =
        snapshot(name = "verify_confirm_fontscale200", dark = false, fontScale = 2f) {
            ChatHomeContent(
                state = ChatHomeRunPreview.console().withRun(
                    ChatHomeRunState(verifyConfirm = ChatHomeRunPreview.verifyConfirm()),
                ),
            )
        }

    @Test
    fun verify_confirm_fontscale200_dark() =
        snapshot(name = "verify_confirm_fontscale200", dark = true, fontScale = 2f) {
            ChatHomeContent(
                state = ChatHomeRunPreview.console().withRun(
                    ChatHomeRunState(verifyConfirm = ChatHomeRunPreview.verifyConfirm()),
                ),
            )
        }

    @Test
    fun verify_confirm_mixed_light() = snapshot(name = "verify_confirm_mixed", dark = false) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console(
                Header.MIXED,
            ).withRun(ChatHomeRunState(verifyConfirm = ChatHomeRunPreview.verifyConfirm(mixed = true))),
        )
    }

    @Test
    fun verify_confirm_mixed_dark() = snapshot(name = "verify_confirm_mixed", dark = true) {
        ChatHomeContent(
            state = ChatHomeRunPreview.console(
                Header.MIXED,
            ).withRun(ChatHomeRunState(verifyConfirm = ChatHomeRunPreview.verifyConfirm(mixed = true))),
        )
    }

    @Test
    fun verify_progress_light() = snapshot(name = "verify_progress", dark = false) {
        ChatHomeContent(state = sheet(Check.PROGRESS))
    }

    @Test
    fun verify_progress_dark() = snapshot(name = "verify_progress", dark = true) {
        ChatHomeContent(state = sheet(Check.PROGRESS))
    }

    @Test
    fun verify_progress_fontscale200_light() =
        snapshot(name = "verify_progress_fontscale200", dark = false, fontScale = 2f) {
            ChatHomeContent(state = sheet(Check.PROGRESS))
        }

    @Test
    fun verify_progress_fontscale200_dark() =
        snapshot(name = "verify_progress_fontscale200", dark = true, fontScale = 2f) {
            ChatHomeContent(state = sheet(Check.PROGRESS))
        }

    @Test
    fun verify_result_all_matched_light() = snapshot(name = "verify_result_all_matched", dark = false) {
        ChatHomeContent(state = sheet(Check.ALL_MATCHED))
    }

    @Test
    fun verify_result_all_matched_dark() = snapshot(name = "verify_result_all_matched", dark = true) {
        ChatHomeContent(state = sheet(Check.ALL_MATCHED))
    }

    @Test
    fun verify_result_mixed_light() = snapshot(name = "verify_result_mixed", dark = false) {
        ChatHomeContent(state = sheet(Check.MIXED))
    }

    @Test
    fun verify_result_mixed_dark() = snapshot(name = "verify_result_mixed", dark = true) {
        ChatHomeContent(state = sheet(Check.MIXED))
    }

    @Test
    fun verify_result_diverged_light() = snapshot(name = "verify_result_diverged", dark = false) {
        ChatHomeContent(state = sheet(Check.DIVERGED))
    }

    @Test
    fun verify_result_diverged_dark() = snapshot(name = "verify_result_diverged", dark = true) {
        ChatHomeContent(state = sheet(Check.DIVERGED))
    }

    @Test
    fun verify_result_diverged_fontscale200_light() =
        snapshot(name = "verify_result_diverged_fontscale200", dark = false, fontScale = 2f) {
            ChatHomeContent(state = sheet(Check.DIVERGED))
        }

    @Test
    fun verify_result_diverged_fontscale200_dark() =
        snapshot(name = "verify_result_diverged_fontscale200", dark = true, fontScale = 2f) {
            ChatHomeContent(state = sheet(Check.DIVERGED))
        }

    @Test
    fun verify_result_nothing_verifiable_light() = snapshot(name = "verify_result_nothing_verifiable", dark = false) {
        ChatHomeContent(state = sheet(Check.NOTHING_VERIFIABLE))
    }

    @Test
    fun verify_result_nothing_verifiable_dark() = snapshot(name = "verify_result_nothing_verifiable", dark = true) {
        ChatHomeContent(state = sheet(Check.NOTHING_VERIFIABLE))
    }

    @Test
    fun verify_cancelled_light() = snapshot(name = "verify_cancelled", dark = false) {
        ChatHomeContent(state = sheet(Check.CANCELLED))
    }

    @Test
    fun verify_cancelled_dark() = snapshot(name = "verify_cancelled", dark = true) {
        ChatHomeContent(state = sheet(Check.CANCELLED))
    }

    @Test
    fun verify_failed_load_light() = snapshot(name = "verify_failed_load", dark = false) {
        ChatHomeContent(state = sheet(Check.FAILED_LOAD))
    }

    @Test
    fun verify_failed_load_dark() = snapshot(name = "verify_failed_load", dark = true) {
        ChatHomeContent(state = sheet(Check.FAILED_LOAD))
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
