package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [CookbookDocsGenerator].
 *
 * Unlike its sibling generators these tests read the **real** node sources
 * rather than synthetic fixtures. The generator's hand-maintained tables name
 * the real node types and the real configuration fields, so a synthetic
 * vocabulary would only ever exercise the cross-check failing — never the case
 * where it passes, which is the one that has to keep working.
 *
 * The failure modes are then injected by mutating those sources **in memory**:
 * a node type added, one removed from the `:catalog` mirror, a configuration
 * field renamed. Each has to stop generation with the cause named, because each
 * is a way the published reference could otherwise lose a row and stay green.
 *
 * Gradle runs `buildSrc` tests in the `buildSrc/` directory, so the sources
 * resolve one level up.
 */
class CookbookDocsGeneratorTest {

    private val sources = CookbookDocsGenerator.Sources(
        domainNodeType = read("app/src/main/java/app/knotwork/android/domain/models/NodeType.kt"),
        catalogNodeType = read("catalog/src/main/java/app/knotwork/design/components/pipelineeditor/NodeType.kt"),
        nodePorts = read("catalog/src/main/java/app/knotwork/design/components/pipelineeditor/NodePorts.kt"),
        nodeContextConfig = read("app/src/main/java/app/knotwork/android/domain/models/NodeContextConfig.kt"),
        nodeConfig = read("catalog/src/main/java/app/knotwork/design/components/pipelineeditor/NodeConfig.kt"),
        defaultPrompts = read("app/src/main/java/app/knotwork/android/domain/constants/DefaultPrompts.kt"),
        nodeTypeStrings = read("catalog/src/main/res/values/strings_node_types.xml"),
    )

    private val skeleton = """
        # Pipeline cookbook

        <!-- AUTO-GEN:NODE_REFERENCE -->
        <!-- /AUTO-GEN:NODE_REFERENCE -->

        <!-- AUTO-GEN:NODE_CONFIG -->
        <!-- /AUTO-GEN:NODE_CONFIG -->

        <!-- AUTO-GEN:FIELD_TABLE -->
        <!-- /AUTO-GEN:FIELD_TABLE -->
    """.trimIndent()

    @Test
    fun `given the real sources when rendered twice then the second pass changes nothing`() {
        val once = CookbookDocsGenerator.render(skeleton, sources)
        val twice = CookbookDocsGenerator.render(once, sources)

        assertNotEquals("The first render produced nothing.", skeleton, once)
        assertEquals("Rendering is not idempotent.", once, twice)
    }

    @Test
    fun `given a freshly rendered document when checked for drift then no block is reported`() {
        val rendered = CookbookDocsGenerator.render(skeleton, sources)

        assertEquals(emptyList<String>(), CookbookDocsGenerator.drift(rendered, sources))
    }

    @Test
    fun `given a hand-edited verdict when checked for drift then the block is reported`() {
        val rendered = CookbookDocsGenerator.render(skeleton, sources)
        // Any verdict will do — the point is that a hand-edit to the table is
        // detected. It used to tamper with a `**No**` row, and there are none
        // left: every field the run ignored has since been wired up or removed.
        val tampered = rendered.replace(
            "**Yes** — saved as the node's `systemPrompt`",
            "**No** — stored and exported, but nothing reads it during a run",
        )

        assertEquals(
            listOf(CookbookDocsGenerator.BLOCK_FIELD_TABLE),
            CookbookDocsGenerator.drift(tampered, sources),
        )
    }

    @Test
    fun `given a run-time input whose sheet field disappears when rendered then generation fails`() {
        // The reference now leads with what a node runs on, so the two tables
        // have to agree. Removing the field that writes `conditionPrompt` leaves
        // an input row promising a control that is not there — which is exactly
        // the shape of confusion this rewrite was about.
        val withoutExpression = sources.copy(
            nodeConfig = sources.nodeConfig.replace("    val expression: String = \"\",\n", ""),
        )

        val error = assertThrows(CookbookDocsGenerator.GenerationException::class.java) {
            CookbookDocsGenerator.render(skeleton, withoutExpression)
        }
        assertTrue(
            "The failure does not name the unwritten input: ${error.message}",
            error.message.orEmpty().contains("conditionPrompt"),
        )
    }

    @Test
    fun `given every node type when rendered then its run-time inputs and its fields agree`() {
        // The positive case of the same guard: with the real sources, every
        // property a sheet field writes is explained by an input row, and every
        // row marked as having no control really has none.
        val rendered = CookbookDocsGenerator.render(skeleton, sources)

        // The `NoControl` vocabulary is no longer reached by the real sources:
        // the two IF_CONDITION inputs that used it are on the sheet now. The
        // generator keeps it — it is how the next such field gets recorded
        // rather than quietly explained — but nothing should be using it today.
        assertTrue(
            "No run-time input should still be marked as having no in-app control.",
            !rendered.contains("nothing in the app writes it"),
        )
        // INPUT still has neither run-time inputs nor graph-shaping fields, and
        // says so rather than rendering an empty table.
        assertTrue(
            "A node with no run-time inputs should say so rather than render an empty table.",
            rendered.contains("**Nothing on this node changes a run.**"),
        )
    }

