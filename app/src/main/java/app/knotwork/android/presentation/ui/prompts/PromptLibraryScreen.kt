package app.knotwork.android.presentation.ui.prompts

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.android.domain.constants.PromptPresetConstants
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.usecases.promptpack.ExportedPromptPack
import app.knotwork.android.presentation.ui.common.asString
import app.knotwork.android.presentation.ui.components.PromptPreviewBottomSheet
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.screens.prompts.PromptEditorSheetBody
import app.knotwork.design.screens.prompts.PromptEditorState
import app.knotwork.design.screens.prompts.PromptEditorStrings
import app.knotwork.design.screens.prompts.PromptLibraryCallbacks
import app.knotwork.design.screens.prompts.PromptLibraryContent
import app.knotwork.design.screens.prompts.PromptLibraryStrings
import app.knotwork.design.screens.prompts.PromptLibraryViewState
import app.knotwork.design.screens.prompts.PromptLibraryVisualState
import app.knotwork.design.screens.prompts.PromptRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Slim app-side Prompt Library mapper. Subscribes to
 * [PromptLibraryViewModel.uiState], folds the projection into the catalog
 * [PromptLibraryViewState], and hosts the editor `ModalBottomSheet`.
 *
 * The data source is [PromptPreset] (bundled + user). The
 * mapper folds each preset into a `PromptRow` keyed by the preset's
 * String id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptLibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: PromptLibraryViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val strings = promptLibraryStrings()
    val editorStrings = promptEditorStrings()
    val resolvedErrorMessage = uiState.errorMessage?.asString()
    val genericErrorMessage = stringResource(R.string.errors_generic_unexpected)
    val viewState = remember(uiState, strings.subtitleFormat, resolvedErrorMessage) {
        uiState.toViewState(
            subtitleFormat = strings.subtitleFormat,
            resolvedErrorMessage = resolvedErrorMessage,
            fallbackErrorMessage = genericErrorMessage,
        )
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF launcher for the top-bar import action. Reads the picked document
    // off the main thread and hands the text to the VM, which parses,
    // refuses what a prompt may not carry, and reconciles the id against the
    // library. The MIME filter accepts `text/*` beside `text/markdown`
    // because most providers report a `.md` file as plain text — or as
    // nothing at all.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val resolver = context.contentResolver
            val document = withContext(Dispatchers.IO) { readText(resolver, uri) }
            // Only an unreadable document short-circuits here. An *empty* one
            // is readable, and the parser has a better sentence for it than
            // "that file could not be read" — it can say the settings block
            // is missing, which is what the user has to fix.
            if (document == null) {
                viewModel.onFileUnreadable()
            } else {
                val fileName = withContext(Dispatchers.IO) { resolveDisplayName(resolver, uri) }.orEmpty()
                viewModel.importPromptFile(document = document, fileName = fileName)
            }
        }
    }

    val callbacks = PromptLibraryCallbacks(
        onBack = onBack,
        onCategorySelected = viewModel::selectCategory,
        onNewPrompt = { viewModel.openEditor(promptId = null) },
        onEditPrompt = { viewModel.openEditor(promptId = it) },
        onDeletePrompt = viewModel::deletePrompt,
        onDuplicatePrompt = viewModel::duplicatePrompt,
        onPreviewPrompt = { presetId ->
            val preset = (uiState.bundledPresets + uiState.userPresets)
                .firstOrNull { it.id == presetId }
                ?: return@PromptLibraryCallbacks
            viewModel.requestPromptPreview(preset.systemPrompt)
        },
        onEditorNameChange = viewModel::onEditorNameChange,
        onEditorCategoryChange = viewModel::onEditorCategoryChange,
        onEditorBodyChange = viewModel::onEditorBodyChange,
        onEditorVariableInsert = viewModel::onEditorVariableInsert,
        onEditorSave = viewModel::saveEditor,
        onEditorCancel = viewModel::closeEditor,
        onRetry = viewModel::retry,
        onImportPrompt = { importLauncher.launch(arrayOf(MIME_MARKDOWN, MIME_TEXT_ANY)) },
        onExportPrompt = viewModel::requestExport,
    )

    PromptLibraryContent(
        state = viewState,
        modifier = modifier,
        strings = strings.content,
        callbacks = callbacks,
        snackbarHost = { KnotworkSnackbarHost(hostState = snackbarHostState) },
    )

    PromptExportLauncher(uiState = uiState, viewModel = viewModel, resolver = context.contentResolver)
    PromptImportSnackbar(uiState = uiState, viewModel = viewModel, hostState = snackbarHostState)
    PromptImportDialogHost(dialog = uiState.importDialog, viewModel = viewModel)

    val editor = viewState.editor
    if (editor != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeEditor,
            sheetState = sheetState,
        ) {
            PromptEditorSheetBody(
                state = editor,
                availableVariables = viewState.availableVariables,
                strings = editorStrings,
                callbacks = callbacks,
            )
        }
    }

    // Prompt preview bottom sheet — driven by `previewState`. The sheet body
    // (`PromptPreviewBottomSheet`) renders `null` segments as a centred
    // spinner so the user gets feedback while resolution runs on IO.
    val previewState = uiState.previewState
    if (previewState !is PromptPreviewState.Hidden) {
        PromptPreviewBottomSheet(
            segments = (previewState as? PromptPreviewState.Ready)?.segments,
            onDismiss = viewModel::dismissPromptPreview,
        )
    }
}

