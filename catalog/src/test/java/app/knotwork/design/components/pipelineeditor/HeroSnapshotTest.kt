package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
 * Roborazzi baseline of the editor's node-card surface at the canonical
 * 1080 × 2400 resolution, in both themes.
 *
 * The catalog harness [PipelineEditorCatalogContent] is intentionally long
 * (it exercises every editor component in a scrollable column for
 * regression diffs); this baseline clips it to a 360 × 800 dp viewport via a
 * `requiredSize + clipToBounds` wrapper so the top of the harness — the
 * per-NodeType card grid — fills the entire frame, which makes a theme or
 * token regression on the node cards obvious in the diff.
 *
 * **This baseline is not copied anywhere.** It used to be the README's
 * "Pipeline editor" hero shot; that slot now holds a capture from a phone
 * (`docs/images/hero-pipeline-canvas{,-dark}.jpg`), because a stack of node
 * cards shows the component catalogue rather than a pipeline. The canvas
 * itself — edges, pan/zoom, minimap — lives in `app/presentation` and has no
 * catalog counterpart to render, so do **not** re-point the README at this
 * file: a `recordRoborazzi` run would then silently overwrite the phone
 * capture with a card stack again.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xxhdpi")
class HeroSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun hero_pipeline_editor_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    HeroFrame { PipelineEditorCatalogContent() }
                }
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_pipeline_editor_light.png",
        )
    }

    @Test
    fun hero_pipeline_editor_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true),
                ) {
                    HeroFrame { PipelineEditorCatalogContent() }
                }
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/hero_pipeline_editor_dark.png",
        )
    }

    /**
     * Forces the captured root to the canonical 360 × 800 dp viewport and
     * clips the (taller) catalog content to that frame.
     */
    @androidx.compose.runtime.Composable
    private fun HeroFrame(content: @androidx.compose.runtime.Composable () -> Unit) {
        Box(
            modifier = Modifier
                .requiredSize(width = 360.dp, height = 800.dp)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            content()
        }
    }
}
