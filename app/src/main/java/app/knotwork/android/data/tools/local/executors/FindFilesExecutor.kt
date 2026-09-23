package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.services.AgentWorkspace
import app.knotwork.android.domain.services.WorkspaceGlob
import org.json.JSONObject
import javax.inject.Inject

/**
 * [LocalToolExecutor] for the built-in `find_files` tool (READ_ONLY).
 *
 * Matches workspace files against a shell-style glob ([WorkspaceGlob]) over
 * their relative paths (an extension glob, a recursive directory prefix, etc.)
 * and renders the matches in the same capped, line-per-file form as
 * `list_files`. Useful when the agent knows a naming pattern but not the exact
 * path.
 *
 * @property workspace The jailed file sandbox whose listing is searched.
 */
class FindFilesExecutor @Inject constructor(private val workspace: AgentWorkspace) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val glob = json.optString("glob", "").trim()
        if (glob.isBlank()) return "Error: missing 'glob' argument."
        // The matcher's cost is linear in the glob's length times each path's; this bound
        // keeps a single call over a full workspace short, not merely finite.
        if (glob.length > WorkspaceGlob.MAX_GLOB_LENGTH) {
            return "Error: the glob is longer than ${WorkspaceGlob.MAX_GLOB_LENGTH} characters."
        }

        return when (val listing = workspace.list()) {
            is WorkspaceResult.Failure -> errorMessage(listing.error)
            is WorkspaceResult.Success -> {
                val matcher = WorkspaceGlob.compile(glob)
                val matches = listing.value.filter { matcher.matches(it.relativePath) }
                WorkspaceListingFormat.render(matches, "No files match '$glob'.")
            }
        }
    }

    /** Maps a [WorkspaceError] to a concise observation string for the agent. */
    private fun errorMessage(error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace,
        WorkspaceError.NotFound,
        WorkspaceError.NotAText,
        WorkspaceError.TooLarge,
        WorkspaceError.AlreadyExists,
        WorkspaceError.QuotaExceeded,
        WorkspaceError.AnchorNotFound,
        is WorkspaceError.AnchorNotUnique,
        WorkspaceError.IsDirectory,
        WorkspaceError.InvalidPath,
        -> "Error: could not search the workspace."
    }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "find_files"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "Find Files"

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Finds files in the agent's private workspace whose relative path matches a glob pattern. " +
                "Supports '*' (within one path segment), '**' (across directories) and '?'. Examples: " +
                "'*.md', 'reports/**', '**/*.json'. Returns matching files with size and last-modified time."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "glob": { "type": "string", "description": "Glob pattern over workspace-relative paths, e.g. '*.md' or 'reports/**'." }
              },
              "required": ["glob"]
            }
        """.trimIndent()
    }
}
