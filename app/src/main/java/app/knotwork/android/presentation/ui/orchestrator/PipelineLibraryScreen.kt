package app.knotwork.android.presentation.ui.orchestrator

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.R
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.ImportCollisionResolution
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.presentation.ui.common.asString
import app.knotwork.android.presentation.ui.orchestrator.presets.GraphFlowPreview
import app.knotwork.android.presentation.ui.orchestrator.presets.PipelineLibrarySpeedDial
import app.knotwork.android.presentation.ui.orchestrator.presets.PipelinePresetsViewModel
import app.knotwork.android.presentation.ui.orchestrator.presets.PresetPickerSheet
import app.knotwork.android.presentation.ui.orchestrator.presets.SaveAsPresetDialog
import app.knotwork.design.components.chips.Status
import app.knotwork.design.components.dialogs.MAX_NAMED_LIST_ITEMS
import app.knotwork.design.components.dialogs.OutcomeAction
import app.knotwork.design.components.dialogs.OutcomeDialog
import app.knotwork.design.components.dialogs.OutcomeNamedList
import app.knotwork.design.components.dialogs.OutcomeTone
import app.knotwork.design.components.dialogs.SingleFieldDialog
import app.knotwork.design.components.dialogs.SingleFieldDialogUi
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.pipelines.PipelineLibraryCallbacks
import app.knotwork.design.screens.pipelines.PipelineLibraryContent
import app.knotwork.design.screens.pipelines.PipelineLibraryFilter
import app.knotwork.design.screens.pipelines.PipelineLibraryRow
import app.knotwork.design.screens.pipelines.PipelineLibraryViewState
import app.knotwork.design.screens.pipelines.PipelineLibraryVisualState
import app.knotwork.design.screens.pipelines.PipelineSecondaryLineKind
import app.knotwork.design.screens.pipelines.isFabHidden
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Library screen listing every saved pipeline. Acts as the entry point for
 * the orchestrator feature.
 *
 * The catalog
 * [PipelineLibraryContent] composable owns the visual surface; this screen
 * subscribes to [OrchestratorViewModel], projects `OrchestratorUiState` to
 * the catalog [PipelineLibraryViewState], and dispatches user-triggered
 * events back to the VM through a [PipelineLibraryCallbacks] bundle.
 *
 * @param viewModel Shared orchestrator view-model (parent-graph scoped).
 * @param onOpenEditor Navigation callback invoked after the active pipeline
 * has been switched (load / duplicate / create).
 * @param onBack Reserved for future use; kept in the signature so the
 * nav-graph wiring needs no changes when the back arrow lands inside the
 * catalog surface.
 */
