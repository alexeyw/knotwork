package app.knotwork.android.presentation.ui.more

/**
 * Live counters of the More tab, as values. The screen words them in the
 * interface language ([MoreSubtitles]): the ViewModel outlives the screen's
 * re-creation in a new language, so text it wrote would stay in the old one.
 *
 * A `null` counter has not arrived yet; its row shows a dash.
 *
 * @property memory Memory entries and their size on disk.
 * @property activeModelName Name of the active model, or `null` when none is active.
 * @property modelsLoaded Whether the model list has arrived, so "no model" is known.
 * @property prompts Prompt categories and prompts.
 * @property tasks Runs in flight and runs waiting behind another.
 * @property tasksBadge Running tasks count surfaced in the trailing badge
 *   pill on the Active tasks row.
 * @property versionName The app's version name.
 * @property versionCode The app's version code.
 * @property savedPresets Pipeline presets the user saved.
 * @property files The workspace's files.
 * @property archivedChats Number of archived chats. Rendered as the archive
 *   row's subtitle — never as a badge, because a badge says "needs attention"
 *   and an archive never does.
 * @property network When the app last reached the network.
 */
data class MoreUiState(
    val memory: MoreMemoryCount? = null,
    val activeModelName: String? = null,
    val modelsLoaded: Boolean = false,
    val prompts: MorePromptCount? = null,
    val tasks: MoreTaskCount? = null,
    val tasksBadge: Int = 0,
    val versionName: String = "",
    val versionCode: Int = 0,
    val savedPresets: Int? = null,
    val files: MoreWorkspaceStat? = null,
    val archivedChats: Int = 0,
    val network: MoreNetworkStat? = null,
)

/**
 * Memory entries and their size.
 *
 * @property chunks Entries stored.
 * @property bytes Their approximate size on disk.
 */
data class MoreMemoryCount(val chunks: Int, val bytes: Long)

/**
 * Prompt categories and prompts.
 *
 * @property categories Distinct node types with prompts.
 * @property prompts Prompts stored, bundled and saved.
 */
data class MorePromptCount(val categories: Int, val prompts: Int)

/**
 * Runs in flight and waiting.
 *
 * @property running Runs doing work.
 * @property queued Runs waiting behind another.
 */
data class MoreTaskCount(val running: Int, val queued: Int)

/** The workspace's files, for the Files row. */
sealed interface MoreWorkspaceStat {
    /** The listing failed. */
    data object Unavailable : MoreWorkspaceStat

    /** No files. */
    data object Empty : MoreWorkspaceStat

    /**
     * Some files.
     *
     * @property count Files in the workspace.
     * @property bytes Their total size.
     */
    data class Files(val count: Int, val bytes: Long) : MoreWorkspaceStat
}

/** When the app last reached the network, for the footer. */
sealed interface MoreNetworkStat {
    /** No outbound call since the process started. */
    data object NoCallsYet : MoreNetworkStat

    /** An outbound call within the last minute. */
    data object JustNow : MoreNetworkStat

    /**
     * The last outbound call was a while ago.
     *
     * @property minutes Whole minutes since it.
     */
    data class Quiet(val minutes: Long) : MoreNetworkStat
}
