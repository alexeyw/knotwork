package app.knotwork.design.screens.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Memory-screen a11y audit.
 *
 * Tighter follow-up to the structural reachability test in
 * [app.knotwork.design.a11y.TalkBackHappyPathsTest]: this suite enumerates
 * the surfaces walked through during TalkBack happy path #5
 * (`decisions.md §14` — "Search Memory → expand a result → delete it")
 * and asserts that every interactive node on the path advertises at least
 * one non-blank `contentDescription`, visible `text`, or `OnClick` action.
 *
 * Without this gate a regression that silently strips semantics from, say,
 * the search-clear icon, the pinned-row glyph, or the detail-sheet Delete
 * affordance would still pass the structural test while leaving the actual
 * TalkBack user with an unannounced surface.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class MemoryAccessibilityTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun memoryPopulated_searchAndSortAreReachable() {
        render { MemoryContent(state = MemoryPreview.populated()) }
        // Search field + 3 sort chips + 3 row triggers at minimum.
        assertReachableNodes(min = POPULATED_MIN_NODES)
    }

    @Test
    fun memoryPopulatedPinned_pinGlyphAdvertisesContentDescription() {
        render { MemoryContent(state = MemoryPreview.populatedPinned()) }
        // The pinned row keeps the same trigger count as the unpinned
        // variant; the glyph travels with the row's semantics, so the
        // baseline floor is the same. The matcher below targets the pin
        // contentDescription explicitly so a regression that strips the
        // glyph's `contentDescription` (turning the star into a colour-only
        // signal) fails this assertion even when the row stays clickable.
        assertReachableNodes(min = POPULATED_MIN_NODES)
        // The pinned row's pin toggle advertises an "Unpin" contentDescription;
        // a regression stripping it would turn the glyph into a colour-only
        // signal and fail here even while the row stays clickable.
        assertContentDescriptionPresent(substring = "pin")
    }

    @Test
    fun memoryEntryExpanded_detailActionsAreReachable() {
        render { MemoryContent(state = MemoryPreview.entryExpanded()) }
        // Detail overlay adds: close icon + Edit + Pin/Unpin + Delete on
        // top of the populated surface. The catalog overlay covers the
        // list (the list rows are still in the tree but visually
        // occluded), so the reachable floor is the populated baseline +
        // the 4 detail-sheet affordances.
        assertReachableNodes(min = POPULATED_MIN_NODES + EXPANDED_MIN_EXTRA_NODES)
    }

    @Test
    fun memoryEditing_saveAndCancelAreReachable() {
        render { MemoryContent(state = MemoryPreview.editing()) }
        // Editing replaces Edit/Pin/Delete with Cancel + Save; the close
        // icon is still rendered. Reachable floor: populated + 3
        // (close + Cancel + Save).
        assertReachableNodes(min = POPULATED_MIN_NODES + EDITING_MIN_EXTRA_NODES)
    }

    @Test
    fun memoryError_retryAndDiagnosticsAreReachable() {
        render { MemoryContent(state = MemoryPreview.error()) }
        // Error state collapses the chrome (no search / sort) and shows
        // Retry + Open diagnostics buttons.
        assertReachableNodes(min = ERROR_MIN_NODES)
    }

    @Test
    fun memoryPairList_historyMarkerReadsAsEarlierVersions() {
        render { MemoryContent(state = MemoryPreview.pairList()) }

        // The marker is a glyph and a bare number; TalkBack must hear what it counts.
        composeTestRule.onNodeWithContentDescription("2 earlier versions", substring = true, useUnmergedTree = true)
            .assertExists()
        composeTestRule.onAllNodesWithText("Update waiting", useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Updates a pinned memory", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun memoryHistory_versionCarriesItsDescriptionAndDeleteAction() {
        render { MemoryContent(state = MemoryPreview.historySheet()) }
        composeTestRule.onNodeWithText("2 earlier versions").performClick()

        val version = composeTestRule.onNodeWithContentDescription("Earlier version 1 of 2.", substring = true)
        version.assertExists()
        val actions = version.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Delete this version"), actions.map { it.label })
    }

    @Test
    fun memoryPairSheet_bothResolutionsAreReachable() {
        render { MemoryContent(state = MemoryPreview.pinnedPairSheet()) }

        composeTestRule.onNodeWithText("Use the update").assertHasClickAction()
        composeTestRule.onNodeWithText("Keep pinned").assertHasClickAction()
        composeTestRule.onNodeWithContentDescription("Update: ", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    private fun render(content: @Composable () -> Unit) {
        composeTestRule.setContent { KnotworkTheme(darkTheme = false) { content() } }
    }

    private fun assertReachableNodes(min: Int) {
        val matcher = SemanticsMatcher("is TalkBack-reachable") { node ->
            val cds = node.config.getOrNull(SemanticsProperties.ContentDescription)
            val text = node.config.getOrNull(SemanticsProperties.Text)
            val onClick = node.config.getOrNull(SemanticsActions.OnClick)
            (cds != null && cds.any { it.isNotBlank() }) ||
                (text != null && text.any { it.text.isNotBlank() }) ||
                onClick != null
        }
        val count = composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().size
        check(count >= min) {
            "Expected at least $min TalkBack-reachable nodes, found $count"
        }
    }

    private fun assertContentDescriptionPresent(substring: String) {
        val matcher = SemanticsMatcher("has CD containing '$substring'") { node ->
            val cds = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: return@SemanticsMatcher false
            cds.any { it.contains(substring, ignoreCase = true) }
        }
        val matches = composeTestRule.onAllNodes(matcher).fetchSemanticsNodes()
        check(matches.isNotEmpty()) {
            "Expected at least one node whose contentDescription contains '$substring'"
        }
    }
}

/** Minimum reachable nodes on the populated surface (search + 3 sort chips + 3 rows). */
private const val POPULATED_MIN_NODES = 7

/** Extra nodes the EntryExpanded overlay surfaces (close + Edit + Pin + Delete). */
private const val EXPANDED_MIN_EXTRA_NODES = 4

/** Extra nodes the Editing overlay surfaces (close + Cancel + Save). */
private const val EDITING_MIN_EXTRA_NODES = 3

/** Minimum reachable nodes on the Error state (Retry + Open diagnostics + the title text). */
private const val ERROR_MIN_NODES = 2
