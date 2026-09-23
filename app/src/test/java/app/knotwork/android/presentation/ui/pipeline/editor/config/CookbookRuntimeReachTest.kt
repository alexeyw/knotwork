package app.knotwork.android.presentation.ui.pipeline.editor.config

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds the published claim "this configuration field reaches the run" against
 * what [NodeConfigCodec] actually does.
 *
 * `docs/cookbook.md` prints, for every field of every node configuration sheet,
 * whether saving it changes anything at run time. That column is the reason the
 * document is worth reading: a sheet shows fields the engine consults and
 * fields it does not, and the difference is invisible in the app — an ignored
 * field still accepts input, still survives a reopen, still exports.
 *
 * The column cannot be checked by the generator that writes it. A generator
 * paired with its own drift check agrees with itself precisely when it is
 * wrong, and no static reading of a declaration can tell whether the engine
 * later reads the value. So the check lives here, on the other side: this test
 * mutates every field of every configuration, pushes it through the real codec,
 * and observes which properties of the stored [NodeModel] actually changed.
 * Those observations are then compared against the committed Markdown — a
 * different file, produced by different code, read by a different parser.
 *
 * Consequences, both of which are the point:
 *
 *  - fixing the codec so an ignored field starts reaching the engine fails this
 *    test until `./gradlew :app:generateCookbookDocs` is re-run, so the fix and
 *    the documentation land together;
 *  - a verdict claiming a field works when it does not fails here rather than
 *    being published.
 *
 * Gradle's `:app:test` runs in the `app/` working directory, so the cookbook
 * resolves as `../docs/cookbook.md`.
 */
class CookbookRuntimeReachTest {

    private val cookbook = File("../docs/cookbook.md")

    @Test
    fun `given the cookbook when its run-time verdicts are read then the codec agrees with every one`() {
        val documented = parseDocumentedTargets()
        assertEquals(
            "The cookbook does not describe every node type — regenerate it before trusting this test.",
            NodeType.entries.map { it.name }.toSet(),
            documented.keys,
        )

        NodeType.entries.forEach { type ->
            val observed = observedTargets(type)
            assertEquals(
                "docs/cookbook.md and NodeConfigCodec disagree about $type. The document says the fields " +
                    "reaching the run are ${documented.getValue(type.name).sorted()}; saving the sheet " +
                    "actually changes ${observed.sorted()}. Fix whichever is wrong, then re-run " +
                    "`./gradlew :app:generateCookbookDocs`.",
                documented.getValue(type.name),
                observed,
            )
        }
    }

    @Test
    fun `given the cookbook when the four prompt fields are read then each one writes systemPrompt`() {
        // Inverted from the canary it replaces. While the codec dropped these
        // four, the test pinned the defect *by name* so a fix could not land
        // without updating the document that described it — which is exactly
        // how it behaved. It now pins the repair, for the same reason: the
        // claim a reader is least likely to believe is the one worth holding
        // against the code.
        val documented = parseDocumentedTargets()

        listOf(
            NodeType.INTENT_ROUTER,
            NodeType.DECOMPOSITION,
            NodeType.EVALUATION,
            NodeType.SUMMARY,
        ).forEach { type ->
            assertTrue(
                "${'$'}{type.name}'s prompt field no longer writes `systemPrompt`; the sheet would " +
                    "accept a prompt the run never sees. Fix `NodeConfigCodec.apply`, not this test.",
                "systemPrompt" in documented.getValue(type.name),
            )
        }
    }

    /**
     * Reads the generated appendix and returns, per node type, the `NodeModel`
     * properties the document claims a field reaches.
     *
     * Every node type appears as a key, including the three whose fields all
     * reach nothing — otherwise a type whose only wired field was lost would
     * simply vanish from the comparison instead of failing it.
     */
    private fun parseDocumentedTargets(): Map<String, Set<String>> {
        val markdown = cookbook.readText()
        val block = markdown.substringAfter("<!-- AUTO-GEN:FIELD_TABLE -->", "")
            .substringBefore("<!-- /AUTO-GEN:FIELD_TABLE -->", "")
        check(block.isNotBlank()) { "docs/cookbook.md has no generated FIELD_TABLE block." }

        val documented = mutableMapOf<String, MutableSet<String>>()
        block.lineSequence().forEach { line ->
            val type = ROW_TYPE_RE.find(line)?.groupValues?.get(1) ?: return@forEach
            val targets = documented.getOrPut(type) { mutableSetOf() }
            RUNTIME_ROW_RE.find(line)?.let { targets += it.groupValues[1] }
        }
        check(documented.isNotEmpty()) { "Parsed no rows from the appendix — the parse is wrong, not the document." }
        return documented
    }

    /**
     * Returns the `NodeModel` properties that actually move when every field of
     * this type's configuration is changed at once and the sheet is saved.
     */
    private fun observedTargets(type: NodeType): Set<String> {
        val base = NodeConfigMutations.baseNode(type)
        val applied = NodeConfigCodec.apply(base, NodeConfigMutations.mutatedConfig(type))
        val before = NodeConfigMutations.flatProperties(base)
        val after = NodeConfigMutations.flatProperties(applied)
        return before.keys.filter { before[it] != after[it] }.toSet()
    }

    private companion object {
        val ROW_TYPE_RE = Regex("""^\|\s*`([A-Z][A-Z0-9_]*)`\s*\|""")
        val RUNTIME_ROW_RE = Regex("""\*\*Yes\*\* — saved as the node's `(\w+)`""")
    }
}
