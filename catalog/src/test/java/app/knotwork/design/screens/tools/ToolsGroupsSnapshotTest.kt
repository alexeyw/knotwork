package app.knotwork.design.screens.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * Roborazzi baseline for the collapsible tool groups.
 *
 * Collapse is view state owned by `ToolsContent`, so these frames are reached
 * the way a user reaches them — by tapping the header — rather than by handing
 * the surface a pre-folded state it could not have produced itself. That also
 * makes each baseline a small interaction test: a header that stopped toggling
 * would fail here, not just look different.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ToolsGroupsSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Both-groups-expanded is the default surface, already pinned byte-for-byte
    // by `tools_default_{light,dark}` in `ToolsContentSnapshotTest`. The handoff
    // named it `tools_groups_expanded_*`; recording it under a second name
    // produced two identical PNGs and a second thing to re-record on every
    // change, so the existing baseline keeps the job.

    @Test
    fun tools_group_builtin_collapsed_light() = snapshot(
        name = "group_builtin_collapsed",
        dark = false,
        collapse = listOf(BUILT_IN_HEADER),
    ) {
        ToolsContent(state = ToolsPreview.default())
    }

    /**
     * The rule a collapsed group has to obey: folding the MCP servers away must
     * not fold away the fact that one of them is down.
     */
    @Test
    fun tools_group_mcp_collapsed_warning_light() = snapshot(
        name = "group_mcp_collapsed_warning",
        dark = false,
        collapse = listOf(MCP_HEADER),
    ) {
        ToolsContent(state = ToolsPreview.defaultDisconnected())
    }

    @Test
    fun tools_groups_both_collapsed_light() = snapshot(
        name = "groups_both_collapsed",
        dark = false,
        collapse = listOf(BUILT_IN_HEADER, MCP_HEADER),
    ) {
        ToolsContent(state = ToolsPreview.default())
    }

    @Test
    fun tools_groups_both_collapsed_dark() = snapshot(
        name = "groups_both_collapsed",
        dark = true,
        collapse = listOf(BUILT_IN_HEADER, MCP_HEADER),
    ) {
        ToolsContent(state = ToolsPreview.default())
    }

    /**
     * No servers yet: the empty state lives *inside* the group that is actually
     * empty, next to a built-in group that is never empty — which is why a
     * full-screen "no tools" would be a lie here.
     */
    @Test
    fun tools_mcp_empty_cta_light() = snapshot(name = "mcp_empty_cta", dark = false) {
        ToolsContent(state = ToolsPreview.noMcpServers())
    }

    @Test
    fun tools_mcp_empty_cta_dark() = snapshot(name = "mcp_empty_cta", dark = true) {
        ToolsContent(state = ToolsPreview.noMcpServers())
    }

    @Test
    fun tools_groups_fontscale200_light() = snapshot(name = "groups_fontscale200", dark = false, fontScale = 2f) {
        ToolsContent(state = ToolsPreview.default())
    }

    /**
     * @param collapse header titles to tap before capturing, in order — the
     *        only way to reach a folded group, since the fold is the surface's
     *        own state.
     */
    private fun snapshot(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        collapse: List<String> = emptyList(),
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) { content() }
            }
        }
        collapse.forEach { header -> composeTestRule.onNodeWithText(header).performClick() }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/tools_${name}_$themeTag.png",
        )
    }

    private companion object {
        const val BUILT_IN_HEADER = "BUILT-IN TOOLS"
        const val MCP_HEADER = "MCP SERVERS"
    }
}
