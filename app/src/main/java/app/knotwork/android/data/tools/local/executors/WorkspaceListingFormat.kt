package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.domain.services.WorkspaceNamePolicy
import java.time.Instant

/**
 * Shared rendering of workspace file listings for the `list_files` and
 * `find_files` tools, so both present entries identically to the model.
 *
 * Each entry is one line: relative path (control characters escaped), size in
 * bytes, and the last-modified timestamp as an ISO-8601 UTC instant (locale- and timezone-independent, hence
 * stable across devices and in tests). The number of lines is capped so a
 * workspace with very many files cannot, by itself, overflow the context
 * window; the omitted count is reported with a truncation marker.
 */
internal object WorkspaceListingFormat {

    /** Maximum number of file lines emitted before truncation kicks in. */
    const val MAX_LINES: Int = 200

    /**
     * Renders [files] (already filtered and sorted by the caller) as a capped,
     * line-per-file listing.
     *
     * @param files The entries to render.
     * @param emptyMessage Text returned when [files] is empty.
     * @return The formatted listing, with a `[... N more entries truncated]`
     *   line appended when more than [MAX_LINES] entries were supplied.
     */
    fun render(files: List<WorkspaceFile>, emptyMessage: String): String {
        if (files.isEmpty()) return emptyMessage
        val shown = files.take(MAX_LINES)
        val lines = shown.joinToString("\n") { formatEntry(it) }
        val overflow = files.size - shown.size
        return if (overflow > 0) {
            "$lines\n[... $overflow more entries truncated]"
        } else {
            lines
        }
    }

    /**
     * Formats a single entry as `relativePath\tN bytes\t<iso-8601-utc>`. The path is
     * escaped with [WorkspaceNamePolicy.escapeForbidden]: the workspace creates no
     * name with a line break or a tab, but one made before that rule (or put there by
     * anything else) must not be able to split its line into a forged entry.
     */
    private fun formatEntry(file: WorkspaceFile): String {
        val path = WorkspaceNamePolicy.escapeForbidden(file.relativePath)
        return "$path\t${file.sizeBytes} bytes\t${Instant.ofEpochMilli(file.lastModified)}"
    }
}
