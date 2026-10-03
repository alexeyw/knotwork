package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps the golden-trace catalogue complete, so the gate cannot quietly stop covering what
 * ships.
 *
 * - Every bundled preset, every published recipe and every fixture has at least one scenario:
 *   a new pipeline file without one fails here instead of shipping unpinned.
 * - Every `NodeType` appears in some committed golden trace — read from the files, not from the
 *   scenarios, because the files are what the gate compares against.
 * - Every golden file belongs to a scenario, so a renamed or removed scenario cannot leave a
 *   trace behind that nothing checks any more.
 */
internal class GoldenTraceCatalogueTest {

    @Test
    fun `every shipped pipeline file has at least one golden scenario`() {
        GoldenSourceKind.entries.forEach { kind ->
            val files = GoldenPipelineSources.stems(kind)
            assertTrue("No ${kind.directory} found — wrong working directory?", files.isNotEmpty())
            val covered = GoldenScenarios.all.filter { it.source.kind == kind }.map { it.source.fileStem }.toSet()
            assertEquals(
                "Every ${kind.directory} file needs a golden scenario in GoldenScenarios",
                emptyList<String>(),
                files.filterNot { it in covered },
            )
        }
    }

    @Test
    fun `every node type appears in a committed golden trace`() {
        val visited = goldenFiles()
            .flatMap { it.readLines() }
            .filter { it.startsWith(VISIT_PREFIX) }
            .map { it.split(" ")[VISIT_TYPE_FIELD] }
            .toSet()
        assertEquals(
            "Node types no golden trace visits — add a scenario (or a fixture) that runs one",
            emptyList<String>(),
            NodeType.entries.map { it.name }.filterNot { it in visited },
        )
    }

    @Test
    fun `every golden file belongs to a scenario`() {
        val expected = GoldenScenarios.all.map { it.goldenPath }.toSet()
        val orphaned = goldenFiles().map { it.relativeTo(GoldenTraceTest.TRACES_DIR).invariantSeparatorsPath }
            .filterNot { it in expected }
        assertEquals("Golden files no scenario produces — delete them", emptyList<String>(), orphaned)
    }

    @Test
    fun `scenario names are unique per pipeline`() {
        val duplicates = GoldenScenarios.all.groupBy { it.goldenPath }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), duplicates)
    }

    private fun goldenFiles(): List<File> = GoldenTraceTest.TRACES_DIR.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".trace") }
        .sortedBy { it.path }
        .toList()

    private companion object {
        /** A visit line: `visit <runId> <pipeline>/<node> <TYPE> #<n>`. */
        const val VISIT_PREFIX = "visit "

        /** Index of the node type in a visit line split on spaces. */
        const val VISIT_TYPE_FIELD = 3
    }
}
