package app.knotwork.design.store

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.screens.chat.ChatHomeContent
import app.knotwork.design.screens.chat.ChatHomePreview
import app.knotwork.design.screens.pipelines.PipelineLibraryContent
import app.knotwork.design.screens.pipelines.PipelineLibraryPreview
import app.knotwork.design.screens.tools.ToolsContent
import app.knotwork.design.screens.tools.ToolsPreview
import app.knotwork.design.screens.triggers.TriggersContent
import app.knotwork.design.screens.triggers.TriggersPreview
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the **app-store listing** screenshots.
 *
 * Separate from the README `HeroSnapshotTest`s for one reason that is easy to
 * miss and expensive to discover at submission time: Google Play refuses a
 * screenshot whose longer side is more than twice its shorter side, and the
 * hero baselines are 1080 × 2400 (2400 > 2 × 1080). These render at
 * `w360dp-h720dp-xxhdpi` = **1080 × 2160**, exactly the 2:1 boundary, which
 * satisfies Play and is well inside what F-Droid accepts.
 *
 * Light theme only — a store carousel that alternates themes reads as
 * inconsistency rather than as a feature, and the dark variants stay covered
 * by the hero baselines.
 *
 * The captures are copied into `fastlane/metadata/android/<locale>/images/`
 * `phoneScreenshots/`, which is the single metadata source both Play and
 * F-Droid read. Nothing verifies that copy step, so re-record **and re-copy**
 * together — see `docs/release.md` § *Store listing metadata*.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h720dp-xxhdpi")
class StoreScreenshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun store_phone_1_chat() {
        capture("store_phone_1_chat") { ChatHomeContent(state = ChatHomePreview.idle()) }
    }

    @Test
    fun store_phone_3_confirmation() {
        capture("store_phone_3_confirmation") {
            ChatHomeContent(state = ChatHomePreview.hitlConfirm(risk = Risk.Sensitive))
        }
    }

    @Test
    fun store_phone_4_pipelines() {
        capture("store_phone_4_pipelines") {
            PipelineLibraryContent(state = PipelineLibraryPreview.populated())
        }
    }

    @Test
    fun store_phone_5_tools() {
        capture("store_phone_5_tools") { ToolsContent(state = ToolsPreview.defaultExpanded()) }
    }

    @Test
    fun store_phone_6_triggers() {
        capture("store_phone_6_triggers") { TriggersContent(state = TriggersPreview.populated()) }
    }

    /**
     * Renders [content] in the light Knotwork theme with animation suppressed
     * and writes the frame to the named baseline.
     *
     * `reducedMotion` is not cosmetic here: an in-flight animation makes the
     * capture depend on when the frame was taken, which turns a verify run into
     * a coin toss.
     *
     * @param name Baseline file name without the extension; slot 2 is reserved
     *   for the phone capture of the editor canvas, which has no catalog
     *   counterpart to render.
     * @param content The screen to capture.
     */
    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    content()
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$name.png",
        )
    }
}
