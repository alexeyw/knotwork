package app.knotwork.android.domain.models

/**
 * Snapshot of how much of the agent workspace's storage budget is consumed.
 *
 * Surfaced by [app.knotwork.android.domain.services.AgentWorkspace.usage] so the
 * Files screen can show a quota indicator. The workspace enforces the same
 * [limitBytes] on every write (a write that would push [usedBytes] past it is
 * refused with [WorkspaceError.QuotaExceeded]); this model is the read-side view
 * of that ceiling.
 *
 * @property usedBytes Total bytes currently occupied by regular files in the
 *   workspace (transient atomic-write scratch files are excluded). Never negative.
 * @property limitBytes The workspace-wide ceiling in bytes, mirroring
 *   [app.knotwork.android.domain.repositories.ToolSettings.workspaceMaxTotalBytes].
 */
data class WorkspaceUsage(val usedBytes: Long, val limitBytes: Long) {
    /**
     * Fraction of the budget consumed in `[0, 1]` (clamped). Returns `0` when the
     * limit is non-positive so a misconfigured ceiling can never divide by zero or
     * report a negative fill.
     */
    val fraction: Float
        get() = if (limitBytes <= 0L) 0f else (usedBytes.toFloat() / limitBytes).coerceIn(0f, 1f)
}
