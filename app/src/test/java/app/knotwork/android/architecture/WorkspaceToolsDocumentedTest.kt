package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the three places that enumerate the tools able to read and write the agent's
 * workspace to the tools that actually can.
 *
 * **Why.** A reader who wants to know what touches the workspace reads one of these
 * lists, and each is prose nobody regenerates. The threat model and the
 * architecture map both named six tools while a seventh, `append_file` — a write
 * tool — shipped beside them; only the extension guide had it right.
 *
 * **What counts as a workspace tool.** A `LocalToolExecutor` whose constructor
 * takes an `AgentWorkspace` — the only way a tool reaches the workspace, since the
 * executors never build a `java.io.File` (`docs/extending.md` §2.6). Its name is
 * the `TOOL_NAME` constant it registers under.
 *
 * **What it cannot see.** It checks that each name appears, in backticks, inside
 * the named section. A section that names a tool the code no longer has, or a
 * count written out in words, is not checked.
 */
class WorkspaceToolsDocumentedTest {

    @Test
    fun `every workspace tool is named where the documents enumerate them`() {
        val tools = workspaceToolNames()
        val missing = DOCUMENTED_SECTIONS.associate { (document, heading) ->
            val section = section(File(repositoryRoot(), document).readText(), heading)
            "$document § $heading" to tools.filterNot { "`$it`" in section }
        }.filterValues { it.isNotEmpty() }

        assertEquals(
            "a tool that reads or writes the workspace is missing from a document that lists them all",
            emptyMap<String, List<String>>(),
            missing,
        )
    }

    @Test
    fun `the census finds the workspace tools it claims to`() {
        // Keeps the rule above from passing vacuously on an empty or wrong tree.
        val tools = workspaceToolNames()
        assertTrue("workspace tools found: $tools", tools.containsAll(listOf("read_file", "write_file", "find_files")))
        assertTrue("a non-workspace tool counted: $tools", "http_request" !in tools)
    }

    private fun workspaceToolNames(): List<String> = ProductionSources.code
        .filterKeys { it.contains(EXECUTORS_DIRECTORY) }
        .values
        .filter { WORKSPACE_PARAMETER.containsMatchIn(it) }
        .mapNotNull { TOOL_NAME.find(it)?.groupValues?.get(1) }
        .sorted()

    /** The text from the line holding [heading] up to the next heading of any level. */
    private fun section(document: String, heading: String): String {
        val lines = document.lines()
        val start = lines.indexOfFirst { it.startsWith("#") && heading in it }
        check(start >= 0) { "heading '$heading' not found" }
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("#") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun repositoryRoot(): File = ProductionSources.moduleDirectory().absoluteFile.parentFile

    private companion object {
        /**
         * Each document and the heading of the section that enumerates the tools. The
         * three files are declared inputs of the test task in `app/build.gradle.kts`.
         */
        val DOCUMENTED_SECTIONS = listOf(
            "SECURITY.md" to "Agent file workspace",
            "docs/extending.md" to "Add a workspace tool",
            "docs/architecture.md" to "File and HTTP tools",
        )

        const val EXECUTORS_DIRECTORY = "/data/tools/local/executors/"

        /**
         * A parameter or property typed `AgentWorkspace` — with or without `val`, so an
         * executor that only passes the workspace on is counted too.
         */
        val WORKSPACE_PARAMETER = Regex("""\b\w+\s*:\s*AgentWorkspace\b""")

        val TOOL_NAME = Regex("""const\s+val\s+TOOL_NAME\s*(?::\s*String\s*)?=\s*"([a-z_]+)"""")
    }
}