/** MIME type a well-behaved provider reports for a `.md` document. */
private const val MIME_MARKDOWN = "text/markdown"

/**
 * Fallback MIME filter for the import picker.
 *
 * Most providers report a `.md` file as `text/plain`, and some report
 * nothing recognisable at all, so filtering on [MIME_MARKDOWN] alone hides
 * the very files this action exists to open.
 */
private const val MIME_TEXT_ANY = "text/*"

/**
 * Launches the create-document picker whenever an export has been rendered,
 * writes the payload to the chosen destination, and confirms it.
 *
 * The parked payload is consumed the moment the picker launches, so a
 * recomposition or a configuration change cannot fire the picker twice for
 * one tap.
 *
 * @param uiState Current screen state; its `pendingExport` drives the picker.
 * @param viewModel Sink for the consume / confirm / failure callbacks.
 * @param resolver Used to write the chosen document.
 */
@Composable
private fun PromptExportLauncher(
    uiState: PromptLibraryUiState,
    viewModel: PromptLibraryViewModel,
    resolver: ContentResolver,
) {
    val scope = rememberCoroutineScope()
    var payload by remember { mutableStateOf<ExportedPromptPack?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MIME_MARKDOWN),
    ) { uri ->
        val pending = payload
        payload = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) { writeText(resolver, uri, pending.content) }
            if (written) viewModel.onExported(pending.displayName) else viewModel.onExportFailed()
        }
    }
    val pendingExport = uiState.pendingExport
    LaunchedEffect(pendingExport) {
        if (pendingExport != null) {
            payload = pendingExport
            viewModel.consumePendingExport()
            exportLauncher.launch(pendingExport.fileName)
        }
    }
}

/**
 * Shows the pending one-shot confirmation and, when the user takes its
 * action, switches to the category the prompt landed in.
 *
 * @param uiState Current screen state; its `snackbar` drives this effect.
 * @param viewModel Consumes the snackbar and applies the tab switch.
 * @param hostState Host the message is shown on.
 */
@Composable
private fun PromptImportSnackbar(
    uiState: PromptLibraryUiState,
    viewModel: PromptLibraryViewModel,
    hostState: SnackbarHostState,
) {
    val snackbar = uiState.snackbar
    val message = snackbar?.text?.asString()
    val actionLabel = stringResource(R.string.prompts_import_success_action)
    LaunchedEffect(snackbar) {
        if (snackbar == null || message == null) return@LaunchedEffect
        // Shown *before* it is consumed. Consuming first clears the state this
        // effect is keyed on, which cancels the effect — and with it the
        // `showSnackbar` call — so the message never reliably appears.
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel.takeIf { snackbar.showCategory != null },
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            snackbar.showCategory?.let(viewModel::selectCategory)
        }
        viewModel.consumeSnackbar()
    }
}

/**
 * Reads a picked document as text.
 *
 * @return The document text, or `null` when the provider refused the read or
 *   the document has gone. A refused read is not an exception the user needs
 *   a stack trace for — it becomes "that file could not be read".
 */
private fun readText(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
} catch (_: Exception) {
    null
}

/**
 * Writes [content] to a chosen document.
 *
 * @return `true` when the bytes were written.
 */
private fun writeText(resolver: ContentResolver, uri: Uri, content: String): Boolean = try {
    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } != null
} catch (_: Exception) {
    false
}

/**
 * Reads a document's display name, used as the fallback id source when the
 * file's frontmatter does not name one.
 *
 * @return The display name, or `null` when it cannot be resolved.
 */
private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
} catch (_: Exception) {
    null
}

/**
 * Maps the app-side [PromptLibraryUiState] onto the catalog
 * [PromptLibraryViewState] consumed by `PromptLibraryContent`.
 *
 * Categories are sourced from [PromptPresetConstants.LLM_DRIVEN_NODE_TYPES]
 * — the set of node types that can host a system-prompt preset — so the
 * tab row is stable across loads (even when one category has zero presets).
 * Rows for the active tab come from `bundledPresets + userPresets`.
 *
 * Branch order: a non-null [errorMessage] always wins over the
 * "empty prompts" branch — otherwise a failed initial load with no
 * cached prompts would hide behind a misleading empty state and the
 * Retry CTA would never be reachable.
 *
 * @param subtitleFormat localised `"%1$d categories · %2$d prompts"` template.
 * @param resolvedErrorMessage pre-resolved `errorMessage.asString()` value
 *   (mappers cannot call `@Composable` resolvers themselves), or `null`
 *   when no error is in flight.
 * @param fallbackErrorMessage generic localised error string used as the
 *   rendered subtitle when [errorMessage] is non-null but resolves to an
 *   empty payload.
 */
