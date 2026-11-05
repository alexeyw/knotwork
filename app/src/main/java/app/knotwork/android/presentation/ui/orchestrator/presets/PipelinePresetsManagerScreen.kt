package app.knotwork.android.presentation.ui.orchestrator.presets

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.R
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.presentation.ui.common.asString
import app.knotwork.design.screens.pipelines.PresetCategoryToneUi
import app.knotwork.design.screens.pipelines.PresetChipUi
import app.knotwork.design.screens.pipelines.PresetDeleteDialogUi
import app.knotwork.design.screens.pipelines.PresetManagerCallbacks
import app.knotwork.design.screens.pipelines.PresetManagerContent
import app.knotwork.design.screens.pipelines.PresetManagerViewState
import app.knotwork.design.screens.pipelines.PresetRenameDialogUi
import app.knotwork.design.screens.pipelines.PresetRowActionLabels
import app.knotwork.design.screens.pipelines.PresetRowUi
import app.knotwork.design.screens.pipelines.PresetTabUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Full-screen manager for bundled and user-saved pipeline presets.
 *
 * The composition moved to `app.knotwork.design.screens.pipelines.PresetManagerContent`;
 * what stays here is what cannot live in the design module — the ViewModel, the
 * Storage-Access-Framework export, and the projection that turns domain presets
 * into resolved rows.
 *
 * @param viewModel Injected presets ViewModel.
 * @param onBack Pop back to the pipeline library.
 */
@Composable
fun PipelinePresetsManagerScreen(viewModel: PipelinePresetsViewModel = hiltViewModel(), onBack: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var renameTarget by remember { mutableStateOf<PipelinePreset?>(null) }
    var deleteTarget by remember { mutableStateOf<PipelinePreset?>(null) }
    var pendingExportPreset by remember { mutableStateOf<PipelinePreset?>(null) }

    // The VM's one-shot messages go to the activity-level host, the same route
    // the Files screen takes: it renders above the NavGraph, so a message
    // survives this screen being navigated away from.
    val errorText = state.errorMessage?.asString()
    LaunchedEffect(errorText) {
        errorText?.let { message ->
            viewModel.announce(message)
            viewModel.clearError()
        }
    }
    val feedbackText = state.feedbackMessage?.asString()
    LaunchedEffect(feedbackText) {
        feedbackText?.let { message ->
            viewModel.announce(message)
            viewModel.clearFeedback()
        }
    }

    // Resolved in composition: the launcher's callback runs outside it.
    val exportedText = stringResource(R.string.orchestrator_preset_exported)
    val exportFailedText = stringResource(R.string.orchestrator_preset_export_failed)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType = EXPORT_MIME_TYPE),
    ) { uri ->
        val preset = pendingExportPreset
        pendingExportPreset = null
        if (uri == null || preset == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(viewModel.exportPresetToJson(preset).toByteArray())
                }
                exportedText
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Preset export failed for %s", preset.id)
                e.message ?: exportFailedText
            }
            viewModel.announce(message)
        }
    }

    PresetManagerContent(
        state = state.toViewState(
            context = context,
            renameTarget = renameTarget,
            deleteTarget = deleteTarget,
        ),
        callbacks = PresetManagerCallbacks(
            onBack = onBack,
            onTabSelected = { id -> viewModel.selectTab(tabFromId(id)) },
            onCategorySelected = { id -> viewModel.selectCategory(categoryFromId(id)) },
            onRename = { id -> renameTarget = state.filteredPresets.firstOrNull { it.id == id } },
            onDelete = { id -> deleteTarget = state.filteredPresets.firstOrNull { it.id == id } },
            onExport = { id ->
                state.filteredPresets.firstOrNull { it.id == id }?.let { preset ->
                    pendingExportPreset = preset
                    exportLauncher.launch(defaultFileName(preset))
                }
            },
            onRenameConfirm = { newName ->
                renameTarget?.let { viewModel.renameUserPreset(presetId = it.id, newName = newName) }
                renameTarget = null
            },
            onRenameDismiss = { renameTarget = null },
            onDeleteConfirm = {
                deleteTarget?.let { viewModel.deleteUserPreset(it.id) }
                deleteTarget = null
            },
            onDeleteDismiss = { deleteTarget = null },
        ),
    )
}

