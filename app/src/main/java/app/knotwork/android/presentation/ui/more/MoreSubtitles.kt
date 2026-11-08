package app.knotwork.android.presentation.ui.more

import android.content.Context
import android.content.res.Resources
import app.knotwork.android.R
import app.knotwork.android.presentation.common.ByteUnits

/**
 * The More tab's row subtitles and footer line, written in the interface
 * language from the counters of [MoreUiState].
 *
 * @property memory Memory row, e.g. "1 248 chunks · 14.2 MB".
 * @property models Models row, e.g. "gemma-4-E2B · active".
 * @property prompts Prompts row, e.g. "5 categories · 24 prompts".
 * @property tasks Active-tasks row, e.g. "2 running · 4 queued".
 * @property metrics Live-metrics row.
 * @property settings Settings row.
 * @property about About row, e.g. "v0.1 · build 1".
 * @property library Library row, e.g. "3 saved presets".
 * @property files Files row, e.g. "7 files · 1.5 MB".
 * @property network Footer line; empty until the first reading.
 * @property networkOk Whether the footer dot reads as on-device.
 */
data class MoreSubtitles(
    val memory: String,
    val models: String,
    val prompts: String,
    val tasks: String,
    val metrics: String,
    val settings: String,
    val about: String,
    val library: String,
    val files: String,
    val network: String,
    val networkOk: Boolean,
) {
    /** Builds the subtitles. */
    companion object {
        /**
         * Words [state] in the language of [context]'s resources.
         *
         * @param state The counters.
         * @param context Context whose resources are in the interface language.
         * @return The subtitles; a counter not yet known reads as a dash.
         */
        fun of(state: MoreUiState, context: Context): MoreSubtitles {
            val resources = context.resources
            val sizes = ByteUnits.of(resources)
            return MoreSubtitles(
                memory = state.memory?.let { memoryLine(it, resources, sizes) } ?: DASH,
                models = when {
                    state.activeModelName != null ->
                        resources.getString(R.string.more_models_active, state.activeModelName)
                    state.modelsLoaded -> resources.getString(R.string.more_models_none)
                    else -> DASH
                },
                prompts = state.prompts?.let {
                    resources.getString(R.string.more_prompts_counts, it.categories, it.prompts)
                } ?: DASH,
                tasks = state.tasks?.let {
                    if (it.running == 0 && it.queued == 0) {
                        resources.getString(R.string.more_tasks_none)
                    } else {
                        resources.getString(R.string.more_tasks_counts, it.running, it.queued)
                    }
                } ?: DASH,
                metrics = resources.getString(R.string.more_metrics_subtitle),
                settings = resources.getString(R.string.more_settings_subtitle),
                about = if (state.versionName.isEmpty()) {
                    DASH
                } else {
                    resources.getString(R.string.more_about_version, state.versionName, state.versionCode)
                },
                library = state.savedPresets?.let {
                    if (it == 0) {
                        resources.getString(R.string.more_library_none)
                    } else {
                        resources.getQuantityString(R.plurals.more_library_saved_presets, it, it)
                    }
                } ?: DASH,
                files = when (val files = state.files) {
                    null, MoreWorkspaceStat.Unavailable -> DASH
                    MoreWorkspaceStat.Empty -> resources.getString(R.string.more_files_empty)
                    is MoreWorkspaceStat.Files -> resources.getQuantityString(
                        R.plurals.more_files_counts,
                        files.count,
                        files.count,
                        kilobytesOrMegabytes(files.bytes, sizes),
                    )
                },
                network = when (val network = state.network) {
                    null -> ""
                    MoreNetworkStat.NoCallsYet -> resources.getString(R.string.more_network_no_calls_yet)
                    MoreNetworkStat.JustNow -> resources.getString(R.string.more_network_just_now)
                    is MoreNetworkStat.Quiet ->
                        resources.getString(R.string.more_network_quiet, network.minutes)
                },
                networkOk = state.network != MoreNetworkStat.JustNow,
            )
        }

        private fun memoryLine(memory: MoreMemoryCount, resources: Resources, sizes: ByteUnits): String {
            val count = if (memory.chunks > GROUPED_THRESHOLD) {
                memory.chunks.toString().reversed().chunked(size = GROUP_DIGITS).joinToString(separator = " ")
                    .reversed()
            } else {
                memory.chunks.toString()
            }
            val size = if (memory.bytes <= 0L) sizes.inMegabytes("0") else kilobytesOrMegabytes(memory.bytes, sizes)
            return resources.getQuantityString(R.plurals.more_memory_counts, memory.chunks, count, size)
        }

        /** Whole KiB below one MiB, MiB with one decimal from there — the More tab's compact sizes. */
        private fun kilobytesOrMegabytes(bytes: Long, sizes: ByteUnits): String = if (bytes < MEGABYTE) {
            sizes.inKilobytes((bytes / KILOBYTE).toString())
        } else {
            sizes.inMegabytes(sizes.number(bytes.toDouble() / MEGABYTE, decimals = 1))
        }

        private const val DASH = "—"
        private const val GROUPED_THRESHOLD = 999
        private const val GROUP_DIGITS = 3
        private const val KILOBYTE = 1024L
        private const val MEGABYTE = KILOBYTE * 1024L
    }
}
