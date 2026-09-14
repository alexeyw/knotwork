package app.knotwork.design.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.screens.chat.ChatHomeContent
import app.knotwork.design.screens.chat.ChatHomePreview
import app.knotwork.design.screens.memory.MemoryContent
import app.knotwork.design.screens.memory.MemoryPreview
import app.knotwork.design.screens.pipelines.PipelineLibraryContent
import app.knotwork.design.screens.pipelines.PipelineLibraryPreview
import app.knotwork.design.screens.tools.ToolDetailContent
import app.knotwork.design.screens.tools.ToolsContent
import app.knotwork.design.screens.tools.ToolsPreview
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Accessibility matrix baseline.
 *
 * Locks Roborazzi snapshots for the heaviest layouts at `fontScale = 2.0`
 * ("Largest" preset) and at `reducedMotion = true` so the project has a
 * canonical visual proof that the design system honours
 * `decisions.md §14`:
 *  - **Dynamic type 200 %**: list rows expand vertically, chat bubbles
 *    wrap, `NodeCard` titles ellipsise; nothing clips.
 *  - **Reduced motion**: looping animations (chat-generating dots,
 *    `KnotworkLoader`) collapse to a deterministic steady state.
 *
 * One representative state per screen is captured. Adding more variants
 * here would just expand the baseline-PNG footprint without catching
 * additional layout regressions — the per-screen snapshot suites cover
 * the rest of the state matrix at default font scale.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class A11yMatrixSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chat_home_idle_font_scale_2() = a11ySnapshot(
        name = "chat_home_idle",
        fontScale = LARGEST_FONT_SCALE,
    ) {
        ChatHomeContent(state = ChatHomePreview.idle())
    }

    @Test
    fun pipeline_library_populated_font_scale_2() = a11ySnapshot(
        name = "pipeline_library_populated",
        fontScale = LARGEST_FONT_SCALE,
    ) {
        PipelineLibraryContent(state = PipelineLibraryPreview.populated())
    }

    @Test
    fun tools_default_font_scale_2() = a11ySnapshot(
        name = "tools_default",
        fontScale = LARGEST_FONT_SCALE,
    ) {
        ToolsContent(state = ToolsPreview.default())
    }

    @Test
    fun tool_detail_default_font_scale_2() = a11ySnapshot(
        name = "tool_detail_default",
        fontScale = LARGEST_FONT_SCALE,
    ) {
        // Locks the `ToolDetailScreen` 200 %-fontScale frame so the
        // `Modifier.horizontalScroll` schema-preview gate keeps long
        // JSON-Schema lines scrollable instead of
        // wrapping. Captures the layout proof for the spec rule in
        // `screens/README.md §C4 ToolDetailScreen`.
        ToolDetailContent(state = ToolsPreview.toolDetailDefault())
    }

    @Test
    fun memory_populated_font_scale_2() = a11ySnapshot(
        name = "memory_populated",
        fontScale = LARGEST_FONT_SCALE,
    ) {
        MemoryContent(state = MemoryPreview.populated())
    }

    @Test
    fun chat_home_generating_reduced_motion() = a11ySnapshot(
        name = "chat_home_generating_reduced",
        fontScale = 1f,
    ) {
        // The generating state's looping `KnotworkLoader` dots are the
        // only thing that varies under reduced-motion in this suite —
        // the rest of the chat surface is identical to `idle`. This
        // snapshot proves the loader collapses to its steady frame
        // (all three dots at full alpha) rather than strobing.
        ChatHomeContent(state = ChatHomePreview.generating())
    }

    /**
     * Wraps [content] under a [KnotworkTheme] with the production a11y
     * primitive replaced by [FixedKnotworkA11y] (always reduced-motion
     * for snapshot stability) and a [LocalDensity] override that pins
     * `fontScale` to the supplied value so `sp`-sized text reflows.
     *
     * The captured PNG lands under
     * `src/test/snapshots/a11y_<name>_fs<scale10>.png` — the scale
     * tag uses tenths (e.g. `fs20` for 2.0) so the filename is filesystem-
     * safe and sorts naturally.
     */
    private fun a11ySnapshot(name: String, fontScale: Float, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(
                        reducedMotion = true,
                        fontScale = fontScale,
                    ),
                    LocalDensity provides Density(
                        density = baseDensity.density,
                        fontScale = fontScale,
                    ),
                ) { content() }
            }
        }
        val scaleTag = "fs${(fontScale * SCALE_FILENAME_FACTOR).toInt()}"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/a11y_${name}_$scaleTag.png",
        )
    }

    private companion object {
        /** Android system "Largest" font-size preset. */
        const val LARGEST_FONT_SCALE = 2f

        /** Filename tag multiplier — `fs20` keeps the suffix integer-only. */
        const val SCALE_FILENAME_FACTOR = 10
    }
}
