package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.services.AgentWorkspace
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] for the built-in `edit_file` tool (SENSITIVE).
 *
 * Edits an existing workspace file by replacing the single occurrence of an
 * `oldText` anchor with `newText` — the find-replace pattern proven in agentic
 * coding systems. The anchor must address exactly one fragment: if it matches
 * nowhere the edit is refused, and if it matches more than once the agent is
 * told the occurrence count and asked for a more specific anchor, so a change is
 * never applied to the wrong spot. An empty `newText` deletes the matched
 * fragment. The underlying read-modify-write is atomic and quota-checked.
 *
 * Every [WorkspaceError] is mapped to a precise observation string instead of
 * throwing — the agent sees the cause and can react.
 *
 * @property workspace The jailed file sandbox every edit funnels through.
 */
class EditFileExecutor @Inject constructor(private val workspace: AgentWorkspace) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val path = json.optString("path", "")
        if (path.isBlank()) return "Error: missing 'path' argument."
        val oldText = json.optString("oldText", "")
        if (oldText.isEmpty()) return "Error: 'oldText' must be a non-empty anchor to locate the edit."
        val newText = json.optString("newText", "")

        return when (val result = workspace.editText(path, oldText, newText)) {
            is WorkspaceResult.Failure -> errorMessage(path, result.error)
            is WorkspaceResult.Success ->
                "Edited '${result.value.relativePath}' (${result.value.sizeBytes} bytes)."
        }
    }

    /** Maps a [WorkspaceError] to a concise observation string for the agent. */
    private fun errorMessage(path: String, error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace -> "Error: path '$path' is outside the workspace."
        WorkspaceError.NotFound -> "Error: file '$path' not found."
        WorkspaceError.NotAText -> "Error: '$path' is not a UTF-8 text file and cannot be edited."
        WorkspaceError.AnchorNotFound -> "Error: 'oldText' was not found in '$path'."
        is WorkspaceError.AnchorNotUnique ->
            "Error: 'oldText' found ${error.count} occurrences in '$path', provide a more specific anchor."
        WorkspaceError.TooLarge -> "Error: the edited content of '$path' exceeds the per-file size limit."
        WorkspaceError.QuotaExceeded -> "Error: editing '$path' would exceed the workspace storage quota."
        WorkspaceError.IsDirectory -> "Error: '$path' is a directory, not a file."
        WorkspaceError.InvalidPath -> WorkspaceToolMessages.INVALID_PATH
        WorkspaceError.AlreadyExists -> "Error: '$path' could not be edited."
    }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "edit_file"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "Edit File"

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Edits an existing text file in the agent's private workspace by replacing the single " +
                "occurrence of 'oldText' with 'newText'. The 'oldText' anchor must appear exactly once " +
                "in the file — include enough surrounding context to make it unique, or the edit is " +
                "refused. An empty 'newText' deletes the matched fragment. Use write_file to create a " +
                "file or replace it wholesale."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Workspace-relative path of the file to edit." },
                "oldText": { "type": "string", "description": "Unique fragment to locate. Must occur exactly once; add surrounding context to disambiguate." },
                "newText": { "type": "string", "description": "Replacement text. Empty to delete the matched fragment." }
              },
              "required": ["path", "oldText", "newText"]
            }
        """.trimIndent()
    }
}
