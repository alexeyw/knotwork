package app.knotwork.android.presentation.ui.orchestrator.presets

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.design.screens.pipelines.PresetPickerRowUi
import app.knotwork.design.screens.pipelines.PresetPickerSheetBody
import app.knotwork.design.screens.pipelines.PresetPickerViewState

/**
 * Hosts the catalog's preset-picker body inside a [ModalBottomSheet].
 *
 * The split follows the `NodeConfigSheet` precedent: the host owns the sheet so
 * scrim, IME and navigation behaviour stay tunable at the screen level, and the
 * body — which is all of the layout — lives where a baseline can photograph it.
 *
 * Selection state stays here rather than in the ViewModel: the host only needs
 * the chosen id on confirmation, and a selection that survived a process death
 * would be a promise the sheet cannot keep. Tab and category filters *are* the
 * ViewModel's, because those do survive configuration changes.
 *
 * @param state Current picker state from `PipelinePresetsViewModel`.
 * @param onTabSelected Forwards tab clicks to the ViewModel.
 * @param onCategorySelected Forwards category-chip clicks to the ViewModel.
 * @param onUsePreset Invoked with the selected preset id on confirmation.
 * @param onDismiss Invoked on drag, scrim tap, close icon or Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetPickerSheet(
    state: PipelinePresetsUiState,
    onTabSelected: (PresetPickerTab) -> Unit,
    onCategorySelected: (PresetCategory?) -> Unit,
    onUsePreset: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PresetPickerSheetBody(
            state = PresetPickerViewState(
                title = stringResource(R.string.orchestrator_preset_picker_title),
                tabs = state.presetTabs(context),
                chips = state.presetChips(context),
                rows = state.filteredPresets.map { it.toPickerRow(context) },
                selectedRowId = selectedPresetId,
                emptyMessage = stringResource(R.string.orchestrator_preset_picker_empty),
                isLoading = state.isLoading,
                cancelLabel = stringResource(R.string.common_cancel),
                useLabel = stringResource(R.string.orchestrator_preset_picker_use),
                closeContentDescription = stringResource(R.string.common_cancel),
            ),
            onTabSelected = { id -> onTabSelected(presetTabFromId(id)) },
            onCategorySelected = { id -> onCategorySelected(categoryFromId(id)) },
            onRowSelected = { id -> selectedPresetId = id },
            onUsePreset = onUsePreset,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Resolves one preset into the picker's row model.
 *
 * Separate from [toRow], which the manager screen uses: that one also carries
 * `canRename` / `canDelete`, and a picker whose only verb is "use this one" has
 * no place for either.
 *
 * @param context Resource resolution.
 * @return The resolved row.
 */
internal fun PipelinePreset.toPickerRow(context: Context): PresetPickerRowUi = PresetPickerRowUi(
    id = id,
    name = name,
    description = description,
    flowPreview = GraphFlowPreview.render(graph) ?: context.getString(R.string.pipeline_flow_empty),
    categoryLabel = context.getString(category.labelRes()),
    categoryTone = category.toTone(),
)
