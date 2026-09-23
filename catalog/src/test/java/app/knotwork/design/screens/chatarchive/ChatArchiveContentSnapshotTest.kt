package app.knotwork.design.screens.chatarchive

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
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the chat-archive surface across its documented
 * states in both themes, plus the two font-scale-200 % layouts where the row
 * sheds its decoration.
 *
 * Reduced-motion is pinned via [FixedKnotworkA11y] so the swipe settle never
 * randomises a capture.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ChatArchiveContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun populated_light() = snapshot(name = "populated", dark = false) {
        ChatArchiveContent(state = ChatArchivePreview.populated())
    }

    @Test
    fun populated_dark() = snapshot(name = "populated", dark = true) {
        ChatArchiveContent(state = ChatArchivePreview.populated())
    }

    @Test
    fun swipe_open_light() = snapshot(name = "swipe_open", dark = false) {
        ChatArchiveContent(state = ChatArchivePreview.swipeOpen())
    }

    @Test
    fun swipe_open_dark() = snapshot(name = "swipe_open", dark = true) {
        ChatArchiveContent(state = ChatArchivePreview.swipeOpen())
    }

    @Test
    fun row_menu_light() = snapshot(name = "row_menu", dark = false) {
        ChatArchiveContent(state = ChatArchivePreview.rowMenu())
    }

    @Test
    fun row_menu_dark() = snapshot(name = "row_menu", dark = true) {
        ChatArchiveContent(state = ChatArchivePreview.rowMenu())
    }

    @Test
    fun delete_confirm_light() = snapshot(name = "delete_confirm", dark = false) {
        ChatArchiveContent(state = ChatArchivePreview.deleteConfirm())
    }

    @Test
    fun delete_confirm_dark() = snapshot(name = "delete_confirm", dark = true) {
        ChatArchiveContent(state = ChatArchivePreview.deleteConfirm())
    }

    @Test
    fun empty_light() = snapshot(name = "empty", dark = false) {
        ChatArchiveContent(state = ChatArchivePreview.empty())
    }

    @Test
    fun empty_dark() = snapshot(name = "empty", dark = true) {
        ChatArchiveContent(state = ChatArchivePreview.empty())
    }

    @Test
    fun loading_light() = snapshot(name = "loading", dark = false) {
        ChatArchiveContent(state = ChatArchivePreview.loading())
    }

    @Test
    fun loading_dark() = snapshot(name = "loading", dark = true) {
        ChatArchiveContent(state = ChatArchivePreview.loading())
    }

    @Test
    fun error_light() = snapshot(name = "error", dark = false) {
        ChatArchiveContent(state = ChatArchivePreview.error())
    }

    @Test
    fun error_dark() = snapshot(name = "error", dark = true) {
        ChatArchiveContent(state = ChatArchivePreview.error())
    }

    // ── Font-scale 200 % ─────────────────────────────────────────────────
    // The leading archive tile is gone and Restore is icon-only; the title
    // takes both gains. Captured so a regression that squeezes the title
    // instead of the decoration shows up as a diff.

    @Test
    fun populated_large_font_light() =
        snapshot(name = "populated_large_font", dark = false, fontScale = LARGE_FONT_SCALE) {
            ChatArchiveContent(state = ChatArchivePreview.populated())
        }

    @Test
    fun empty_large_font_light() = snapshot(name = "empty_large_font", dark = false, fontScale = LARGE_FONT_SCALE) {
        ChatArchiveContent(state = ChatArchivePreview.empty())
    }

    /**
     * Renders [content] under the standard test rule with reduced-motion and a
     * fixed font scale pinned, then writes the PNG to
     * `src/test/snapshots/chat_archive_<name>_<theme>.png`.
     */
    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // The a11y override goes *inside* KnotworkTheme: the theme provides
            // `LocalKnotworkA11y` itself, so an outer provider would be shadowed
            // and the font-scale branches would never run under test.
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/chat_archive_${name}_$themeTag.png",
        )
    }

    private companion object {
        /** The "Largest" system text-size preset every row must survive. */
        const val LARGE_FONT_SCALE = 2.0f
    }
}
