package app.knotwork.android.presentation.ui.pipeline.editor

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.presentation.ui.pipeline.editor.sheet.NodeTypePickerSheet
import app.knotwork.design.components.pipelineeditor.NODE_TYPE_PICKER_ROW_TEST_TAG_PREFIX
import app.knotwork.design.components.pipelineeditor.NODE_TYPE_PICKER_SEARCH_TEST_TAG
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The editor's "Add node" sheet, on a device, where its `ModalBottomSheet`
 * lays out — the catalog's JVM tests can only photograph the body.
 *
 * The expected types come from the domain enum, not from a hand-written list:
 * the radial menu this replaces was checked against a copy of its own labels,
 * and that copy covered twelve types of fourteen without anyone noticing.
 */
class PipelineEditorNodeTypePickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val pickedTypes = mutableListOf<NodeType>()
    private val picked: NodeType? get() = pickedTypes.firstOrNull()
    private var dismissed = 0

    private fun render() {
        composeTestRule.setContent {
            KnotworkTheme {
                NodeTypePickerSheet(onPick = { pickedTypes += it }, onDismiss = { dismissed++ })
            }
        }
    }

    private fun rowTag(type: NodeType) = NODE_TYPE_PICKER_ROW_TEST_TAG_PREFIX + type.name

    @Test
    fun nodeTypePicker_listsEveryDomainNodeType() {
        render()

        NodeType.entries.forEach { type ->
            composeTestRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(rowTag(type)))
            composeTestRule.onNodeWithTag(rowTag(type)).assertExists()
        }
    }

    @Test
    fun nodeTypePicker_tapOnRow_hidesThenReportsTheDomainType() {
        render()

        composeTestRule.onNodeWithTag(rowTag(NodeType.LITE_RT)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { picked != null }

        assertEquals(NodeType.LITE_RT, picked)
        assertEquals(0, dismissed)
    }

    @Test
    fun nodeTypePicker_secondPickWhileHiding_reportsOnlyOneType() {
        render()
        composeTestRule.waitForIdle()

        // Accessibility clicks, not touches: a touch on the moving sheet catches
        // it and cancels the hide by itself, while TalkBack's double-tap reaches
        // the row's click directly — the path on which two hides can overlap.
        // The clock is frozen so the first hide is still under way.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag(rowTag(NodeType.LITE_RT)).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag(rowTag(NodeType.CLOUD)).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntil(timeoutMillis = 5_000) { picked != null }
        composeTestRule.waitForIdle()

        assertEquals("One opening reported more than one type: $pickedTypes", 1, pickedTypes.size)
        assertEquals(0, dismissed)
    }

    @Test
    fun nodeTypePicker_search_keepsOnlyMatchingTypes() {
        render()

        composeTestRule.onNodeWithTag(NODE_TYPE_PICKER_SEARCH_TEST_TAG).performTextInput("queue")

        composeTestRule.onNodeWithTag(rowTag(NodeType.QUEUE_PROCESSOR)).assertExists()
        composeTestRule.onNodeWithTag(rowTag(NodeType.LITE_RT)).assertDoesNotExist()
    }

    @Test
    fun nodeTypePicker_close_dismissesWithoutAPick() {
        render()

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { dismissed > 0 }

        assertEquals(1, dismissed)
        assertNull(picked)
    }

    @Test
    fun nodeTypePicker_dragHandle_isNotATalkBackStop() {
        render()

        // Material's own handle announces itself as "Drag handle"; the editor's
        // is a mark only, because Close and Back already dismiss.
        composeTestRule.onAllNodesWithContentDescription("Drag handle", substring = true)
            .assertCountEquals(0)
    }
}
