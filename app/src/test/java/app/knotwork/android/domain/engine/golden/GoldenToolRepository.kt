package app.knotwork.android.domain.engine.golden

import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.ToolRisk
import app.knotwork.android.domain.repositories.ToolRepository
import org.json.JSONException
import org.json.JSONObject

/**
 * Tool catalogue of the golden harness: a fixed set of the app's built-in tool names, each
 * answering with a recorded output instead of acting on the world.
 *
 * The catalogue is a fixture, not a copy of production: names and risks match the built-in
 * defaults of `ToolRepositoryImpl` (so the approval gate asks where the app would), while the
 * descriptions are short stand-ins — a change to a production tool description does not move
 * a golden trace, a change to how the engine dispatches a tool does. Every execution is
 * recorded with the risk the gate decided on and the arguments the model produced.
 *
 * @param log The run's event log.
 */
internal class GoldenToolRepository(private val log: GoldenEventLog) : ToolRepository {

    override suspend fun getAvailableTools(): List<AgentTool> = CATALOGUE.map { it.agentTool }

    override suspend fun getAllLocalTools(): List<AgentTool> = getAvailableTools()

    override suspend fun executeTool(name: String, arguments: String, context: ToolExecutionContext): String {
        val output = recordedOutput(name, arguments)
        log.record(
            "tool.execute $name risk=${context.gatedRisk} session=${context.sessionId}",
            "arguments" to arguments,
            "output" to output,
        )
        return output
    }

    override suspend fun getRisk(toolName: String, arguments: String): ToolRisk =
        CATALOGUE.firstOrNull { it.agentTool.name == toolName }?.agentTool?.risk ?: ToolRisk.SENSITIVE

    private fun recordedOutput(name: String, arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (e: JSONException) {
            return "$name could not read its arguments: ${e::class.simpleName}"
        }
        return when (name) {
            SEARCH -> "Search results for \"${args.optString("query")}\":\n" +
                "1. ${args.optString("query")} — a recorded encyclopedia summary.\n" +
                "2. ${args.optString("query")} — a second recorded source."
            WRITE -> "Wrote ${args.optString("content").length} characters to ${args.optString("path")}."
            APPEND -> "Appended ${args.optString("content").length} characters to ${args.optString("path")}."
            // Longer than the harness's tool-result budget (1 500 tokens ≈ 6 000 characters)
            // and shorter than the shipped default's (8 000), so the cut point shows which
            // budget a node was given.
            READ -> "Contents of ${args.optString("path")}:\n" +
                (1..LONG_FILE_LINES).joinToString("\n") {
                    "Line $it of the recorded file: a note long enough to count."
                }
            else -> "$name finished."
        }
    }

    /**
     * One catalogue entry.
     *
     * @property agentTool What the agent sees.
     * @property defaultArguments The arguments the scripted model produces for this tool when
     *   the scenario does not script them.
     */
    data class Entry(val agentTool: AgentTool, val defaultArguments: String)

    companion object {
        private const val SEARCH = "search_tool"
        private const val READ = "read_file"
        private const val WRITE = "write_file"
        private const val APPEND = "append_file"
        private const val DELETE = "delete_file"

        /** Lines in the recorded `read_file` body: about 7 000 characters. */
        private const val LONG_FILE_LINES = 115

        /** The harness catalogue, in the order the agent sees it. */
        val CATALOGUE: List<Entry> = listOf(
            entry(SEARCH, "Looks a topic up in an encyclopedia.", ToolRisk.READ_ONLY, "query"),
            entry(READ, "Reads a file from the agent workspace.", ToolRisk.READ_ONLY, "path"),
            entry(WRITE, "Writes a file in the agent workspace.", ToolRisk.SENSITIVE, "path", "content"),
            entry(APPEND, "Appends to a file in the agent workspace.", ToolRisk.SENSITIVE, "path", "content"),
            entry(DELETE, "Deletes a file from the agent workspace.", ToolRisk.DESTRUCTIVE, "path"),
        )

        /**
         * Arguments the scripted model produces for [toolName] when nothing is scripted.
         *
         * @param toolName A tool name, possibly not in the catalogue.
         * @param subject Text that makes the arguments recognisable in a trace (a node label).
         * @return A JSON object as text.
         */
        fun defaultArguments(toolName: String, subject: String): String {
            val template = CATALOGUE.firstOrNull { it.agentTool.name == toolName }?.defaultArguments ?: "{}"
            return template.replace(SUBJECT, JSONObject.quote(subject).removeSurrounding("\""))
        }

        private const val SUBJECT = "<subject>"

        private fun entry(name: String, description: String, risk: ToolRisk, vararg params: String): Entry {
            val properties = params.joinToString(",") { "\"$it\":{\"type\":\"string\"}" }
            val required = params.joinToString(",") { "\"$it\"" }
            val schema = "{\"type\":\"object\",\"properties\":{$properties},\"required\":[$required]}"
            val defaults = params.joinToString(",") { param ->
                val value = when (param) {
                    "path" -> "notes/golden.md"
                    "content" -> "$SUBJECT — recorded content."
                    else -> SUBJECT
                }
                "\"$param\":\"$value\""
            }
            return Entry(AgentTool(name, description, schema, risk), "{$defaults}")
        }
    }
}
