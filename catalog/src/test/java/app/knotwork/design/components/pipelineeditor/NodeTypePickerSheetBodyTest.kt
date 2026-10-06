package app.knotwork.design.components.pipelineeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Behaviour of [NodeTypePickerSheetBody]: what a search keeps, what a tap
 * returns, and what TalkBack is given.
 *
 * Tall enough that every row is composed at once, so "every type is listed"
 * is checked on the real list rather than on what fits a phone screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h2400dp-xhdpi")
class NodeTypePickerSheetBodyTest {

    @get:Rule
    val rule = createComposeRule()

    private var query by mutableStateOf("")
    private val picked = mutableListOf<NodeType>()
    private var dismissed = 0

    private fun render(initial: String = "") {
        query = initial
        rule.setContent {
            KnotworkTheme {
                NodeTypePickerSheetBody(
                    query = query,
                    onQueryChange = { query = it },
                    onPick = { picked += it },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    private fun row(type: NodeType) = rule.onNodeWithTag(NODE_TYPE_PICKER_ROW_TEST_TAG_PREFIX + type.name)

    private fun type(text: String) = rule.onNodeWithTag(NODE_TYPE_PICKER_SEARCH_TEST_TAG).performTextInput(text)

    @Test
    fun `given an empty search when opened then every type is listed under five headings and the title`() {
        render()

        NodeType.entries.forEach { row(it).assertExists() }
        // Five group headings plus the sheet's own title.
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(6)
        rule.onNodeWithText("RUN A MODEL").assertIsDisplayed()
        rule.onNodeWithTag(NODE_TYPE_PICKER_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Add node"))
    }

    @Test
    fun `given a word typed when filtered then only the matching types remain`() {
        render()

        type("list")

        listOf(NodeType.SKILL, NodeType.DECOMPOSITION, NodeType.QUEUE_PROCESSOR).forEach { row(it).assertExists() }
        row(NodeType.LITE_RT).assertDoesNotExist()
        row(NodeType.INPUT).assertDoesNotExist()
        rule.onNodeWithText("START AND FINISH").assertDoesNotExist()
    }

    @Test
    fun `given a row when tapped then its type is picked once`() {
        render()

        row(NodeType.EVALUATION).performClick()

        assertEquals(listOf(NodeType.EVALUATION), picked)
    }

    @Test
    fun `given a row when read by TalkBack then it is one button with name, description and the add action`() {
        render()

        val node = row(NodeType.DECOMPOSITION).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        assertEquals("Decomposition Turns one instruction into a list of subtasks.", text)
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals("Add to pipeline", node.config.getOrNull(SemanticsActions.OnClick)?.label)
    }

    @Test
    fun `given the search action when the keyboard sends it then nothing is picked`() {
        render()
        type("cloud")

        rule.onNodeWithTag(NODE_TYPE_PICKER_SEARCH_TEST_TAG).performImeAction()

        assertEquals(emptyList<NodeType>(), picked)
        row(NodeType.CLOUD).assertExists()
    }

    @Test
    fun `given no text when opened then there is no clear icon, and with text it clears the search`() {
        render()
        rule.onNodeWithContentDescription("Clear search").assertDoesNotExist()

        type("cloud")
        rule.onNodeWithContentDescription("Clear search").performClick()

        assertEquals("", query)
        rule.onNodeWithContentDescription("Clear search").assertDoesNotExist()
        row(NodeType.INPUT).assertExists()
    }

    @Test
    fun `given a query nothing matches when shown then it is quoted and Clear search brings every type back`() {
        render()
        type("zzz")

        rule.onNodeWithText("No node type matches “zzz”").assertIsDisplayed()
        rule.onAllNodesWithTag(NODE_TYPE_PICKER_ROW_TEST_TAG_PREFIX + NodeType.INPUT.name).assertCountEquals(0)

        rule.onNode(hasText("Clear search")).performClick()

        assertEquals("", query)
        NodeType.entries.forEach { row(it).assertExists() }
    }

    @Test
    fun `given the close icon when tapped then the sheet is dismissed without a pick`() {
        render()

        rule.onNodeWithContentDescription("Close").performClick()

        assertEquals(1, dismissed)
        assertEquals(emptyList<NodeType>(), picked)
    }

    @Test
    fun `given typing that pauses when TalkBack reads the field then it carries the count of matches`() {
        rule.mainClock.autoAdvance = false
        render()
        type("list")
        // Half a second in, typing may still be under way: nothing is announced
        // yet. Several frames pass, so an announcement without the pause would
        // already be on the field.
        rule.mainClock.advanceTimeBy(500)

        assertNull(searchState())

        rule.mainClock.advanceTimeBy(600)
        assertEquals("3 node types", searchState())
    }

    @Test
    fun `given a query nothing matches when typing pauses then the field carries the no-match line`() {
        rule.mainClock.autoAdvance = false
        render()
        type("zzz")
        rule.mainClock.advanceTimeBy(1_100)

        assertEquals("No node type matches “zzz”", searchState())
    }

    private fun searchState(): String? = rule.onNodeWithTag(NODE_TYPE_PICKER_SEARCH_TEST_TAG).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)
}