    @Test
    fun `given a document with no markers when rendered then generation fails`() {
        assertThrows(CookbookDocsGenerator.GenerationException::class.java) {
            CookbookDocsGenerator.render("# Cookbook with no markers", sources)
        }
    }

    @Test
    fun `given a node type absent from the reader-facing meta when rendered then generation fails`() {
        val withExtraType = sources.copy(
            domainNodeType = sources.domainNodeType.replace("    OUTPUT,", "    OUTPUT,\n\n    TELEPATHY,"),
        )

        val error = assertThrows(CookbookDocsGenerator.GenerationException::class.java) {
            CookbookDocsGenerator.render(skeleton, withExtraType)
        }
        assertTrue(
            "The failure does not name the offending type: ${error.message}",
            error.message.orEmpty().contains("TELEPATHY"),
        )
    }

    @Test
    fun `given the catalog mirror missing a type when rendered then generation fails`() {
        val withShortMirror = sources.copy(
            catalogNodeType = sources.catalogNodeType.replace("    SKILL,", ""),
        )

        val error = assertThrows(CookbookDocsGenerator.GenerationException::class.java) {
            CookbookDocsGenerator.render(skeleton, withShortMirror)
        }
        assertTrue(
            "The failure does not name the disagreeing source: ${error.message}",
            error.message.orEmpty().contains("catalog NodeType mirror"),
        )
    }

    @Test
    fun `given a configuration field with no recorded verdict when rendered then generation fails`() {
        val withRenamedField = sources.copy(
            nodeConfig = sources.nodeConfig.replace("val maxNewTokens: Int", "val tokenCeiling: Int"),
        )

        val error = assertThrows(CookbookDocsGenerator.GenerationException::class.java) {
            CookbookDocsGenerator.render(skeleton, withRenamedField)
        }
        assertTrue(
            "The failure does not name the undecided field: ${error.message}",
            error.message.orEmpty().contains("LiteRtConfig.tokenCeiling"),
        )
    }

    @Test
    fun `given the ports factory when parsed then each port shape is read from it`() {
        val ports = CookbookDocsGenerator.buildNodes(sources).associate { it.doc.id to it.ports }

        assertEquals("INPUT should have no inbound port.", 0, ports.getValue("INPUT").inbound)
        assertEquals("OUTPUT should have no outbound port.", emptyList<String>(), ports.getValue("OUTPUT").outbound)
        assertEquals(
            "IF_CONDITION should carry both labelled branches.",
            listOf("True", "False"),
            ports.getValue("IF_CONDITION").outbound,
        )
        assertEquals(
            "EVALUATION's Retry port is emitted under a condition.",
            setOf("Retry"),
            ports.getValue("EVALUATION").conditional,
        )
        assertTrue(
            "INTENT_ROUTER's ports come from its declared classes.",
            ports.getValue("INTENT_ROUTER").perDeclaredClass,
        )
        // A branch that names no `outbound` argument takes the data class's own
        // default of one unlabelled port. Reading it as "no ports" would publish
        // every ordinary node as terminal, and would look plausible in review.
        assertEquals(
            "A default branch should render as one unlabelled outbound port.",
            listOf("Default"),
            ports.getValue("LITE_RT").outbound,
        )
    }

    @Test
    fun `given the context defaults when parsed then only the enabled blocks are listed`() {
        val nodes = CookbookDocsGenerator.buildNodes(sources).associateBy { it.doc.id }

        assertEquals(listOf("nodeInput"), nodes.getValue("TOOL").context)
        assertEquals(
            listOf("chatHistory", "originalTask", "nodeInput"),
            nodes.getValue("CLOUD").context,
        )
        assertTrue("PIPELINE forwards its input verbatim.", !nodes.getValue("PIPELINE").usesContext)
        assertTrue("LITE_RT composes its input from context.", nodes.getValue("LITE_RT").usesContext)
    }

    @Test
    fun `given the default prompts when parsed then only the seeded types report one`() {
        val nodes = CookbookDocsGenerator.buildNodes(sources).associateBy { it.doc.id }

        assertTrue("LITE_RT is seeded with a prompt.", nodes.getValue("LITE_RT").hasDefaultPrompt)
        assertTrue("EVALUATION is seeded with a prompt.", nodes.getValue("EVALUATION").hasDefaultPrompt)
        // OUTPUT is deliberately seeded with none so a fresh node echoes its
        // input; a parser that read the `else -> null` arm as a prompt would
        // publish the opposite of the shipped behaviour.
        assertTrue("OUTPUT starts in pass-through mode.", !nodes.getValue("OUTPUT").hasDefaultPrompt)
        assertTrue("INPUT carries no prompt.", !nodes.getValue("INPUT").hasDefaultPrompt)
    }

