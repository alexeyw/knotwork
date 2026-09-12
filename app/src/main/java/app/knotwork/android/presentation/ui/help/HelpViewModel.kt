package app.knotwork.android.presentation.ui.help

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.repositories.NetworkStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the Help list, in the app's own terms.
 *
 * @property documents Every whole document the registry declares, in its order.
 * @property refusedDocumentId Id of the row whose offline refusal is open, or
 *   `null`.
 */
data class HelpUiState(
    val documents: List<DocumentationLinks.Entry> = DocumentationLinks.DOCUMENTS,
    val refusedDocumentId: String? = null,
)

/**
 * Drives the Help list.
 *
 * The list itself is not state the user can change — it is the generated
 * registry — so the only thing this holds is which refusal is open. What it
 * decides is what a tap *means*, and that decision has exactly one input beyond
 * the registry: whether there is a network. A bundled document ignores it; a
 * remote one cannot.
 *
 * @property networkState Repository the connectivity check reads from.
 */
@HiltViewModel
class HelpViewModel @Inject constructor(private val networkState: NetworkStateRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HelpUiState())

    /** The list's state. */
    val uiState: StateFlow<HelpUiState> = _uiState.asStateFlow()

    /**
     * Decides what tapping a row does.
     *
     * @param id The row's registry id.
     * @param onOpenReader Invoked for a document that ships in the app.
     * @param onOpenBrowser Invoked for a document on the web, when reachable.
     */
    fun onDocumentClick(id: String, onOpenReader: (String) -> Unit, onOpenBrowser: (String) -> Unit) {
        val entry = DocumentationLinks.byId(id) ?: return
        when {
            entry.delivery == DocumentationLinks.Delivery.BUNDLED -> {
                dismissRefusal()
                onOpenReader(id)
            }

            networkState.networkState.value.isConnected -> {
                dismissRefusal()
                onOpenBrowser(id)
            }

            // Offline and on the web. The refusal replaces the navigation
            // rather than warning before it: online, the tap simply does what
            // the row said it would.
            else -> _uiState.update { it.copy(refusedDocumentId = id) }
        }
    }

    /** Closes the open refusal. */
    fun dismissRefusal() {
        _uiState.update { it.copy(refusedDocumentId = null) }
    }

    /**
     * Reports the document a refusal offers to open instead.
     *
     * The first bundled document in registry order, which is the one the
     * registry puts first precisely because it is the one a reader in trouble
     * needs. `null` only if nothing is bundled at all, which the build forbids.
     *
     * @return The fallback document's id, or `null`.
     */
    fun alternativeDocumentId(): String? =
        DocumentationLinks.DOCUMENTS.firstOrNull { it.delivery == DocumentationLinks.Delivery.BUNDLED }?.id

    /** Clears the refusal after the user acts on it. */
    fun onRefusalHandled() {
        viewModelScope.launch { dismissRefusal() }
    }
}
