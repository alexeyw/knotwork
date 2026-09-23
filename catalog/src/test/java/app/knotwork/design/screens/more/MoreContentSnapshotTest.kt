package app.knotwork.design.screens.more

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
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class MoreContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun more_sections_light() = snapshot(name = "sections", dark = false) {
        MoreContent(state = MorePreview.sections())
    }

    @Test
    fun more_sections_dark() = snapshot(name = "sections", dark = true) {
        MoreContent(state = MorePreview.sections())
    }

    /** Twelve rows plus four headings is where a sectioned list stops fitting. */
    @Test
    fun more_sections_fontscale200() = snapshot(name = "sections_fontscale200", dark = false, fontScale = 2f) {
        MoreContent(state = MorePreview.sections())
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
            filePath = "src/test/snapshots/more_${name}_$themeTag.png",
        )
    }
}

/**
 * Fixture for the More tab.
 *
 * Deliberately the **full twelve rows in their four sections**, with the same
 * glyphs the app ships. The previous fixture carried seven rows and Material
 * icons, so the baseline never showed the screen the closed-test tester was
 * actually looking at — the one where Triggers took two hours to find.
 */
internal object MorePreview {
    fun sections(): MoreViewState = MoreViewState(
        sections = listOf(
            MoreSection(
                id = "automation",
                title = "AUTOMATION",
                rows = listOf(
                    MoreRow(
                        id = "triggers",
                        title = "Triggers",
                        subtitle = "3 active · last fired 14:02",
                        icon = AppIcons.Trigger,
                        onClick = {},
                    ),
                    MoreRow(
                        id = "library",
                        title = "Library",
                        subtitle = "Pipeline presets · 6 saved",
                        icon = AppIcons.Bookmark,
                        onClick = {},
                    ),
                    MoreRow(
                        id = "tasks",
                        title = "Tasks",
                        subtitle = "2 running · 4 queued",
                        icon = AppIcons.History,
                        badge = 2,
                        onClick = {},
                    ),
                ),
            ),
            MoreSection(
                id = "content",
                title = "YOUR CONTENT",
                rows = listOf(
                    MoreRow(
                        id = "memory",
                        title = "Memory",
                        subtitle = "1 248 chunks · 14.2 MB",
                        icon = AppIcons.Brain,
                        onClick = {},
                    ),
                    MoreRow(
                        id = "files",
                        title = "Files",
                        subtitle = "Agent workspace · 7 files · 1.5 MB",
                        icon = AppIcons.Folder,
                        onClick = {},
                    ),
                    // Zero, and in the subtitle — the case a badge would have
                    // made look broken.
                    MoreRow(
                        id = "archive",
                        title = "Archive",
                        subtitle = "0 archived chats",
                        icon = AppIcons.Archive,
                        onClick = {},
                    ),
                ),
            ),
            MoreSection(
                id = "blocks",
                title = "BUILDING BLOCKS",
                rows = listOf(
                    MoreRow(
                        id = "prompts",
                        title = "Prompts",
                        subtitle = "8 categories · 24 prompts",
                        icon = AppIcons.Sliders,
                        onClick = {},
                    ),
                    MoreRow(
                        id = "skills",
                        title = "Skills",
                        subtitle = "4 installed · 1 needs a key",
                        icon = AppIcons.Skill,
                        onClick = {},
                    ),
                    MoreRow(
                        id = "models",
                        title = "Models",
                        subtitle = "gemma-4-E2B · active",
                        icon = AppIcons.Ram,
                        onClick = {},
                    ),
                ),
            ),
            MoreSection(
                id = "app",
                title = "APP",
                rows = listOf(
                    MoreRow(
                        id = "settings",
                        title = "Settings",
                        subtitle = "System prompt · models · keys",
                        icon = AppIcons.Cog,
                        onClick = {},
                    ),
                    MoreRow(
                        id = "metrics",
                        title = "Live metrics",
                        subtitle = "tok/s · latency · battery",
                        icon = AppIcons.Bolt,
                        onClick = {},
                    ),
                    MoreRow(
                        id = "about",
                        title = "About",
                        subtitle = "v0.8.0 · build 2026.08.21",
                        icon = AppIcons.Spark,
                        onClick = {},
                    ),
                ),
            ),
        ),
        networkStatus = "on-device · no network calls in last 14 m",
        networkStatusOk = true,
    )
}
