package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.services.AgentWorkspace
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] for the built-in `delete_file` tool (DESTRUCTIVE).
 *
 * Removes a single regular file from the workspace. Deletion is irreversible, so
 * the tool is classified DESTRUCTIVE: the Human-in-the-Loop gate always requires
 * an explicit, typed confirmation before this executor ever runs. Only regular
 * files are deletable — a path that resolves to a directory (or to nothing) is
 * reported as not found rather than silently traversed.
 *
 * Every [WorkspaceError] is mapped to a precise observation string instead of
 * throwing — the agent sees the cause and can react.
 *
 * @property workspace The jailed file sandbox every deletion funnels through.
 */
class DeleteFileExecutor @Inject constructor(private val workspace: AgentWorkspace) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val path = json.optString("path", "")
        if (path.isBlank()) return "Error: missing 'path' argument."

        return when (val result = workspace.delete(path)) {
            is WorkspaceResult.Failure -> errorMessage(path, result.error)
            is WorkspaceResult.Success -> "Deleted '$path'."
        }
    }

    /** Maps a [WorkspaceError] to a concise observation string for the agent. */
    private fun errorMessage(path: String, error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace -> "Error: path '$path' is outside the workspace."
        WorkspaceError.NotFound -> "Error: file '$path' not found."
        WorkspaceError.InvalidPath -> WorkspaceToolMessages.INVALID_PATH
        WorkspaceError.AlreadyExists,
        WorkspaceError.QuotaExceeded,
        WorkspaceError.NotAText,
        WorkspaceError.TooLarge,
        WorkspaceError.AnchorNotFound,
        is WorkspaceError.AnchorNotUnique,
        WorkspaceError.IsDirectory,
        -> "Error: '$path' could not be deleted."
    }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "delete_file"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "Delete File"

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Deletes a single file from the agent's private workspace. This is irreversible and always " +
                "requires the user to confirm before it runs. Only regular files can be deleted."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Workspace-relative path of the file to delete." }
              },
              "required": ["path"]
            }
        """.trimIndent()
    }
}
