package app.knotwork.design.components.pipelineeditor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * Roborazzi snapshot baseline for [PipelineEditorCatalogContent] in both
 * themes plus a reduced-motion variant that pins [FixedKnotworkA11y] so
 * the NodeCard running pulse renders deterministically.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h4000dp-xhdpi")
class PipelineEditorCatalogPageSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pipeline_editor_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    PipelineEditorCatalogContent()
                }
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/pipeline_editor_light.png",
        )
    }

    @Test
    fun pipeline_editor_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    PipelineEditorCatalogContent()
                }
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/pipeline_editor_dark.png",
        )
    }

    /**
     * The toolbar carrying unsaved work. Its own capture rather than a flag on
     * the page above, because the page renders the editor's *clean* state and
     * that is the one worth keeping stable — a second frame is cheaper than a
     * fixture that has to be read to know which state it pinned.
     */
    @Test
    fun editor_toolbar_unsaved_light() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = false) {
                EditorToolbar(
                    name = "Weekly digest",
                    onNameChange = {},
                    onNavigateUp = {},
                    onOverflow = {},
                    subtitle = "Editing · 4 nodes · 3 edges",
                    unsavedChanges = true,
                )
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/editor_toolbar_unsaved_light.png",
        )
    }

    @Test
    fun editor_toolbar_unsaved_dark() {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = true) {
                EditorToolbar(
                    name = "Weekly digest",
                    onNameChange = {},
                    onNavigateUp = {},
                    onOverflow = {},
                    subtitle = "Editing · 4 nodes · 3 edges",
                    unsavedChanges = true,
                )
            }
        }
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/editor_toolbar_unsaved_dark.png",
        )
    }
}