internal fun PromptLibraryUiState.toViewState(
    subtitleFormat: String,
    resolvedErrorMessage: String?,
    fallbackErrorMessage: String,
): PromptLibraryViewState {
    val categories = PromptPresetConstants.LLM_DRIVEN_NODE_TYPES
        .map { it.name }
        .sorted()
    val selected = selectedCategory?.takeIf { it in categories }
        ?: categories.firstOrNull().orEmpty()
    val allPresets = bundledPresets + userPresets
    val totalPresets = allPresets.size
    val rows = allPresets
        .filter { it.nodeType.name == selected }
        .map { preset ->
            PromptRow(
                id = preset.id,
                category = preset.nodeType.name,
                name = preset.name,
                body = preset.systemPrompt,
                // No "used by N pipelines" counter for presets — that would
                // require scanning every pipeline graph on every load. Wire
                // the field to 0 so the catalog footer reads as a placeholder
                // until a dedicated counter use case lands.
                usedByCount = 0,
                // Bundled presets are read-only — the catalog hides Edit /
                // Delete affordances under this flag.
                isReadOnly = preset.isBundled,
            )
        }
    val subtitle = subtitleFormat.format(categories.size, totalPresets)
    val editor = editorDraft?.let { draft ->
        PromptEditorState(
            id = draft.id,
            name = draft.name,
            category = draft.category,
            body = draft.body,
            usedByCount = 0,
        )
    }
    val visualState = when {
        // Errors take precedence over both Loading and Empty — without
        // this order, a failed initial load with no cached prompts is
        // rendered as Empty, hiding the real failure and the Retry CTA.
        errorMessage != null -> PromptLibraryVisualState.Error
        isLoading -> PromptLibraryVisualState.Loading
        allPresets.isEmpty() -> PromptLibraryVisualState.Empty
        else -> PromptLibraryVisualState.Default
    }
    val errorText = if (visualState == PromptLibraryVisualState.Error) {
        resolvedErrorMessage?.takeIf { it.isNotBlank() } ?: fallbackErrorMessage
    } else {
        null
    }
    return PromptLibraryViewState(
        visualState = visualState,
        categories = categories,
        selectedCategory = selected,
        prompts = rows,
        availableVariables = availableVariables,
        editor = editor,
        subtitle = subtitle,
        errorMessage = errorText,
    )
}

/** Bundle of localised display strings threaded into [PromptLibraryContent]. */
private data class LocalisedPromptLibraryStrings(val content: PromptLibraryStrings, val subtitleFormat: String)

@Composable
private fun promptLibraryStrings(): LocalisedPromptLibraryStrings = LocalisedPromptLibraryStrings(
    content = PromptLibraryStrings(
        title = stringResource(R.string.prompts_screen_title),
        backCd = stringResource(R.string.prompts_back_cd),
        fabCd = stringResource(R.string.prompts_fab_cd),
        editCd = stringResource(R.string.prompts_edit_cd),
        deleteCd = stringResource(R.string.prompts_delete_cd),
        previewCd = stringResource(R.string.prompts_preview_cd),
        duplicateCd = stringResource(R.string.prompts_duplicate_cd),
        usedByFormat = stringResource(R.string.prompts_used_by_format),
        emptyTitle = stringResource(R.string.prompts_empty_title),
        emptySubtitle = stringResource(R.string.prompts_empty_subtitle),
        emptyImportCta = stringResource(R.string.prompts_empty_import_cta),
        emptyNewCta = stringResource(R.string.prompts_empty_new_cta),
        emptyCategoryTitle = stringResource(R.string.prompts_empty_category_title),
        emptyCategorySubtitle = stringResource(R.string.prompts_empty_category_subtitle),
        importCd = stringResource(R.string.prompts_import_cd),
        exportCdFormat = stringResource(R.string.prompts_export_cd_format),
        moreCd = stringResource(R.string.prompts_more_cd),
        exportAction = stringResource(R.string.prompts_action_export),
        deleteAction = stringResource(R.string.prompts_action_delete),
        errorTitle = stringResource(R.string.prompts_error_title),
        errorRetry = stringResource(R.string.common_retry),
    ),
    subtitleFormat = stringResource(R.string.prompts_subtitle_format),
)

@Composable
private fun promptEditorStrings(): PromptEditorStrings = PromptEditorStrings(
    titleNew = stringResource(R.string.prompts_editor_title_new),
    titleEdit = stringResource(R.string.prompts_editor_title_edit),
    nameLabel = stringResource(R.string.prompts_editor_name_label),
    namePlaceholder = stringResource(R.string.prompts_editor_name_placeholder),
    categoryLabel = stringResource(R.string.prompts_editor_category_label),
    categoryPlaceholder = stringResource(R.string.prompts_editor_category_placeholder),
    bodyLabel = stringResource(R.string.prompts_editor_body_label),
    bodyPlaceholder = stringResource(R.string.prompts_editor_body_placeholder),
    insertLabel = stringResource(R.string.prompts_editor_insert_label),
    footerFormat = stringResource(R.string.prompts_editor_footer_format),
    cancel = stringResource(R.string.common_cancel),
    save = stringResource(R.string.common_save),
    closeCd = stringResource(R.string.prompts_editor_close_cd),
)