    @Test
    fun `given the appendix when rendered then every configuration field appears exactly once`() {
        val nodes = CookbookDocsGenerator.buildNodes(sources)
        val rendered = CookbookDocsGenerator.render(skeleton, sources)
        val appendix = rendered.substringAfter("<!-- AUTO-GEN:FIELD_TABLE -->")
            .substringBefore("<!-- /AUTO-GEN:FIELD_TABLE -->")

        // A node with no fields at all still gets one row, saying so, rather
        // than vanishing from a table that claims to cover every type — so it
        // counts once here too.
        val expected = nodes.sumOf { it.fields.size } + nodes.count { it.fields.isEmpty() }
        val actual = appendix.lines().count { it.startsWith("| `") }
        assertEquals("The appendix does not list every field exactly once.", expected, actual)
    }

    @Test
    fun `given the rendered reference when read then every node type has a section`() {
        val rendered = CookbookDocsGenerator.render(skeleton, sources)

        CookbookDocsGenerator.buildNodes(sources).forEach { node ->
            assertTrue(
                "The rendered reference has no section for ${node.doc.id}.",
                rendered.contains("### ${node.name} — `${node.doc.id}`"),
            )
        }
    }

    @Test
    fun `given the resource file when rendered then every entry opens with the app's description`() {
        // The app's node picker and the cookbook share one sentence. The entry
        // must open with it verbatim and only then continue, or the two would
        // be two texts again with nothing to say they had drifted.
        val texts = NodeTypeStrings.parse(sources.nodeTypeStrings)
        val rendered = CookbookDocsGenerator.render(skeleton, sources)

        CookbookDocsGenerator.NODE_DOC_META.forEach { doc ->
            val text = texts.getValue(doc.id)
            val opening = listOfNotNull(text.description, doc.continuation).joinToString(" ")
            assertTrue(
                "${doc.id}'s entry does not open with the app's description: $opening",
                rendered.contains("### ${text.name} — `${doc.id}`\n\n$opening\n\n"),
            )
        }
    }

    @Test
    fun `given a type changed only in the resource file when checked for drift then both node blocks are reported`() {
        // The resource file is a source like the others: editing a description
        // without regenerating must fail `verifyCookbookDocs`.
        val rendered = CookbookDocsGenerator.render(skeleton, sources)
        val edited = sources.copy(
            nodeTypeStrings = sources.nodeTypeStrings.replace(
                ">$decompositionDescription<",
                ">${decompositionDescription.removeSuffix(".")} again.<",
            ),
        )
        assertNotEquals("The edit did not apply.", sources, edited)

        assertEquals(
            listOf(CookbookDocsGenerator.BLOCK_NODE_REFERENCE, CookbookDocsGenerator.BLOCK_NODE_CONFIG),
            CookbookDocsGenerator.drift(rendered, edited),
        )
    }

    @Test
    fun `given a node type missing from the resource file when rendered then generation fails naming it`() {
        val withoutSummary = sources.copy(
            nodeTypeStrings = sources.nodeTypeStrings.lines()
                .filterNot { it.contains("knotwork_node_type_summary_") }
                .joinToString("\n"),
        )

        val error = assertThrows(CookbookDocsGenerator.GenerationException::class.java) {
            CookbookDocsGenerator.render(skeleton, withoutSummary)
        }
        assertTrue(
            "The failure does not name the file and the type: ${error.message}",
            error.message.orEmpty().contains("strings_node_types.xml") &&
                error.message.orEmpty().contains("SUMMARY"),
        )
    }

    @Test
    fun `given a resource text the parser refuses when rendered then generation fails`() {
        val withMarkup = sources.copy(
            nodeTypeStrings = sources.nodeTypeStrings.replace(
                ">$decompositionDescription<",
                "><b>$decompositionDescription</b><",
            ),
        )

        val error = assertThrows(CookbookDocsGenerator.GenerationException::class.java) {
            CookbookDocsGenerator.render(skeleton, withMarkup)
        }
        assertTrue(
            "The failure does not name the resource: ${error.message}",
            error.message.orEmpty().contains("knotwork_node_type_decomposition_description"),
        )
    }

    /**
     * DECOMPOSITION's description as the resource file holds it — read rather
     * than quoted, so the in-memory edits above keep working when the sentence
     * is reworded. It has no apostrophe, so the raw XML body is the same text.
     */
    private val decompositionDescription: String
        get() = NodeTypeStrings.parse(sources.nodeTypeStrings).getValue("DECOMPOSITION").description
            .also { require('\'' !in it) { "Pick a description without an escape for these edits." } }

    private fun read(relativePath: String): String = File("../$relativePath").readText()
}
