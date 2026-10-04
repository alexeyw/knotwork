package app.knotwork.android.data.local

import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.UpdateMcpServerResult

/**
 * Pure collision-detection used by
 * [app.knotwork.android.data.local.settings.ToolSettingsStore.updateMcpServer]
 * before it persists the new row.
 *
 * The check is extracted to an internal object so the rule can be
 * exercised by a fast pure-Kotlin unit test without any DataStore
 * plumbing. The persistence code path is a one-liner around this helper.
 */
internal object McpServerCollisionCheck {

    /**
     * Returns [UpdateMcpServerResult.UrlCollision] when [newUrl] already
     * belongs to a row in [currentList] **other than** the one identified
     * by [originalUrl] (the row currently being edited).
     *
     * Returns `null` when:
     *  - [newUrl] is unique among other rows, or
     *  - [newUrl] equals [originalUrl] (no-op edit of the same row), or
     *  - the row being edited does not exist in [currentList] (add path —
     *    no other row can shadow a new URL since the URL itself is the
     *    only identity surface).
     *
     * Comparison is case-sensitive and does not normalise the URL. The
     * caller is responsible for trimming whitespace before calling.
     *
     * @param currentList The persisted MCP server list as read from
     *   [app.knotwork.android.data.local.settings.ToolSettingsStore.mcpServers].
     * @param originalUrl The URL of the row the user is editing. Pass
     *   [newUrl] verbatim from the Add-mode path so the function trivially
     *   returns `null` (Add isn't a "replace", every Add path uses a
     *   different code branch in `ToolSettingsStore`).
     * @param newUrl The URL the form is about to persist.
     */
    fun detectCollision(
        currentList: List<McpServerConfig>,
        originalUrl: String,
        newUrl: String,
    ): UpdateMcpServerResult.UrlCollision? {
        if (newUrl == originalUrl) return null
        val collision = currentList.firstOrNull { it.url == newUrl && it.url != originalUrl }
            ?: return null
        return UpdateMcpServerResult.UrlCollision(
            collidingUrl = collision.url,
            collidingDisplayName = collision.name?.takeIf { it.isNotBlank() },
        )
    }
}
