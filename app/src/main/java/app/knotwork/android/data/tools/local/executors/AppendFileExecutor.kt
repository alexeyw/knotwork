package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.services.AgentWorkspace
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] for the built-in `append_file` tool (SENSITIVE).
 *
 * Appends UTF-8 [content] to the end of a workspace-relative `path`, creating the
 * file (and parent directories) on the first call. Unlike `write_file` there is no
 * `overwrite` flag and an existing file is never refused: appending is additive, so
 * this is the tool for "accumulate into a daily log / report" without first reading
 * the file back. The whole read-existing → concatenate → atomic-rewrite runs under
 * the workspace lock and is quota-checked, so concurrent appends never interleave and
 * the workspace can never be grown past its size limits.
 *
 * Every [WorkspaceError] is mapped to a precise observation string instead of
 * throwing — the agent sees the cause and can react.
 *
 * @property workspace The jailed file sandbox every append funnels through.
 */
class AppendFileExecutor @Inject constructor(private val workspace: AgentWorkspace) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val path = json.optString("path", "")
        if (path.isBlank()) return "Error: missing 'path' argument."
        val content = json.optString("content", "")

        return when (val result = workspace.appendText(path, content)) {
            is WorkspaceResult.Failure -> errorMessage(path, result.error)
            is WorkspaceResult.Success ->
                "Appended ${content.toByteArray(Charsets.UTF_8).size} bytes to '${result.value.relativePath}' " +
                    "(now ${result.value.sizeBytes} bytes)."
        }
    }

    /** Maps a [WorkspaceError] to a concise observation string for the agent. */
    private fun errorMessage(path: String, error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace -> "Error: path '$path' is outside the workspace."
        WorkspaceError.IsDirectory -> "Error: '$path' is a directory, not a file — choose a different path."
        WorkspaceError.NotAText -> "Error: '$path' is not a UTF-8 text file, so text cannot be appended to it."
        WorkspaceError.TooLarge -> "Error: appending to '$path' would exceed the per-file size limit."
        WorkspaceError.QuotaExceeded -> "Error: appending to '$path' would exceed the workspace storage quota."
        WorkspaceError.InvalidPath -> WorkspaceToolMessages.INVALID_PATH
        WorkspaceError.NotFound,
        WorkspaceError.AlreadyExists,
        WorkspaceError.AnchorNotFound,
        is WorkspaceError.AnchorNotUnique,
        -> "Error: '$path' could not be appended to."
    }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "append_file"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "Append File"

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Appends UTF-8 text to the END of a file in the agent's private workspace, creating the file " +
                "and parent directories if they do not exist. Existing content is always preserved (there is " +
                "no overwrite). Use this to accumulate entries in a log or report; use write_file to create or " +
                "replace a whole file, or edit_file to change part of one."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Workspace-relative path of the file to append to (e.g. reports/daily.md)." },
                "content": { "type": "string", "description": "UTF-8 text to append to the end of the file." }
              },
              "required": ["path", "content"]
            }
        """.trimIndent()
    }
}
