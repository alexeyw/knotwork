package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.services.AgentWorkspace
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] for the built-in `write_file` tool (SENSITIVE).
 *
 * Writes UTF-8 [content] to a workspace-relative `path`. Overwriting is **never
 * implicit**: an existing file is replaced only when the call passes
 * `overwrite: true`. Without it, writing over an existing file is refused with a
 * readable error telling the agent to retry with the flag — this stops the model
 * from silently clobbering a file it merely meant to create. The underlying
 * workspace write is atomic (staged scratch file + rename) and quota-checked
 * before any bytes land, so a refused or failed write never corrupts existing
 * content.
 *
 * Every [WorkspaceError] is mapped to a precise observation string instead of
 * throwing — the agent sees the cause and can react.
 *
 * @property workspace The jailed file sandbox every write funnels through.
 */
class WriteFileExecutor @Inject constructor(private val workspace: AgentWorkspace) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val path = json.optString("path", "")
        if (path.isBlank()) return "Error: missing 'path' argument."
        val content = json.optString("content", "")
        val overwrite = json.optBoolean("overwrite", false)

        return when (val result = workspace.writeText(path, content, overwrite)) {
            is WorkspaceResult.Failure -> errorMessage(path, result.error)
            is WorkspaceResult.Success ->
                "Wrote ${result.value.sizeBytes} bytes to '${result.value.relativePath}'."
        }
    }

    /** Maps a [WorkspaceError] to a concise observation string for the agent. */
    private fun errorMessage(path: String, error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace -> "Error: path '$path' is outside the workspace."
        WorkspaceError.AlreadyExists ->
            "Error: '$path' already exists. Pass \"overwrite\": true to replace it."
        WorkspaceError.IsDirectory -> "Error: '$path' is a directory, not a file — choose a different path."
        WorkspaceError.TooLarge -> "Error: content for '$path' exceeds the per-file size limit."
        WorkspaceError.QuotaExceeded -> "Error: writing '$path' would exceed the workspace storage quota."
        WorkspaceError.InvalidPath -> WorkspaceToolMessages.INVALID_PATH
        WorkspaceError.NotFound,
        WorkspaceError.NotAText,
        WorkspaceError.AnchorNotFound,
        is WorkspaceError.AnchorNotUnique,
        -> "Error: '$path' could not be written."
    }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "write_file"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "Write File"

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Writes a UTF-8 text file to the agent's private workspace, creating parent directories as " +
                "needed. Creating a new file is the default; to replace an existing file you must pass " +
                "\"overwrite\": true (without it, writing over an existing file is refused so content is " +
                "never clobbered by accident). Use edit_file for a small change to an existing file."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Workspace-relative path of the file to write (e.g. reports/summary.md)." },
                "content": { "type": "string", "description": "Full UTF-8 text content to write." },
                "overwrite": { "type": "boolean", "description": "Replace an existing file when true. Default false: writing over an existing file is refused." }
              },
              "required": ["path", "content"]
            }
        """.trimIndent()
    }
}
