package app.knotwork.android.presentation.ui.help

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.models.BundledDocument
import app.knotwork.android.domain.models.DocumentationTarget
import app.knotwork.android.domain.repositories.BundledDocumentationRepository
import app.knotwork.android.domain.repositories.NetworkStateRepository
import app.knotwork.android.domain.usecases.ResolveDocumentationLinkUseCase
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * What the reader is doing with a tapped link, once resolved.
 */
sealed interface HelpReaderEffect {

    /**
     * Open another bundled document in the reader.
     *
     * @property id The document's registry id.
     * @property anchor Heading anchor to arrive at, or `null` for its top.
     */
    data class OpenDocument(val id: String, val anchor: String?) : HelpReaderEffect

    /**
     * Hand a URL to the browser.
     *
     * @property url The address.
     */
    data class OpenBrowser(val url: String) : HelpReaderEffect

    /**
     * Copy an address the user cannot reach right now.
     *
     * @property url The address.
     */
    data class CopyLink(val url: String) : HelpReaderEffect
}

/**
 * State of the reader.
 *
 * @property markdown The document's text, empty until loaded.
 * @property loading Whether the read is still in flight.
 * @property failed Whether the copy shipped with the app could not be read.
 * @property anchorOffset Character offset to scroll to, or `null`.
 * @property anchorMarked Whether the arrival mark is still showing.
 * @property offlineUrl Address of a body link refused for want of a network, or
 *   `null` when no refusal is showing.
 */
data class HelpReaderUiState(
    val markdown: String = "",
    val loading: Boolean = true,
    val failed: Boolean = false,
    val anchorOffset: Int? = null,
    val anchorMarked: Boolean = false,
    val offlineUrl: String? = null,
)

/**
 * Drives the reader for one bundled document.
 *
 * The work here is deliberately thin. Which heading an anchor names and where a
 * relative link points were both decided by the build and shipped in the
 * document's index, so this loads text, looks answers up, and decides the one
 * thing the build cannot know: whether the network a link needs exists.
 *
 * @property repository Source of the bundled text and its index.
 * @property resolveLink Looks a tapped target up in the index.
 * @property networkState Repository the connectivity check reads from.
 * @property savedStateHandle Carries the document id and the optional anchor.
 */
@HiltViewModel
class HelpReaderViewModel @Inject constructor(
    private val repository: BundledDocumentationRepository,
    private val resolveLink: ResolveDocumentationLinkUseCase,
    private val networkState: NetworkStateRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Registry id of the document being read. */
    val documentId: String = checkNotNull(savedStateHandle[NavRoutes.HELP_DOCUMENT_ID_ARG])

    /** Anchor the reader should arrive at, or `null`. */
    private val anchor: String? = savedStateHandle.get<String>(NavRoutes.HELP_DOCUMENT_ANCHOR_ARG)
        ?.takeIf { it.isNotEmpty() }

    private val _uiState = MutableStateFlow(HelpReaderUiState())

    /** The reader's state. */
    val uiState: StateFlow<HelpReaderUiState> = _uiState.asStateFlow()

    private var document: BundledDocument? = null

    init {
        load()
    }

    /** Reads the document and resolves the arrival anchor. */
    fun load() {
        _uiState.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val documents = repository.documents().getOrElse {
                Timber.e(it, "Could not read the bundled documentation index")
                _uiState.update { state -> state.copy(loading = false, failed = true) }
                return@launch
            }
            val entry = documents.firstOrNull { it.id == documentId }
            val text = entry?.let { repository.content(documentId).getOrNull() }
            if (entry == null || text == null) {
                _uiState.update { state -> state.copy(loading = false, failed = true) }
                return@launch
            }
            document = entry
            val offset = anchor?.let { entry.anchors[it] }
            _uiState.update {
                it.copy(
                    markdown = text,
                    loading = false,
                    failed = false,
                    anchorOffset = offset,
                    anchorMarked = offset != null,
                )
            }
        }
    }

    /**
     * Decides what following a link in the body does.
     *
     * @param rawTarget The destination exactly as the Markdown wrote it.
     * @param urlOf Turns a repository path and anchor into this build's URL.
     * @return The effect to run, or `null` when nothing should happen.
     */
    fun onLinkClick(rawTarget: String, urlOf: (String, String?) -> String): HelpReaderEffect? {
        val current = document ?: return null
        val target = resolveLink(current, rawTarget)
        if (target == null) {
            // `verifyBundledDocs` makes this unreachable: every relative link
            // in a bundled document resolves or the build fails.
            Timber.e("Documentation link '%s' in '%s' resolved to nothing", rawTarget, documentId)
            return null
        }
        return when (target) {
            is DocumentationTarget.SameDocument -> {
                scrollTo(target.anchor)
                null
            }

            is DocumentationTarget.Bundled -> HelpReaderEffect.OpenDocument(target.id, target.anchor)
            is DocumentationTarget.Repository -> handOff(urlOf(target.path, target.anchor))
            is DocumentationTarget.External -> handOff(target.url)
        }
    }

    /**
     * Sends a URL to the browser, or refuses in place when there is no network.
     *
     * The refusal is state, not an effect: it opens a bar at the bottom of the
     * reader and takes none of the scroll position, which is the expensive
     * thing eleven screens into the troubleshooting guide. Copying the address
     * is offered *by* that bar rather than done on the user's behalf.
     *
     * @param url The address.
     * @return The effect to run, or `null` when the refusal handles it.
     */
    private fun handOff(url: String): HelpReaderEffect? {
        if (networkState.networkState.value.isConnected) return HelpReaderEffect.OpenBrowser(url)
        _uiState.update { it.copy(offlineUrl = url) }
        return null
    }

    /**
     * Scrolls to an anchor in the document already open.
     *
     * @param anchor The anchor, or `null` to return to the top.
     */
    private fun scrollTo(anchor: String?) {
        val offset = anchor?.let { document?.anchors?.get(it) } ?: 0
        _uiState.update { it.copy(anchorOffset = offset, anchorMarked = anchor != null) }
    }

    /** Dismisses the arrival mark on the first scroll gesture. */
    fun onScrolled() {
        if (_uiState.value.anchorMarked) _uiState.update { it.copy(anchorMarked = false) }
    }

    /** Marks the pending scroll as consumed so it does not repeat. */
    fun onAnchorConsumed() {
        _uiState.update { it.copy(anchorOffset = null) }
    }

    /** Closes the in-body offline refusal. */
    fun dismissOfflineBar() {
        _uiState.update { it.copy(offlineUrl = null) }
    }
}
