package app.knotwork.android.presentation.ui.settings.provider

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.design.screens.settings.ProviderPickerContent
import app.knotwork.design.screens.settings.ProviderPickerRowUi
import app.knotwork.design.screens.settings.ProviderPickerViewState

/**
 * Minimal provider picker shown when the user taps Settings →
 * "+ Add provider". Lists every [ProviderId]; tapping a row routes
 * to [ProviderDetailScreen] for that provider.
 *
 * v0.1 keeps the picker as a full screen rather than a bottom-sheet so
 * the predictive-back gesture works without bottom-sheet anchored-
 * draggable plumbing. The richer bottom-sheet picker is a follow-up.
 */
@Composable
fun ProviderPickerScreen(onPick: (ProviderId) -> Unit, onBack: () -> Unit) {
    ProviderPickerContent(
        state = ProviderPickerViewState(
            title = stringResource(R.string.settings_provider_picker_title),
            backContentDescription = stringResource(R.string.common_back),
            rows = ProviderId.entries.map { ProviderPickerRowUi(id = it.name, title = it.displayName()) },
        ),
        // The catalog hands back the opaque row id it was given; mapping it back
        // to `ProviderId` here is what keeps the provider vocabulary out of the
        // design module.
        onPick = { id -> ProviderId.entries.firstOrNull { it.name == id }?.let(onPick) },
        onBack = onBack,
    )
}
