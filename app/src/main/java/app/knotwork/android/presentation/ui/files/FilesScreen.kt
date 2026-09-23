package app.knotwork.android.presentation.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.R
import app.knotwork.android.domain.models.WorkspaceFile
import app.knotwork.android.presentation.common.DisplayFormat
import app.knotwork.design.screens.files.CollisionView
import app.knotwork.design.screens.files.DeleteDialogView
import app.knotwork.design.screens.files.FileKind
import app.knotwork.design.screens.files.FileRowItem
import app.knotwork.design.screens.files.FilesCallbacks
import app.knotwork.design.screens.files.FilesContent
import app.knotwork.design.screens.files.FilesViewState
import app.knotwork.design.screens.files.FilesVisualState
import app.knotwork.design.screens.files.PreviewView
import app.knotwork.design.screens.files.QuotaTone
import app.knotwork.design.screens.files.QuotaView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Stateful entry point for the Files screen. Maps [FilesViewModel] state to the
 * catalog [FilesContent], wires the Storage-Access-Framework pickers (import /
 * save-as) and the `FileProvider`-backed share sheet, and forwards the catalog
 * callbacks to the ViewModel.
 *
 * @param onBack pop back to the More tab.
 * @param modifier layout modifier from the nav host.
 * @param viewModel injected Files ViewModel.
 */
@Composable
fun FilesScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: FilesViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val name = context.queryDisplayName(uri)
        viewModel.onImportPicked(displayName = name) { context.contentResolver.openInputStream(uri) }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SAVE_AS_MIME),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { viewModel.completeSaveAs(it) }
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                FilesEvent.LaunchImport -> importLauncher.launch(arrayOf(IMPORT_MIME_FILTER))
                is FilesEvent.LaunchSaveAs -> saveAsLauncher.launch(event.suggestedName)
                is FilesEvent.ShareStaged -> context.openShareSheet(event.stagedPaths)
            }
        }
    }

    FilesContent(
        state = uiState.toViewState(),
        modifier = modifier.testTag(FILES_ROOT_TEST_TAG),
        callbacks = FilesCallbacks(
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onImport = viewModel::requestImport,
            onRowClick = viewModel::onRowClick,
            onRowLongClick = viewModel::onRowLongClick,
            onSelectAll = viewModel::selectAll,
            onExitSelection = viewModel::exitSelection,
            onDeleteSelected = viewModel::requestDeleteSelected,
            onShareSelected = viewModel::requestShareSelected,
            onFilePreview = viewModel::onRowClick,
            onFileShare = viewModel::requestShare,
            onFileSaveAs = viewModel::requestSaveAs,
            onFileDelete = viewModel::requestDelete,
            onClosePreview = viewModel::closePreview,
            onDeleteConfirm = viewModel::confirmDelete,
            onDeleteCancel = viewModel::cancelDelete,
            onCollisionKeepBoth = viewModel::resolveCollisionKeepBoth,
            onCollisionReplace = viewModel::resolveCollisionReplace,
            onCollisionCancel = viewModel::cancelCollision,
            onErrorRetry = viewModel::refresh,
        ),
    )
}

/** Projects the app-side UI state to the catalog view state. */
internal fun FilesUiState.toViewState(): FilesViewState {
    val visual = when {
        loadFailed -> FilesVisualState.Error
        files.isEmpty() -> FilesVisualState.Empty
        else -> FilesVisualState.Populated
    }
    return FilesViewState(
        visualState = visual,
        quota = toQuotaView(),
        files = files.map { it.toRowItem() },
        selectionMode = selectionMode,
        selectedPaths = selectedPaths,
        refreshing = refreshing,
        preview = preview?.toPreviewView(),
        deleteDialog = pendingDelete?.let { paths ->
            DeleteDialogView(names = paths.map { it.basename() }, count = paths.size)
        },
        collisionDialog = collision?.let { CollisionView(name = it.name, keepBothName = it.keepBothName) },
    )
}

