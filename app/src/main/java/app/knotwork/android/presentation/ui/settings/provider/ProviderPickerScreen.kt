package app.knotwork.android.presentation.ui.settings.provider

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.requiredCredential
import app.knotwork.design.screens.settings.ProviderPickerContent
import app.knotwork.design.screens.settings.ProviderPickerGroupUi
import app.knotwork.design.screens.settings.ProviderPickerRowUi
import app.knotwork.design.screens.settings.ProviderPickerViewState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Provider picker shown when the user taps Settings → "+ Add provider". Lists every [ProviderId]
 * in the order every provider surface uses, grouped into hosted providers and servers the user
 * runs, and marks the ones already set up; tapping a row routes to [ProviderDetailScreen] for that
 * provider.
 *
 * A full screen rather than a bottom-sheet so the predictive-back gesture works without
 * bottom-sheet anchored-draggable plumbing.
 *
 * @param onPick A provider was chosen.
 * @param onBack The user left the picker.
 * @param viewModel Which providers are already set up.
 */
@Composable
fun ProviderPickerScreen(
    onPick: (ProviderId) -> Unit,
    onBack: () -> Unit,
    viewModel: ProviderPickerViewModel = hiltViewModel(),
) {
    val added by viewModel.added.collectAsState()
    val compatDescription = stringResource(R.string.settings_provider_picker_compat_desc)
    fun row(id: ProviderId) = ProviderPickerRowUi(
        id = id.name,
        title = id.displayName(),
        description = compatDescription.takeIf { id == ProviderId.OpenAiCompatible },
        added = id in added,
    )
    val (own, hosted) = ProviderId.entries.partition { it.cloudProvider.usesBaseUrl }
    ProviderPickerContent(
        state = ProviderPickerViewState(
            title = stringResource(R.string.settings_provider_picker_title),
            backContentDescription = stringResource(R.string.common_back),
            groups = listOf(
                ProviderPickerGroupUi(stringResource(R.string.settings_provider_group_hosted), hosted.map(::row)),
                ProviderPickerGroupUi(stringResource(R.string.settings_provider_group_own), own.map(::row)),
            ),
            addedLabel = stringResource(R.string.settings_provider_picker_added),
        ),
        // The catalog hands back the opaque row id it was given; mapping it back
        // to `ProviderId` here is what keeps the provider vocabulary out of the
        // design module.
        onPick = { id -> ProviderId.entries.firstOrNull { it.name == id }?.let(onPick) },
        onBack = onBack,
    )
}

/**
 * Which providers the picker marks *added*: those with their key — or, for a server the user runs,
 * its address — saved. A provider still waiting for a model counts: it is set up, and its row on
 * the providers list says what is missing.
 *
 * @param apiKeyRepository Where keys and addresses are saved.
 */
@HiltViewModel
class ProviderPickerViewModel @Inject constructor(apiKeyRepository: ApiKeyRepository) : ViewModel() {

    /** The providers already set up. */
    val added: StateFlow<Set<ProviderId>> = combine(
        ProviderId.entries.map { id ->
            apiKeyRepository.requiredCredential(id.cloudProvider).map { credential ->
                id.takeUnless { credential.isNullOrBlank() }
            }
        },
    ) { ids -> ids.filterNotNull().toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
