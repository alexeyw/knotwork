package app.knotwork.design.screens.prompts

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
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baseline for `PromptLibraryContent`. Covers default (cards),
 * empty, and editor-sheet states in both themes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class PromptLibraryContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun prompts_default_light() = snapshot(name = "default", dark = false) {
        PromptLibraryContent(state = PromptLibraryPreview.default())
    }

    @Test
    fun prompts_default_dark() = snapshot(name = "default", dark = true) {
        PromptLibraryContent(state = PromptLibraryPreview.default())
    }

    @Test
    fun prompts_empty_light() = snapshot(name = "empty", dark = false) {
        PromptLibraryContent(state = PromptLibraryPreview.empty())
    }

    @Test
    fun prompts_empty_dark() = snapshot(name = "empty", dark = true) {
        PromptLibraryContent(state = PromptLibraryPreview.empty())
    }

    @Test
    fun prompts_empty_library_light() = snapshot(name = "empty_library", dark = false) {
        PromptLibraryContent(state = PromptLibraryPreview.emptyLibrary())
    }

    @Test
    fun prompts_empty_library_dark() = snapshot(name = "empty_library", dark = true) {
        PromptLibraryContent(state = PromptLibraryPreview.emptyLibrary())
    }

    @Test
    fun prompts_default_font_scale_200() = snapshot(name = "default_fontscale200", dark = false) {
        // The action cluster has to survive here intact — the drawn design
        // dropped the overflow at this scale, taking Export and Delete with
        // it. `PromptLibraryContentActionsTest` asserts that; this baseline
        // makes a regression visible.
        AtFontScale(scale = 2f) {
            PromptLibraryContent(state = PromptLibraryPreview.default())
        }
    }

    @Test
    fun prompts_editor_light() = snapshot(name = "editor", dark = false) {
        PromptEditorSheetBody(
            state = PromptLibraryPreview.editorState(),
            availableVariables = listOf("\$DATE", "\$DEVICE", "\$LANG", "\$USER", "\$TZ"),
            strings = PromptEditorStrings(),
            callbacks = noopPromptLibraryCallbacks(),
        )
    }

    /** Renders [content] at a forced font scale, leaving density untouched. */
    @Composable
    private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = base.density, fontScale = scale),
            content = content,
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
            filePath = "src/test/snapshots/prompts_${name}_$themeTag.png",
        )
    }
}

internal object PromptLibraryPreview {
    private val categories = listOf("DECOMPOSITION", "IF_CONDITION", "INTENT_ROUTER", "QUEUE_PROCESSOR")

    fun default(): PromptLibraryViewState = PromptLibraryViewState(
        visualState = PromptLibraryVisualState.Default,
        categories = categories,
        selectedCategory = "DECOMPOSITION",
        prompts = listOf(
            PromptRow(
                id = "snap-1",
                category = "DECOMPOSITION",
                name = "Decomposer",
                body = "You are a Task Decomposer. Break down the given complex task into a list of simpler, " +
                    "actionable subtasks. Output the result as a JSON array of strings. Today is \$DATE. " +
                    "Device locale: \$LANG.",
                usedByCount = 3,
            ),
            PromptRow(
                id = "snap-2",
                category = "DECOMPOSITION",
                name = "Steps · concise",
                body = "Split the user goal into 3–7 atomic steps. Each step must be independently " +
                    "executable by a tool or another LLM call. Return strictly JSON: {\"steps\": […]}.",
                usedByCount = 3,
            ),
            // A bundled row, so the read-only variant of the action cluster
            // (Preview · Duplicate · Export, no overflow) is in the baseline.
            PromptRow(
                id = "snap-3",
                category = "DECOMPOSITION",
                name = "Report writer",
                body = "Draft a structured report from \$INPUT, with sections and a closing summary.",
                usedByCount = 3,
                isReadOnly = true,
            ),
        ),
        availableVariables = listOf("\$DATE", "\$DEVICE", "\$LANG", "\$USER", "\$TZ"),
        subtitle = "5 categories · 24 prompts",
    )

    /**
     * The library-wide empty state — nothing saved at all. Distinct from
     * [empty], which is a category with no prompts inside a library that has
     * some.
     */
    fun emptyLibrary(): PromptLibraryViewState = PromptLibraryViewState(
        visualState = PromptLibraryVisualState.Empty,
        categories = categories,
        selectedCategory = "DECOMPOSITION",
        prompts = emptyList(),
        subtitle = "9 categories · 0 prompts",
    )

    fun empty(): PromptLibraryViewState = PromptLibraryViewState(
        visualState = PromptLibraryVisualState.Default,
        categories = categories,
        selectedCategory = "QUEUE_PROCESSOR",
        prompts = emptyList(),
        subtitle = "5 categories · 24 prompts",
    )

    fun editorState(): PromptEditorState = PromptEditorState(
        id = "snap-1",
        name = "Decomposer",
        category = "DECOMPOSITION",
        body = "You are a Task Decomposer. Break down the given complex task into a list of simpler, actionable " +
            "subtasks. Output the result as a JSON array of strings. Today is \$DATE.",
        usedByCount = 3,
    )
}