/**
 * Projects the VM state onto the catalog's view state, resolving every string
 * here so the design module never learns what a preset or a category is.
 *
 * @param context Resource resolution.
 * @param renameTarget The preset whose rename dialog is open, if any.
 * @param deleteTarget The preset whose delete confirmation is open, if any.
 * @return The resolved view state.
 */
private fun PipelinePresetsUiState.toViewState(
    context: Context,
    renameTarget: PipelinePreset?,
    deleteTarget: PipelinePreset?,
): PresetManagerViewState = PresetManagerViewState(
    title = context.getString(R.string.orchestrator_preset_manager_title),
    subtitle = context.getString(
        R.string.orchestrator_preset_manager_subtitle,
        bundledPresets.size,
        userPresets.size,
    ),
    backContentDescription = context.getString(R.string.common_back),
    tabs = presetTabs(context),
    chips = presetChips(context),
    rows = filteredPresets.map { preset -> preset.toRow(context) },
    emptyText = context.getString(
        if (activeTab == PresetPickerTab.Bundled) {
            R.string.orchestrator_preset_manager_bundled_empty
        } else {
            R.string.orchestrator_preset_manager_user_empty
        },
    ),
    actionLabels = PresetRowActionLabels(
        rename = context.getString(R.string.orchestrator_preset_manager_action_rename),
        export = context.getString(R.string.orchestrator_preset_manager_action_export),
        delete = context.getString(R.string.orchestrator_preset_manager_action_delete),
    ),
    rename = renameTarget?.let { target ->
        PresetRenameDialogUi(
            initialName = target.name,
            title = context.getString(R.string.orchestrator_preset_manager_rename_title),
            label = context.getString(R.string.orchestrator_preset_save_name_label),
            confirmLabel = context.getString(R.string.common_save),
            cancelLabel = context.getString(R.string.common_cancel),
        )
    },
    delete = deleteTarget?.let { target ->
        PresetDeleteDialogUi(
            title = context.getString(R.string.orchestrator_preset_manager_delete_title),
            body = context.getString(R.string.orchestrator_preset_manager_delete_confirm, target.name),
            confirmLabel = context.getString(R.string.common_delete),
            cancelLabel = context.getString(R.string.common_cancel),
        )
    },
)

/**
 * The Bundled / Mine tabs with their counts.
 *
 * Shared with `PresetPickerSheet` so the two surfaces cannot drift: they render
 * the same catalog component from the same projection.
 *
 * @param context Resource resolution.
 * @return The resolved tabs.
 */
internal fun PipelinePresetsUiState.presetTabs(context: Context): List<PresetTabUi> =
    PresetPickerTab.entries.map { tab ->
        PresetTabUi(
            id = tab.tagId(),
            label = context.getString(tab.labelRes()),
            count = if (tab == PresetPickerTab.Bundled) bundledPresets.size else userPresets.size,
            selected = tab == activeTab,
        )
    }

/**
 * The category chips, the reset chip first. Only categories present under the
 * active tab are offered.
 *
 * @param context Resource resolution.
 * @return The resolved chips.
 */
internal fun PipelinePresetsUiState.presetChips(context: Context): List<PresetChipUi> {
    val forTab = presetsForActiveTab
    return buildList {
        add(
            PresetChipUi(
                id = null,
                label = context.getString(R.string.orchestrator_preset_picker_chip_all),
                count = forTab.size,
                selected = selectedCategory == null,
            ),
        )
        visibleCategories.forEach { category ->
            add(
                PresetChipUi(
                    id = category.name,
                    label = context.getString(category.labelRes()),
                    count = forTab.count { it.category == category },
                    selected = selectedCategory == category,
                ),
            )
        }
    }
}

/**
 * Maps an opaque category id back to the domain enum.
 *
 * Total rather than `valueOf`: the ids are ours, but a chip id that no longer
 * parses should drop the filter, not crash the screen — the reset chip's `null`
 * is already a valid answer.
 *
 * @param id The id handed back by the design module, or `null` for the reset chip.
 * @return The matching category, or `null`.
 */