@Suppress("UnusedParameter", "LongMethod") // onBack kept for nav-graph stability; body is a flat switch.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineLibraryScreen(
    viewModel: OrchestratorViewModel = hiltViewModel(),
    presetsViewModel: PipelinePresetsViewModel = hiltViewModel(),
    onOpenEditor: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val presetsState by presetsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importUnreadableMessage = stringResource(R.string.orchestrator_library_import_unreadable)

    // SAF launcher for the footer "Import JSON" affordance. Reads the picked
    // document off the main thread and hands the text to the VM, which parses,
    // validates, persists, and (on a schemaVersion mismatch) stashes the graph
    // in `pendingImport` for the confirmation dialog below.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (json.isNullOrBlank()) {
                snackbarHostState.showSnackbar(message = importUnreadableMessage)
            } else {
                // Detects a bundle envelope vs a single-pipeline document and
                // routes to the matching flow — one affordance, two shapes.
                viewModel.importJson(json)
            }
        }
    }

    val exportFailedMessage = stringResource(R.string.errors_generic_unexpected)
    // Pre-resolved format template ("Exported %1$s") — the file name is only
    // known at write time, so we format this in the (non-composable) launcher
    // callback rather than calling stringResource there.
    val exportSuccessTemplate = stringResource(R.string.orchestrator_library_export_bundle_success)

    // Holds the bundle content + requested file name between launching the
    // create-document picker and the picker returning. The VM's
    // `pendingBundleExport` is consumed the moment we launch (below), so a
    // configuration change / recomposition can't re-fire the picker for the
    // same payload. The file name is retained as a fallback label for the
    // success snackbar when the chosen document's display name can't be read.
    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    var pendingExportFileName by remember { mutableStateOf<String?>(null) }

    // SAF launcher for "Export bundle" — writes the already-computed payload to
    // the chosen file and confirms the result to the user. A null uri means the
    // user dismissed the picker: nothing was written, so we stay silent.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType = "application/json"),
    ) { uri ->
        val content = pendingExportContent
        val fallbackName = pendingExportFileName
        pendingExportContent = null
        pendingExportFileName = null
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Write, then read back the document's display name — both are
            // blocking I/O, so they share one IO hop. Success yields the name
            // to confirm (falling back to the requested file name); failure
            // yields the throwable.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray())
                    }
                    resolveDisplayName(context.contentResolver, uri) ?: fallbackName
                }
            }
            result.fold(
                onSuccess = { name ->
                    val label = name ?: fallbackName.orEmpty()
                    snackbarHostState.showSnackbar(message = exportSuccessTemplate.format(label))
                },
                onFailure = { failure ->
                    snackbarHostState.showSnackbar(message = failure.message ?: exportFailedMessage)
                },
            )
        }
    }

    LaunchedEffect(uiState.pendingBundleExport) {
        val export = uiState.pendingBundleExport ?: return@LaunchedEffect
        pendingExportContent = export.content
        pendingExportFileName = export.fileName
        // Consume before launching so the picker fires exactly once per payload.
        viewModel.consumeBundleExport()
        exportLauncher.launch(export.fileName)
    }

    var activeFilter by remember { mutableStateOf(PipelineLibraryFilter.All) }
    var openOverflowRowId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PipelineGraph?>(null) }
    var deleteTarget by remember { mutableStateOf<PipelineGraph?>(null) }
    var saveAsPresetTarget by remember { mutableStateOf<PipelineGraph?>(null) }
    var showPresetPicker by remember { mutableStateOf(false) }

    val errorText = uiState.errorMessage?.asString()
    LaunchedEffect(errorText) {
        errorText?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }
    val feedbackText = uiState.feedbackMessage?.asString()
    LaunchedEffect(feedbackText) {
        feedbackText?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearFeedback()
        }
    }
    LaunchedEffect(uiState.pendingEditorNavigation) {
        if (uiState.pendingEditorNavigation) {
            viewModel.consumePendingEditorNavigation()
            onOpenEditor()
        }
    }

    val rows by remember(
        uiState.savedPipelines,
        uiState.defaultPipelineId,
        uiState.shareTargetPipelineId,
        uiState.quickSettingsTilePipelineId,
    ) {
        derivedStateOf {
            uiState.savedPipelines.map { pipeline ->
                pipeline.toLibraryRow(
                    isDefault = pipeline.id == uiState.defaultPipelineId,
                    isShareTarget = pipeline.id == uiState.shareTargetPipelineId,
                    isQuickTile = pipeline.id == uiState.quickSettingsTilePipelineId,
                )
            }
        }
    }

    val filteredRows by remember(rows, activeFilter) {
        derivedStateOf {
            when (activeFilter) {
                PipelineLibraryFilter.All, PipelineLibraryFilter.Mine -> rows
                // Recent: top-N by last-modified order — repository already
                // returns pipelines newest-first.
                PipelineLibraryFilter.Recent -> rows.take(n = RECENT_TAKE_COUNT)
                // Shared is rendered disabled in the chip row; the screen
                // never sets `activeFilter = Shared`, but if it ever did
                // we'd surface the empty result deterministically.
                PipelineLibraryFilter.Shared -> emptyList()
            }
        }
    }

    val visualState = when {
        uiState.errorMessage != null && rows.isEmpty() -> PipelineLibraryVisualState.Error
        uiState.isLoading && rows.isEmpty() -> PipelineLibraryVisualState.Loading
        rows.isEmpty() -> PipelineLibraryVisualState.Empty
        else -> PipelineLibraryVisualState.Populated
    }

    val viewState = PipelineLibraryViewState(
        visualState = visualState,
        pipelines = if (activeFilter != PipelineLibraryFilter.All) filteredRows else rows,
        totalCount = rows.size,
        defaultCount = if (uiState.defaultPipelineId != null) 1 else 0,
        activeFilter = activeFilter,
        errorMessage = if (visualState == PipelineLibraryVisualState.Error) errorText.orEmpty() else null,
        openOverflowRowId = openOverflowRowId,
    )

    val callbacks = PipelineLibraryCallbacks(
        onFilterChange = { activeFilter = it },
        onPipelineClick = { id ->
            viewModel.loadPipeline(pipelineId = id)
            onOpenEditor()
        },
        onPipelineOverflow = { id -> openOverflowRowId = id },
        onOverflowDismiss = { openOverflowRowId = null },
        onLoadInEditor = { id ->
            viewModel.loadPipeline(pipelineId = id)
            onOpenEditor()
        },
        onSetAsDefault = { id -> viewModel.setDefaultPipeline(pipelineId = id) },
        onUseForSharing = { id -> viewModel.bindPipelineToSurface(EntrySurface.SHARE, pipelineId = id) },
        onUseForTile = { id -> viewModel.bindPipelineToSurface(EntrySurface.QUICK_TILE, pipelineId = id) },
        onRename = { id ->
            uiState.savedPipelines.firstOrNull { it.id == id }?.let { renameTarget = it }
        },
        onDuplicate = { id -> viewModel.duplicatePipeline(pipelineId = id) },
        onExportBundle = { id ->
            viewModel.exportBundle(pipelineId = id, fileName = "knotwork-bundle-${LocalDate.now()}.json")
        },
        onImportJson = { importLauncher.launch(arrayOf("application/json", "text/*")) },
        onDelete = { id ->
            uiState.savedPipelines.firstOrNull { it.id == id }?.let { deleteTarget = it }
        },
        onNewPipeline = { showCreateDialog = true },
        onBrowseTemplates = { showPresetPicker = true },
        onSaveAsPreset = { id ->
            uiState.savedPipelines.firstOrNull { it.id == id }?.let { saveAsPresetTarget = it }
        },
        onErrorRetry = { viewModel.clearError() },
    )

    Box(modifier = Modifier.fillMaxSize().testTag(tag = LIBRARY_ROOT_TEST_TAG)) {
        PipelineLibraryContent(state = viewState, callbacks = callbacks)
        if (!viewState.isFabHidden) {
            PipelineLibrarySpeedDial(
                onNewPipeline = callbacks.onNewPipeline,
                onFromPreset = { showPresetPicker = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = KnotworkTheme.spacing.sp4,
                        bottom = KnotworkTheme.spacing.sp4,
                    ),
            )
        }
        SnackbarHost(hostState = snackbarHostState)
    }

    if (showPresetPicker) {
        PresetPickerSheet(
            state = presetsState,
            onTabSelected = presetsViewModel::selectTab,
            onCategorySelected = presetsViewModel::selectCategory,
            onUsePreset = { id ->
                presetsViewModel.loadFromPreset(id)
                showPresetPicker = false
            },
            onDismiss = { showPresetPicker = false },
        )
    }

    LaunchedEffect(presetsState.pendingPipelineIdFromPreset) {
        presetsState.pendingPipelineIdFromPreset?.let { newPipelineId ->
            presetsViewModel.consumePendingPipelineNavigation()
            viewModel.loadPipeline(newPipelineId)
            onOpenEditor()
        }
    }

    val presetFeedback = presetsState.feedbackMessage?.asString()
    LaunchedEffect(presetFeedback) {
        presetFeedback?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            presetsViewModel.clearFeedback()
        }
    }
    val presetError = presetsState.errorMessage?.asString()
    LaunchedEffect(presetError) {
        presetError?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            presetsViewModel.clearError()
        }
    }

    saveAsPresetTarget?.let { target ->
        SaveAsPresetDialog(
            initialName = target.name,
            onDismiss = { saveAsPresetTarget = null },
            onConfirm = { result ->
                viewModel.saveAsPresetFromLibrary(
                    pipelineId = target.id,
                    name = result.name,
                    description = result.description,
                    category = result.category,
                    tags = result.tags,
                )
                saveAsPresetTarget = null
            },
        )
    }

    if (showCreateDialog) {
        PipelineNameDialog(
            title = stringResource(R.string.orchestrator_library_new_pipeline_title),
            confirmLabel = stringResource(R.string.common_create),
            initialName = "",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createNewPipeline(name = name)
                showCreateDialog = false
            },
        )
    }
    renameTarget?.let { target ->
        PipelineNameDialog(
            title = stringResource(R.string.orchestrator_library_rename_pipeline_title),
            confirmLabel = stringResource(R.string.common_save),
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                viewModel.renamePipeline(pipelineId = target.id, newName = name)
                renameTarget = null
            },
        )
    }
    deleteTarget?.let { target ->
        // Pipelines that run `target` through a PIPELINE node. Deleting `target`
        // leaves each with a dangling reference — surfaced here as an explicit
        // warning (and, on confirm, as a normal deep-linkable validation error;
        // there is no silent cascade delete).
        val dependents = remember(target.id, uiState.savedPipelines) {
            viewModel.dependentsOf(pipelineId = target.id)
        }
        val hasDependents = dependents.isNotEmpty()
        AlertDialog(
            modifier = Modifier.testTag(tag = DELETE_DIALOG_TEST_TAG),
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.orchestrator_library_delete_pipeline_title)) },
            text = {
                if (hasDependents) {
                    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.orchestrator_library_delete_dependents_warning,
                                dependents.size,
                                dependents.size,
                            ),
                            modifier = Modifier.testTag(tag = DELETE_DEPENDENTS_TEST_TAG),
                        )
                        Text(
                            text = stringResource(
                                R.string.orchestrator_library_delete_dependents_header,
                                dependents.size,
                            ),
                            style = KnotworkTextStyles.LabelMd,
                            color = KnotworkTheme.extended.signalErrorText,
                        )
                        dependents.forEach { dependent ->
                            Text(text = dependent.name.ifBlank { "untitled" })
                        }
                    }
                } else {
                    Text(stringResource(R.string.orchestrator_library_delete_confirm, target.name))
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag(tag = DELETE_CONFIRM_TEST_TAG),
                    onClick = {
                        viewModel.deletePipeline(pipelineId = target.id)
                        deleteTarget = null
                    },
                ) {
                    Text(
                        stringResource(
                            if (hasDependents) {
                                R.string.orchestrator_library_delete_anyway
                            } else {
                                R.string.common_delete
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(
                        stringResource(
                            if (hasDependents) {
                                R.string.orchestrator_library_delete_keep
                            } else {
                                R.string.common_cancel
                            },
                        ),
                    )
                }
            },
        )
    }
    uiState.pendingImport?.let { mismatch ->
        // An instance of the shared outcome-dialog family rather than a
        // second hand-rolled dialog: the prompt import raises the same three
        // shapes, and one mechanism is the difference between two surfaces
        // agreeing and two surfaces drifting.
        val hidden = mismatch.droppedFields.size - MAX_NAMED_LIST_ITEMS
        OutcomeDialog(
            tone = OutcomeTone.INFO,
            headline = stringResource(R.string.orchestrator_library_import_schema_title),
            body = stringResource(
                R.string.orchestrator_library_import_schema_body,
                mismatch.foundVersion,
                mismatch.expectedVersion,
            ),
            // Name what is actually being lost. "Some configuration may not
            // import cleanly" is true but unactionable — the user cannot tell
            // whether it matters without being told which settings they are
            // agreeing to discard.
            namedList = OutcomeNamedList(
                heading = stringResource(R.string.orchestrator_library_import_dropped_heading),
                items = mismatch.droppedFields,
                moreLabel = hidden.takeIf { it > 0 }?.let {
                    pluralStringResource(R.plurals.orchestrator_library_import_dropped_more, it, it)
                },
            ),
            confirm = OutcomeAction(
                label = stringResource(R.string.orchestrator_library_import_anyway),
                onClick = viewModel::confirmPendingImport,
            ),
            dismiss = OutcomeAction(
                label = stringResource(R.string.common_cancel),
                onClick = viewModel::cancelPendingImport,
            ),
            onDismissRequest = viewModel::cancelPendingImport,
        )
    }
    uiState.pendingCollision?.let { graph ->
        AlertDialog(
            onDismissRequest = viewModel::cancelCollision,
            title = { Text(stringResource(R.string.orchestrator_library_import_collision_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.orchestrator_library_import_collision_single_body,
                        graph.name.ifBlank { "untitled" },
                    ),
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
                    TextButton(onClick = { viewModel.resolveCollision(ImportCollisionResolution.REPLACE) }) {
                        Text(stringResource(R.string.orchestrator_library_import_collision_replace))
                    }
                    TextButton(onClick = { viewModel.resolveCollision(ImportCollisionResolution.IMPORT_AS_COPY) }) {
                        Text(stringResource(R.string.orchestrator_library_import_collision_copy))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelCollision) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    uiState.pendingBundleImport?.let { pending ->
        val hasCollision = pending.collidingIds.isNotEmpty()
        AlertDialog(
            onDismissRequest = viewModel::cancelBundleImport,
            title = { Text(stringResource(R.string.orchestrator_library_import_bundle_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                    if (hasCollision) {
                        Text(
                            pluralStringResource(
                                R.plurals.orchestrator_library_import_bundle_collision_body,
                                pending.collidingIds.size,
                                pending.collidingIds.size,
                                pending.pipelines.size,
                            ),
                        )
                    }
                    if (pending.schemaMismatches.isNotEmpty()) {
                        Text(stringResource(R.string.orchestrator_library_import_bundle_schema_body))
                    }
                }
            },
            confirmButton = {
                if (hasCollision) {
                    Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
                        TextButton(onClick = { viewModel.resolveBundleImport(ImportCollisionResolution.REPLACE) }) {
                            Text(stringResource(R.string.orchestrator_library_import_bundle_replace))
                        }
                        TextButton(
                            onClick = { viewModel.resolveBundleImport(ImportCollisionResolution.IMPORT_AS_COPY) },
                        ) {
                            Text(stringResource(R.string.orchestrator_library_import_bundle_copies))
                        }
                    }
                } else {
                    TextButton(onClick = { viewModel.resolveBundleImport(ImportCollisionResolution.REPLACE) }) {
                        Text(stringResource(R.string.orchestrator_library_import_anyway))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelBundleImport) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * `:app` binding of the catalog's single-field dialog, used for both creating
 * and renaming a pipeline.
 *
 * The dialog used to be written out here, one of two hand-rolled copies of the
 * same shape — the other being the preset rename. Only one of the two had a
 * baseline, so the same interaction was verified in one place and unverified in
 * the other.
 *
 * @param title Dialog title, which differs between create and rename.
 * @param confirmLabel Confirm CTA, likewise.
 * @param initialName Value the field opens with.
 * @param onDismiss Cancel or scrim tap.
 * @param onConfirm Confirmed, with the value as typed.
 */
@Composable
private fun PipelineNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    SingleFieldDialog(
        ui = SingleFieldDialogUi(
            title = title,
            label = stringResource(R.string.orchestrator_library_name_field_label),
            initialValue = initialName,
            confirmLabel = confirmLabel,
            cancelLabel = stringResource(R.string.common_cancel),
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * Projects a domain [PipelineGraph] onto a catalog [PipelineLibraryRow].
 * Builds the "N nodes · {flavour}" subtitle from the first few node types
 * and derives the secondary line (shown only for an empty, "unbound" graph),
 * plus the entry-surface binding flags that drive the "DEFAULT" / "SHARE" /
 * "TILE" pills.
 *
 * There is deliberately no "active" marker. The pipeline the editor happens to
 * hold is screen state, not a property of the pipeline: after a restart it is just
 * the most recently modified one, and a row labelled "Active" read as a setting the
 * user had chosen — while quietly making that row impossible to delete.
 */
private fun PipelineGraph.toLibraryRow(
    isDefault: Boolean,
    isShareTarget: Boolean,
    isQuickTile: Boolean,
): PipelineLibraryRow {
    // Walk the graph from INPUT following connections (GraphFlowPreview) rather
    // than iterating `nodes` in insertion order — otherwise the subtitle reads
    // e.g. "INPUT→OUTPUT→LITE_RT" (storage order) while the editor renders the
    // true execution order "INPUT→LITE_RT→OUTPUT".
    val flavour = GraphFlowPreview.render(this)
    val subtitle = "$nodeCountText · $flavour"
    val secondaryLine = if (nodes.isEmpty()) "unbound" else null
    val secondaryKind = if (nodes.isEmpty()) {
        PipelineSecondaryLineKind.Unbound
    } else {
        PipelineSecondaryLineKind.Default
    }
    return PipelineLibraryRow(
        id = id,
        title = name.ifBlank { "untitled" },
        subtitle = subtitle,
        secondaryLine = secondaryLine,
        secondaryLineKind = secondaryKind,
        status = Status.Idle,
        leadingTint = Color(color = LEADING_TINT_PACKED),
        leadingIcon = AppIcons.Branch,
        isDefault = isDefault,
        isShareTarget = isShareTarget,
        isQuickTile = isQuickTile,
    )
}

/** Pre-formatted "n nodes" segment used in the row subtitle. */
private val PipelineGraph.nodeCountText: String
    get() = if (nodes.size == 1) "1 node" else "${nodes.size} nodes"

/** Number of rows considered "recent" by the Recent filter chip. */
private const val RECENT_TAKE_COUNT = 3

/** Packed ARGB of the leading-mark tint used by every library row (brand orange). */
private const val LEADING_TINT_PACKED: Long = 0xFFC48225

/** TestTag applied to the screen root so Espresso / Compose tests can anchor. */
internal const val LIBRARY_ROOT_TEST_TAG = "pipeline_library_root"

/** TestTag on the delete-pipeline dialog root. */
internal const val DELETE_DIALOG_TEST_TAG = "pipeline_delete_dialog"

/** TestTag on the dependent-pipelines warning text (present only when dependents exist). */
internal const val DELETE_DEPENDENTS_TEST_TAG = "pipeline_delete_dependents_warning"

/** TestTag on the delete-dialog confirm button. */
internal const val DELETE_CONFIRM_TEST_TAG = "pipeline_delete_confirm"

/**
 * Reads the human-readable display name of the document at [uri] — the file
 * name shown in the system "Save file" picker — via
 * [OpenableColumns.DISPLAY_NAME]. Returns `null` when the backing provider
 * exposes no such column so the caller can fall back to the requested name.
 *
 * Runs a blocking cursor query, so it must be called off the main thread.
 *
 * @param resolver The content resolver used to query the document provider.
 * @param uri The document uri returned by the create-document picker.
 * @return The document's display name, or `null` if it cannot be resolved.
 */
private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
