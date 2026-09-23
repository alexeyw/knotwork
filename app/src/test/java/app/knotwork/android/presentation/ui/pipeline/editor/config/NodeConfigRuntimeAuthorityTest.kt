package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule "the configuration sheet shows the value the run uses" for
 * every field of every node type.
 *
 * A stored node carries its configuration twice — the flat properties the
 * engine reads and the `configJson` envelope — and an imported file can make
 * the two disagree. The sheet is the only screen that shows a node's tool,
 * prompt or provider, so when the copies disagree it must show the flat one.
 *
 * The check is behavioural rather than a list of fields: build a node whose
 * flat properties come from one configuration and whose envelope comes from
 * another, open it in the sheet and save it untouched. If saving changes
 * anything the run reads, the sheet displayed a value from the envelope. The
 * two configurations are [NodeConfigMutations.mutatedConfig] (every field off
 * its default — the table [CookbookRuntimeReachTest] also uses) and the
 * editor's own default, and the test first proves they disagree on every
 * property the run reads, so a field read from the wrong copy cannot hide
 * behind two equal values.
 */
class NodeConfigRuntimeAuthorityTest {

    @Test
    fun `given an envelope disagreeing with the run when saved untouched then nothing that runs changes`() {
        NodeType.entries.forEach { type ->
            val mutated = NodeConfigCodec.apply(
                NodeConfigMutations.baseNode(type),
                NodeConfigMutations.mutatedConfig(type),
            )
            val defaulted = NodeConfigCodec.apply(
                NodeConfigMutations.baseNode(type),
                NodeConfigCodec.defaultFor(NodeTypeMapper.toCatalog(type), title = DEFAULT_TITLE),
            )
            assertTheTwoDisagreeOnEveryRunInput(type, mutated, defaulted)

            assertSheetShowsTheRun(type, runs = mutated, envelopeFrom = defaulted)
            assertSheetShowsTheRun(type, runs = defaulted, envelopeFrom = mutated)
        }
    }

    /**
     * Without this the test could pass vacuously: a property equal in both
     * configurations reads the same from either copy.
     */
    private fun assertTheTwoDisagreeOnEveryRunInput(type: NodeType, mutated: NodeModel, defaulted: NodeModel) {
        val base = NodeConfigMutations.flatProperties(NodeConfigMutations.baseNode(type))
        val mutatedFlat = NodeConfigMutations.flatProperties(mutated)
        val defaultedFlat = NodeConfigMutations.flatProperties(defaulted)
        val runInputs = base.keys.filter { base[it] != mutatedFlat[it] }
        val indistinguishable = runInputs.filter { mutatedFlat[it] == defaultedFlat[it] }
        assertTrue(
            "$type: the mutated and the default configuration store the same $indistinguishable, so this " +
                "test cannot tell which copy the sheet read them from. Change one of the two values.",
            indistinguishable.isEmpty(),
        )
    }

    private fun assertSheetShowsTheRun(type: NodeType, runs: NodeModel, envelopeFrom: NodeModel) {
        val imported = runs.copy(configJson = envelopeFrom.configJson)

        val savedUntouched = NodeConfigCodec.apply(imported, NodeConfigCodec.decode(imported))

        assertEquals(
            "$type: the sheet showed a value from the node's envelope rather than the one the run reads, so " +
                "saving it without an edit changed what runs. Read the field from the flat NodeModel property " +
                "in NodeConfigCodec.decode.",
            NodeConfigMutations.flatProperties(runs),
            NodeConfigMutations.flatProperties(savedUntouched),
        )
        assertEquals("$type: the sheet's title must be the node's label", runs.label, savedUntouched.label)
    }

    private companion object {
        const val DEFAULT_TITLE = "Default title"
    }
}