internal fun categoryFromId(id: String?): PresetCategory? =
    id?.let { name -> PresetCategory.entries.firstOrNull { it.name == name } }

/**
 * Maps an opaque tab id back to the presentation enum, for the picker sheet.
 *
 * @param id The id handed back by the design module.
 * @return The matching tab.
 */
internal fun presetTabFromId(id: String): PresetPickerTab = tabFromId(id)

/**
 * Projects one preset onto its row.
 *
 * A bundled preset is read-only, so its overflow simply does not carry Rename
 * and Delete — absent rather than disabled, because a disabled item still asks
 * the reader to work out why.
 *
 * @param context Resource resolution.
 * @return The resolved row.
 */
internal fun PipelinePreset.toRow(context: Context): PresetRowUi = PresetRowUi(
    id = id,
    name = name,
    description = description,
    flowPreview = GraphFlowPreview.render(graph) ?: context.getString(R.string.pipeline_flow_empty),
    categoryLabel = context.getString(category.labelRes()),
    categoryTone = category.toTone(),
    canRename = !isBundled,
    canDelete = !isBundled,
)

/**
 * The design module's mirror of this category.
 *
 * A mapping rather than a shared enum: `:catalog` decides how a tone looks and
 * never learns the category vocabulary, so a new category is one arm here.
 *
 * @return The matching tone.
 */
internal fun PresetCategory.toTone(): PresetCategoryToneUi = when (this) {
    PresetCategory.LOCAL -> PresetCategoryToneUi.Local
    PresetCategory.CLOUD -> PresetCategoryToneUi.Cloud
    PresetCategory.HYBRID -> PresetCategoryToneUi.Hybrid
    PresetCategory.TOOL -> PresetCategoryToneUi.Tool
    PresetCategory.RESEARCH -> PresetCategoryToneUi.Research
    PresetCategory.OTHER -> PresetCategoryToneUi.Other
}

/**
 * Label resource for this category.
 *
 * @return The string resource id.
 */
internal fun PresetCategory.labelRes(): Int = when (this) {
    PresetCategory.LOCAL -> R.string.orchestrator_preset_category_local
    PresetCategory.CLOUD -> R.string.orchestrator_preset_category_cloud
    PresetCategory.HYBRID -> R.string.orchestrator_preset_category_hybrid
    PresetCategory.TOOL -> R.string.orchestrator_preset_category_tool
    PresetCategory.RESEARCH -> R.string.orchestrator_preset_category_research
    PresetCategory.OTHER -> R.string.orchestrator_preset_category_other
}

/**
 * Label resource for this tab.
 *
 * @return The string resource id.
 */
private fun PresetPickerTab.labelRes(): Int = when (this) {
    PresetPickerTab.Bundled -> R.string.orchestrator_preset_picker_tab_bundled
    PresetPickerTab.Mine -> R.string.orchestrator_preset_picker_tab_mine
}

/**
 * Opaque id this tab travels to the design module as.
 *
 * Deliberately the lowercase word rather than `name`: the existing test tags
 * read `preset_picker_tab_bundled`, and a rename here would silently break
 * every selector built on them.
 *
 * @return The id.
 */
private fun PresetPickerTab.tagId(): String = when (this) {
    PresetPickerTab.Bundled -> "bundled"
    PresetPickerTab.Mine -> "mine"
}

/**
 * Maps an opaque tab id back to the presentation enum.
 *
 * @param id The id handed back by the design module.
 * @return The matching tab, defaulting to Bundled.
 */
private fun tabFromId(id: String): PresetPickerTab = when (id) {
    "mine" -> PresetPickerTab.Mine
    else -> PresetPickerTab.Bundled
}

/**
 * Builds the default filename suggested to the SAF picker — the preset name with
 * characters that are not legal in most filesystems replaced.
 *
 * @param preset The preset being exported.
 * @return The suggested file name.
 */
private fun defaultFileName(preset: PipelinePreset): String {
    val slug = preset.name
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "preset" }
    return "$slug.preset.json"
}

private const val EXPORT_MIME_TYPE = "application/json"
