package app.knotwork.android.domain.models

/**
 * Typed outcome of
 * [app.knotwork.android.domain.repositories.ToolSettings.updateMcpServer].
 *
 * Replacing an MCP server row by index when the new URL collides with
 * another existing row's URL would produce a `[B, B]` list with the
 * original server's auth / headers silently lost. This sealed result lets
 * the UI detect that case ([UrlCollision]) and surface an inline error
 * before the user discovers the loss later when the agent fails to connect.
 *
 * The downstream MCP router (`ToolRepositoryImpl.distinctMcpConfigs`)
 * still deduplicates defensively — this type only guarantees the
 * **persistence** side stays clean.
 */
sealed interface UpdateMcpServerResult {

    /** The update was persisted as requested. */
    data object Success : UpdateMcpServerResult

    /**
     * Refused to persist because [collidingUrl] already exists on
     * another row (i.e. a row whose original URL differs from the one
     * the user was editing). [collidingDisplayName] is the display name
     * of that existing row (server name, or the URL itself when no name
     * is set) so the UI can show a meaningful message like
     * "A server with this URL already exists: \"Local files\"".
     *
     * @property collidingUrl The URL the user typed in, which already
     *   belongs to a different server row.
     * @property collidingDisplayName Display label of the existing row;
     *   `null` if the row has no explicit name and the UI should fall
     *   back to rendering [collidingUrl].
     */
    data class UrlCollision(val collidingUrl: String, val collidingDisplayName: String?) : UpdateMcpServerResult
}
