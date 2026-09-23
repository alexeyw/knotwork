package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.services.AgentWorkspace
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] for the built-in `list_files` tool (READ_ONLY).
 *
 * Lists the workspace's files (recursively) as a stable, path-sorted, capped
 * listing — one line per file with its size and last-modified timestamp. An
 * optional `path` argument narrows the listing to a single sub-directory; it is
 * validated through the workspace containment gate so a traversal attempt is
 * refused with a typed observation rather than escaping the sandbox.
 *
 * @property workspace The jailed file sandbox whose listing is rendered.
 */
class ListFilesExecutor @Inject constructor(private val workspace: AgentWorkspace) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val rawPath = json.optString("path", "").trim()
        val requested = rawPath.takeUnless { it.isEmpty() || it == "." || it == "/" }

        // When a sub-directory is requested, resolve it through the containment gate first —
        // both to refuse an out-of-workspace path precisely, and to obtain the *canonical*
        // relative path to filter by. The model's path may carry `./` or `..` segments
        // (`./reports`, `sub/../reports`) that a raw string comparison against the always-
        // canonical listing entries would never match; resolving normalises them. A path
        // that canonicalises to the root itself yields an empty relative path, which we
        // treat as "list the whole workspace".
        val canonicalPrefix = if (requested != null) {
            when (val resolved = workspace.resolve(requested)) {
                is WorkspaceResult.Failure -> return errorMessage(requested, resolved.error)
                is WorkspaceResult.Success -> resolved.value.relativePath.takeIf { it.isNotEmpty() }
            }
        } else {
            null
        }

        return when (val listing = workspace.list()) {
            is WorkspaceResult.Failure -> errorMessage(rawPath, listing.error)
            is WorkspaceResult.Success -> {
                val files = if (canonicalPrefix == null) {
                    listing.value
                } else {
                    listing.value.filter {
                        it.relativePath == canonicalPrefix || it.relativePath.startsWith("$canonicalPrefix/")
                    }
                }
                val emptyMessage = if (canonicalPrefix == null) {
                    "Workspace is empty."
                } else {
                    "No files under '$requested'."
                }
                WorkspaceListingFormat.render(files, emptyMessage)
            }
        }
    }

    /** Maps a [WorkspaceError] to a concise observation string for the agent. */
    private fun errorMessage(path: String, error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace -> "Error: path '$path' is outside the workspace."
        WorkspaceError.NotFound -> "Error: '$path' not found."
        WorkspaceError.InvalidPath -> WorkspaceToolMessages.INVALID_PATH
        WorkspaceError.NotAText,
        WorkspaceError.TooLarge,
        WorkspaceError.AlreadyExists,
        WorkspaceError.QuotaExceeded,
        WorkspaceError.AnchorNotFound,
        is WorkspaceError.AnchorNotUnique,
        WorkspaceError.IsDirectory,
        -> "Error: could not list '$path'."
    }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "list_files"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "List Files"

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Lists files in the agent's private workspace, one per line with size and last-modified time. " +
                "Pass an optional 'path' to list only a sub-directory. Use this to discover what files exist " +
                "before reading or writing them."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Optional workspace-relative sub-directory to list. Omit to list the whole workspace." }
              }
            }
        """.trimIndent()
    }
}
