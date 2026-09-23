package app.knotwork.design.screens.skills

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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

/**
 * Roborazzi snapshot baselines for the Skill Library surfaces — the list
 * (`SkillLibraryContent`), the full-screen editor (`SkillEditorContent`) and
 * the delete dialog (`SkillDeleteDialogContent`) across their documented
 * states in both themes.
 *
 * Reduced-motion is pinned via [FixedKnotworkA11y] so no looping animation
 * randomises a snapshot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class SkillLibraryContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ── List ─────────────────────────────────────────────────────────────

    @Test
    fun list_bundled_light() = snapshot(name = "list_bundled", dark = false) {
        SkillLibraryContent(state = SkillLibraryPreview.bundledPopulated())
    }

    @Test
    fun list_bundled_dark() = snapshot(name = "list_bundled", dark = true) {
        SkillLibraryContent(state = SkillLibraryPreview.bundledPopulated())
    }

    @Test
    fun list_mine_light() = snapshot(name = "list_mine", dark = false) {
        SkillLibraryContent(state = SkillLibraryPreview.minePopulated())
    }

    @Test
    fun list_mine_empty_light() = snapshot(name = "list_mine_empty", dark = false) {
        SkillLibraryContent(state = SkillLibraryPreview.mineEmpty())
    }

    @Test
    fun list_loading_light() = snapshot(name = "list_loading", dark = false) {
        SkillLibraryContent(state = SkillLibraryPreview.loading())
    }

    @Test
    fun list_error_light() = snapshot(name = "list_error", dark = false) {
        SkillLibraryContent(state = SkillLibraryPreview.error())
    }

    @Test
    fun list_error_dark() = snapshot(name = "list_error", dark = true) {
        SkillLibraryContent(state = SkillLibraryPreview.error())
    }

    // ── Editor ───────────────────────────────────────────────────────────

    @Test
    fun editor_create_light() = snapshot(name = "editor_create", dark = false) {
        SkillEditorContent(state = SkillLibraryPreview.editorCreate())
    }

    @Test
    fun editor_restrict_light() = snapshot(name = "editor_restrict", dark = false) {
        SkillEditorContent(state = SkillLibraryPreview.editorEditRestrict())
    }

    @Test
    fun editor_restrict_dark() = snapshot(name = "editor_restrict", dark = true) {
        SkillEditorContent(state = SkillLibraryPreview.editorEditRestrict())
    }

    @Test
    fun editor_no_tools_light() = snapshot(name = "editor_no_tools", dark = false) {
        SkillEditorContent(state = SkillLibraryPreview.editorNoTools())
    }

    @Test
    fun editor_invalid_light() = snapshot(name = "editor_invalid", dark = false) {
        SkillEditorContent(state = SkillLibraryPreview.editorInvalid())
    }

    @Test
    fun editor_view_light() = snapshot(name = "editor_view", dark = false) {
        SkillEditorContent(state = SkillLibraryPreview.editorView())
    }

    // ── Delete dialog ────────────────────────────────────────────────────

    @Test
    fun delete_none_light() = snapshot(name = "delete_none", dark = false) {
        DialogHost { SkillDeleteDialogContent(state = SkillLibraryPreview.deleteNoDependents()) }
    }

    @Test
    fun delete_many_light() = snapshot(name = "delete_many", dark = false) {
        DialogHost { SkillDeleteDialogContent(state = SkillLibraryPreview.deleteManyDependents()) }
    }

    @Test
    fun delete_many_dark() = snapshot(name = "delete_many", dark = true) {
        DialogHost { SkillDeleteDialogContent(state = SkillLibraryPreview.deleteManyDependents()) }
    }

    /** Pads the dialog card so its drop-shadow and corners fit in the capture. */
    @Composable
    private fun DialogHost(content: @Composable () -> Unit) {
        Box(modifier = Modifier.padding(20.dp)) { content() }
    }

    /**
     * Wraps the content under the standard test rule, pins reduced-motion so
     * looping animations don't randomise the snapshot, and writes the PNG to
     * `src/test/snapshots/skill_<name>_<theme>.png`.
     */
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
            filePath = "src/test/snapshots/skill_${name}_$themeTag.png",
        )
    }
}