private fun FilesUiState.toQuotaView(): QuotaView {
    val pct = (usage.fraction * PERCENT).roundToInt()
    val tone = when {
        usage.limitBytes > 0L && usage.usedBytes >= usage.limitBytes -> QuotaTone.Over
        pct >= WARN_PERCENT -> QuotaTone.Warn
        else -> QuotaTone.Normal
    }
    val base = "${DisplayFormat.formatBytes(usage.usedBytes)} / ${DisplayFormat.formatBytes(usage.limitBytes)}"
    val usageText = when (tone) {
        QuotaTone.Over -> "$base · full"
        QuotaTone.Warn -> "$base · $pct%"
        QuotaTone.Normal -> base
    }
    return QuotaView(
        count = files.size,
        usageText = usageText,
        fraction = usage.fraction,
        tone = tone,
        full = tone == QuotaTone.Over,
    )
}

private fun WorkspaceFile.toRowItem(): FileRowItem {
    val dot = relativePath.lastIndexOf('/')
    val dir = if (dot < 0) "" else relativePath.substring(0, dot + 1)
    val name = if (dot < 0) relativePath else relativePath.substring(dot + 1)
    return FileRowItem(
        path = relativePath,
        dir = dir,
        name = name,
        sizeLabel = DisplayFormat.formatBytes(sizeBytes),
        dateLabel = relativeShort(System.currentTimeMillis() - lastModified),
        kind = if (isText) FileKind.Text else FileKind.Binary,
        isFresh = lastModified > 0L && System.currentTimeMillis() - lastModified < FRESH_WINDOW_MS,
    )
}

private fun FilePreviewState.toPreviewView(): PreviewView {
    val dot = path.lastIndexOf('/')
    val dir = if (dot < 0) "" else path.substring(0, dot + 1)
    val name = if (dot < 0) path else path.substring(dot + 1)
    return PreviewView(
        path = path,
        dir = dir,
        name = name,
        sizeLabel = DisplayFormat.formatBytes(preview.totalBytes),
        body = preview.text,
        truncated = preview.truncated,
        shownBytesLabel = DisplayFormat.formatBytes(preview.text.toByteArray(Charsets.UTF_8).size.toLong()),
    )
}

private fun String.basename(): String = substringAfterLast('/')

/** Compact relative-age label (`"now"`, `"2 m"`, `"3 h"`, `"5 d"`, `"2 w"`). */
private fun relativeShort(elapsedMs: Long): String {
    val mins = (elapsedMs.coerceAtLeast(0L)) / MS_PER_MINUTE
    return when {
        mins < 1L -> "now"
        mins < MINUTES_PER_HOUR -> "$mins m"
        mins < MINUTES_PER_DAY -> "${mins / MINUTES_PER_HOUR} h"
        mins < MINUTES_PER_WEEK -> "${mins / MINUTES_PER_DAY} d"
        else -> "${mins / MINUTES_PER_WEEK} w"
    }
}

/** Resolves a content [uri]'s display name, falling back to its last path segment. */
private fun Context.queryDisplayName(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            cursor.getString(index)?.let { return it }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: FALLBACK_IMPORT_NAME
}

/**
 * Offers already-staged copies of workspace files to the system share sheet via
 * [FileProvider]. The workspace staged them (and owns how long they live), so
 * this only mints one read-only URI per copy: the workspace directory itself is
 * never exposed, and the grant is per file and not persistable.
 *
 * @param stagedPaths Absolute paths of the staged copies under the share cache.
 */
private fun Context.openShareSheet(stagedPaths: List<String>) {
    val uris = stagedPaths.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", File(it)) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = SHARE_MIME
            putExtra(Intent.EXTRA_STREAM, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = SHARE_MIME
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val chooser = Intent.createChooser(intent, getString(R.string.files_share_chooser_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(chooser)
}

/** Stable test tag applied to the Files screen root — used by instrumented tests. */
const val FILES_ROOT_TEST_TAG: String = "files_screen_root"

private const val FALLBACK_IMPORT_NAME = "imported-file"
private const val SAVE_AS_MIME = "application/octet-stream"
private const val IMPORT_MIME_FILTER = "*/*"
private const val SHARE_MIME = "*/*"
private const val PERCENT = 100f
private const val WARN_PERCENT = 90
private const val FRESH_WINDOW_MS = 60L * 60L * 1000L
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 60L * 24
private const val MINUTES_PER_WEEK = 60L * 24 * 7
