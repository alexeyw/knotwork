package app.knotwork.design.components.pipelineeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's grouping and search rules, without Compose.
 *
 * The texts are stand-ins shaped like the real ones, so a rule is pinned by
 * what it does rather than by today's wording.
 */
class NodeTypeSearchTest {

    private val texts = mapOf(
        NodeType.INPUT to ("Input" to "Where the run starts."),
        NodeType.OUTPUT to ("Output" to "Where the run ends."),
        NodeType.LITE_RT to ("LiteRT" to "One inference step on the local model running on the phone."),
        NodeType.CLOUD to ("Cloud" to "One inference step against a cloud provider."),
        NodeType.SKILL to ("Skill" to "Runs a reusable skill: an instruction plus the list of tools it may use."),
        NodeType.CLARIFICATION to ("Clarification" to "Pauses the run to ask you a question."),
        NodeType.TOOL to ("Tool" to "Calls one tool and passes the result on."),
        NodeType.PIPELINE to ("Pipeline" to "Runs another saved pipeline as a single step."),
        NodeType.INTENT_ROUTER to ("Intent Router" to "Sorts the incoming text into one of your classes."),
        NodeType.IF_CONDITION to ("If Condition" to "A two-way branch."),
        NodeType.EVALUATION to ("Evaluation" to "Judges what the previous step produced."),
        NodeType.DECOMPOSITION to ("Decomposition" to "Turns one instruction into a list of subtasks."),
        NodeType.QUEUE_PROCESSOR to ("Queue Processor" to "Walks a list of subtasks one at a time."),
        NodeType.SUMMARY to ("Summary" to "Condenses what earlier steps produced."),
    )

    private fun entryOf(type: NodeType): NodeTypeSearch.Entry {
        val (name, description) = texts.getValue(type)
        return NodeTypeSearch.Entry(type, name, description)
    }

    private fun filter(query: String): List<Pair<NodeTypeGroup, List<NodeType>>> =
        NodeTypeSearch.filter(query, ::entryOf).map { section -> section.group to section.entries.map { it.type } }

    @Test
    fun `given every node type when grouped then each is in exactly one group, in the designed order`() {
        val listed = NodeTypeGroup.entries.flatMap { it.types }

        assertEquals("A type is missing from the picker or listed twice.", NodeType.entries.toSet(), listed.toSet())
        assertEquals("A type is listed twice.", NodeType.entries.size, listed.size)
        assertEquals(
            listOf(
                NodeTypeGroup.START_AND_FINISH to listOf(NodeType.INPUT, NodeType.OUTPUT),
                NodeTypeGroup.RUN_A_MODEL to listOf(NodeType.LITE_RT, NodeType.CLOUD, NodeType.SKILL),
                NodeTypeGroup.ASK_AND_ACT to listOf(NodeType.CLARIFICATION, NodeType.TOOL, NodeType.PIPELINE),
                NodeTypeGroup.BRANCH to listOf(NodeType.INTENT_ROUTER, NodeType.IF_CONDITION, NodeType.EVALUATION),
                NodeTypeGroup.WORK_THROUGH_A_LIST to
                    listOf(NodeType.DECOMPOSITION, NodeType.QUEUE_PROCESSOR, NodeType.SUMMARY),
            ),
            NodeTypeGroup.entries.map { it to it.types },
        )
    }

    @Test
    fun `given a blank query when filtered then every group comes back whole`() {
        assertEquals(NodeTypeGroup.entries.map { it to it.types }, filter("   "))
    }

    @Test
    fun `given a word from descriptions when filtered then empty groups drop out and the order holds`() {
        assertEquals(
            listOf(
                NodeTypeGroup.RUN_A_MODEL to listOf(NodeType.SKILL),
                NodeTypeGroup.WORK_THROUGH_A_LIST to listOf(NodeType.DECOMPOSITION, NodeType.QUEUE_PROCESSOR),
            ),
            filter("list"),
        )
    }

    @Test
    fun `given two words when filtered then a type must contain both`() {
        // "step" alone matches five types; with "cloud" only one has both words.
        assertEquals(listOf(NodeTypeGroup.RUN_A_MODEL to listOf(NodeType.CLOUD)), filter("step cloud"))
    }

    @Test
    fun `given mixed case and extra spaces when filtered then case and spacing do not matter`() {
        assertEquals(filter("local phone"), filter("  LOCAL   Phone "))
        assertEquals(listOf(NodeTypeGroup.RUN_A_MODEL to listOf(NodeType.LITE_RT)), filter("  LOCAL   Phone "))
    }

    @Test
    fun `given the enum id when filtered then the type is found though the id is never shown`() {
        assertEquals(
            listOf(NodeTypeGroup.WORK_THROUGH_A_LIST to listOf(NodeType.QUEUE_PROCESSOR)),
            filter("queue_processor"),
        )
    }

    @Test
    fun `given part of a word when matched then it is found as a substring`() {
        assertTrue(NodeTypeSearch.matches(entryOf(NodeType.INTENT_ROUTER), "rout"))
    }

    @Test
    fun `given a word no type contains when filtered then nothing comes back`() {
        assertEquals(emptyList<Pair<NodeTypeGroup, List<NodeType>>>(), filter("zzz"))
        assertFalse(NodeTypeSearch.matches(entryOf(NodeType.INPUT), "input zzz"))
    }
}
