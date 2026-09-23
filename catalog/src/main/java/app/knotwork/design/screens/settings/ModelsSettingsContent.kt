package app.knotwork.design.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Models category sub-screen. Surfaces the active-model card, the restart-gated
 * backend selector, the backend test probe and the collapsed external-provider
 * list. There is no Advanced disclosure — every Models row is Basic.
 *
 * @param state Immutable Models state.
 * @param modifier Layout modifier.
 * @param callbacks Interaction callbacks.
 */
@Composable
fun ModelsSettingsContent(
    state: ModelsSettingsViewState,
    modifier: Modifier = Modifier,
    callbacks: SettingsCallbacks = noopSettingsCallbacks(),
) {
    CategoryScaffold(
        title = stringResource(R.string.knotwork_settings_cat_models_title),
        subtitle = stringResource(R.string.knotwork_settings_count, 1 + state.providers.size),
        onBack = callbacks.onBack,
        modifier = modifier,
        overlay = {
            if (state.restartRequiredMessage != null) {
                RestartBanner(message = state.restartRequiredMessage, onRestart = callbacks.onRestartClick)
            }
        },
    ) {
        ActiveModelCard(state.localModel)
        SettingsAnchor(anchorKey = SettingsRowAnchors.LOCAL_MODEL_BACKEND) {
            BackendDropdownRow(
                title = stringResource(R.string.knotwork_settings_local_model_backend_title),
                backendLabel = state.localModel.backendLabel,
                selectedBackend = state.localModel.selectedBackend,
                options = state.localModel.backendOptions,
                onSelected = callbacks.onBackendSelected,
            )
        }
        TestProbeRow(state.localModel, callbacks.onTestBackendClick)
        NavLinkRow(
            icon = AppIcons.Download,
            title = stringResource(R.string.knotwork_settings_local_model_manage),
            state = stringResource(R.string.knotwork_settings_models_manage_subtitle),
            onClick = callbacks.onManageModelsClick,
        )
        SettingsAnchor(anchorKey = SettingsRowAnchors.LINK_PROVIDER_LIST) {
            SettingsSectionLabel(
                text = stringResource(R.string.knotwork_settings_section_providers),
                trailing = {
                    app.knotwork.design.components.misc.KnotworkSectionAction(
                        label = stringResource(R.string.knotwork_settings_providers_add),
                        icon = AppIcons.Add,
                        onClick = callbacks.onAddProviderClick,
                    )
                },
            )
        }
        state.providers.forEach { row ->
            ProviderNavRow(row = row, onClick = { callbacks.onProviderRowClick(row.id) })
        }
    }
}

@Composable
private fun ActiveModelCard(state: LocalModelCardState) {
    Surface(
        shape = KnotworkTheme.shapes.md,
        color = KnotworkTheme.extended.surface1,
        border = BorderStroke(SectionCardBorder, KnotworkTheme.extended.divider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier.padding(KnotworkTheme.spacing.sp3),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(LOCAL_MODEL_TILE_SIZE)
                    .clip(KnotworkTheme.shapes.sm)
                    .background(color = KnotworkTheme.extended.surface2),
            ) {
                Icon(imageVector = AppIcons.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Column(modifier = Modifier.weight(1f)) {
                val modelName = state.modelName ?: stringResource(R.string.knotwork_settings_local_model_empty)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                ) {
                    Text(
                        text = modelName,
                        style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = ModelNameOverflow,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (state.modelName != null) ActivePill()
                }
                if (state.metaLine != null) {
                    Text(
                        text = state.metaLine,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                        maxLines = 2,
                        overflow = ModelNameOverflow,
                    )
                }
            }
        }
    }
}

@Composable
private fun TestProbeRow(state: LocalModelCardState, onTest: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = AppIcons.Bolt,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(KnotworkTheme.spacing.sp5),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.knotwork_settings_local_model_test_title),
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = state.testProbeText,
                style = KnotworkTextStyles.MonoSm,
                color = if (state.testProbeIsError) {
                    KnotworkTheme.extended.signalError
                } else {
                    KnotworkTheme.extended.onSurfaceMuted
                },
            )
        }
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_settings_local_model_test_run),
            onClick = onTest,
            size = KnotworkButtonSize.Sm,
        )
    }
}
