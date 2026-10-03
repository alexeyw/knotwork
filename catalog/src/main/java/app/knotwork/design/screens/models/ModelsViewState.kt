package app.knotwork.design.screens.models

/**
 * Visual variant of the Models surface.
 *
 * `Default` is the only state that actually shows content. The header subtitle
 * is computed by the host (`models_subtitle_*` strings) based on the
 * collections in the view state.
 */
enum class ModelsVisualState {
    /** Initial fetch in progress; renders a centred loader. */
    Loading,

    /** No installed models, no presets matching, no downloads in flight. */
    Empty,

    /** Normal authoring surface — active card, presets list, download rows. */
    Default,

    /** Fatal load failure — message + retry CTA. */
    Error,
}

/**
 * Lightweight projection of the currently active model rendered in the
 * top "ACTIVE" card.
 *
 * @property displayName monospace model filename or human-readable label.
 * @property meta "1.4 GB · NPU · QNN backend"-style mono description.
 * @property visionSupported whether the user has marked this model as able to
 *   read image attachments. Drives the "Image support" toggle on the active
 *   card; defaults to `false` (text-only).
 * @property audioSupported whether the user has marked this model as able to
 *   transcribe audio clips. Drives the "Audio support" toggle on the active
 *   card; defaults to `false`.
 */
data class ActiveModelRow(
    val id: Long,
    val displayName: String,
    val meta: String,
    val visionSupported: Boolean = false,
    val audioSupported: Boolean = false,
)

/** Per-preset row content. */
data class PresetRow(val id: String, val name: String, val source: String, val status: PresetStatus)

/** Trailing state of a [PresetRow]. Discriminates the four documented variants. */
sealed interface PresetStatus {
    /** Not yet downloaded — trailing `↓ Get` button. */
    data class Idle(val sizeMeta: String? = null) : PresetStatus

    /**
     * Download in progress. The catalog renders a percent line + progress
     * bar + cancel-X icon.
     *
     * @property progress 0..100 integer percentage.
     * @property metaLine optional `"64% · 8.4 MB/s · 1.47 / 2.3 GB · ETA 01:38"` formatted line.
     * Falls back to `"$progress%"` when omitted.
     */
    data class Downloading(val progress: Int, val metaLine: String? = null) : PresetStatus

    /**
     * Saved locally but not the currently active model — trailing
     * `Activate` button that flips the row to [Active] on tap.
     */
    data class OnDisk(val sizeMeta: String? = null) : PresetStatus

    /**
     * Saved locally **and** currently active — trailing `ACTIVE` pill
     * with a status dot, no button. Exactly one row per [ModelsViewState]
     * should carry this status.
     */
    data class Active(val sizeMeta: String? = null) : PresetStatus
}

/**
 * Top-level immutable input to `ModelsContent`.
 *
 * @property visualState rendering branch — drives loader / empty / default
 * / error layouts.
 * @property active currently active model, or `null` if none is selected.
 * @property authToken HuggingFace Bearer token; empty when none stored.
 * @property customUrl user-supplied URL for direct download.
 * @property customUrlEnabled toggles the `Get` button availability when
 * a download is already in flight.
 * @property customDownload optional in-flight progress row rendered
 * directly under the Custom URL field when the user-supplied download
 * does not match any [presets] entry (e.g. a custom HuggingFace URL).
 * `null` when no custom download is active.
 * @property presets list of available preset model rows.
 * @property downloadedRows on-disk models that are **not** in [presets]
 * — i.e. custom downloads. Rendered as a dedicated "Downloaded models"
 * section so the user can find and activate them.
 * @property subtitle TopAppBar subtitle (e.g. `"1 active · 2 on disk · 3.3 GB"`).
 * @property errorMessage user-visible error when [visualState] is [ModelsVisualState.Error].
 * @property performance state of the Performance card rendered below the
 * Active-model card (per-model TTFT / decode speed / peak memory + benchmark),
 * or `null` to omit the card entirely.
 */
data class ModelsViewState(
    val visualState: ModelsVisualState,
    val active: ActiveModelRow? = null,
    val authToken: String = "",
    val customUrl: String = "",
    val customUrlEnabled: Boolean = true,
    val customDownload: PresetStatus.Downloading? = null,
    val presets: List<PresetRow> = emptyList(),
    val downloadedRows: List<PresetRow> = emptyList(),
    val subtitle: String = "",
    val errorMessage: String? = null,
    val performance: PerformanceCardState? = null,
) {
    init {
        require((visualState == ModelsVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/** One-shot callbacks consumed by `ModelsContent`. */
class ModelsCallbacks(
    val onBack: () -> Unit = {},
    val onDiscover: () -> Unit = {},
    val onAuthTokenChange: (String) -> Unit = {},
    val onAuthTokenPaste: () -> Unit = {},
    val onCustomUrlChange: (String) -> Unit = {},
    val onCustomUrlSubmit: () -> Unit = {},
    val onPresetDownload: (String) -> Unit = {},
    val onPresetCancelDownload: (String) -> Unit = {},
    val onPresetActivate: (String) -> Unit = {},
    val onPresetDelete: (String) -> Unit = {},
    val onCustomDownloadCancel: () -> Unit = {},
    val onToggleVision: (Boolean) -> Unit = {},
    val onToggleAudio: (Boolean) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRunBenchmark: () -> Unit = {},
    val onCancelBenchmark: () -> Unit = {},
    val onShareBenchmark: () -> Unit = {},
    val onDismissBenchmark: () -> Unit = {},
)

/** Convenience factory returning a no-op callback bundle. */
fun noopModelsCallbacks(): ModelsCallbacks = ModelsCallbacks()
