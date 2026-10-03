package app.knotwork.design.screens.discover

/**
 * Visual variant of the model-discovery **detail** surface (one repository's
 * files, opened from a [DiscoverModelRow] tap).
 *
 * The `:app` layer maps its detail UI state onto this enum so the catalog
 * stays free of `:app`/domain types.
 */
enum class DiscoverDetailVisualState {
    /** Fetching the repository detail; renders skeletons. */
    Loading,

    /** Detail loaded; renders the header + installable file list. */
    Loaded,

    /** Network/parse failure; error + Retry CTA. */
    Error,
}

/** Trailing state of a [DiscoverFileRow]. */
sealed interface DiscoverFileStatus {
    /** Not installed; renders the `Install` action. */
    data object Idle : DiscoverFileStatus

    /**
     * Download in flight; renders a percent line + progress bar + cancel.
     *
     * @property progress 0..100 integer percentage.
     */
    data class Downloading(val progress: Int) : DiscoverFileStatus

    /** Already present locally; renders an `Installed` badge, no action. */
    data object Installed : DiscoverFileStatus
}

/**
 * One installable `.litertlm` file inside the repository.
 *
 * @property fileName on-disk file name (mono).
 * @property sizeLabel host-formatted size (e.g. `"2.4 GB"`), or `null` to hide.
 * @property status trailing install state.
 */
data class DiscoverFileRow(val fileName: String, val sizeLabel: String?, val status: DiscoverFileStatus)

/**
 * Top-level immutable input to `DiscoverDetailContent`.
 *
 * @property visualState rendering branch.
 * @property title repository display name (header).
 * @property repoId fully-qualified repo id (mono sub-line).
 * @property metaLine host-formatted stats line (`"↓ 12.3k · ♥ 45"`).
 * @property license parsed license id, or `null`.
 * @property gated `true` to show the access-gated notice + token field.
 * @property files installable files; non-empty in [DiscoverDetailVisualState.Loaded].
 * @property errorMessage user-visible error; non-null iff [visualState] is [DiscoverDetailVisualState.Error].
 * @property pendingLicenseFileName file awaiting license confirmation; when
 *   non-null the catalog shows the confirm dialog. `null` = dialog hidden.
 * @property tokenInput current Hugging Face token text (gated repos only).
 * @property tokenRevealed `true` to render the token in clear text.
 */
data class DiscoverDetailViewState(
    val visualState: DiscoverDetailVisualState,
    val title: String = "",
    val repoId: String = "",
    val metaLine: String = "",
    val license: String? = null,
    val gated: Boolean = false,
    val files: List<DiscoverFileRow> = emptyList(),
    val errorMessage: String? = null,
    val pendingLicenseFileName: String? = null,
    val tokenInput: String = "",
    val tokenRevealed: Boolean = false,
) {
    init {
        require((visualState == DiscoverDetailVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/**
 * Stable callback bundle accepted by `DiscoverDetailContent`.
 */
class DiscoverDetailCallbacks(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenModelCard: () -> Unit = {},
    /** User tapped Install on a file — opens the license-confirm dialog. */
    val onInstallClick: (String) -> Unit = {},
    /** User confirmed the license — starts the download. */
    val onLicenseConfirm: (String) -> Unit = {},
    /** User dismissed the license dialog. */
    val onLicenseDismiss: () -> Unit = {},
    /** User cancelled an in-flight install for a file. */
    val onCancelInstall: (String) -> Unit = {},
    val onTokenChange: (String) -> Unit = {},
    val onTokenPaste: () -> Unit = {},
    val onToggleTokenReveal: () -> Unit = {},
)

/** Convenience factory returning a callbacks bundle that ignores every event. */
fun noopDiscoverDetailCallbacks(): DiscoverDetailCallbacks = DiscoverDetailCallbacks()
