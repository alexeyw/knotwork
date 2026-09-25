package app.knotwork.android.presentation.ui.chat.archive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.android.presentation.ui.chat.toShareChooser
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.screens.chatarchive.ChatArchiveCallbacks
import app.knotwork.design.screens.chatarchive.ChatArchiveContent
import app.knotwork.design.screens.chatarchive.ChatArchiveRowUi
import app.knotwork.design.screens.chatarchive.ChatArchiveStrings
import app.knotwork.design.screens.chatarchive.ChatArchiveViewState
import app.knotwork.design.screens.chatarchive.ChatArchiveVisualState

/**
 * App-side chat-archive surface: folds [ChatArchiveViewModel.uiState] into the
 * catalog [ChatArchiveViewState], localises every string, and owns the two
 * things a Hilt ViewModel cannot — the share-sheet `Intent` and the snackbar
 * host.
 *
 * Tapping a row **opens** the archived chat (read-only, via [onOpenChat]); it
 * never un-archives it. Archive state changes only on an explicit Restore.
 *
 * @param onBack pops back to wherever the archive was opened from.
 * @param onOpenChat routes to the chat surface for the given session id.
 * @param modifier optional layout modifier applied to the screen root.
 * @param viewModel the screen-scoped Hilt ViewModel.
 */
@Composable
fun ChatArchiveScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatArchiveViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val exportChooserTitle = stringResource(R.string.chat_export_chooser_title)
    val restoredMessage = stringResource(R.string.chat_snackbar_restored)

    LaunchedEffect(viewModel) {
        viewModel.exportEvents.collect { document ->
            context.startActivity(document.toShareChooser(exportChooserTitle))
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.restoreEvents.collect {
            snackbarHostState.showSnackbar(message = restoredMessage)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ChatArchiveContent(
            state = uiState.toViewState(
                // Resolved here, not inside the mapper: plural forms come from
                // resources, and only a composable can read those.
                archivedLabels = uiState.rows.associate { it.id to archivedAtText(it.archivedAt) },
                subtitle = pluralStringResource(
                    R.plurals.chat_archive_subtitle,
                    uiState.rows.size,
                    uiState.rows.size,
                ),
            ),
            modifier = Modifier.fillMaxSize().testTag(CHAT_ARCHIVE_ROOT_TEST_TAG),
            strings = chatArchiveStrings(),
            callbacks = ChatArchiveCallbacks(
                onBack = onBack,
                onRowClick = onOpenChat,
                onRowMenuOpen = viewModel::openRowMenu,
                onRowMenuDismiss = viewModel::dismissRowMenu,
                onRestore = viewModel::restore,
                onExport = viewModel::export,
                onDeleteRequest = viewModel::requestDelete,
                onDeleteConfirm = viewModel::confirmDelete,
                onDeleteDismiss = viewModel::dismissDelete,
                onRetry = viewModel::retry,
            ),
            snackbarHost = { KnotworkSnackbarHost(hostState = snackbarHostState) },
        )
    }
}

/** Localised string bundle handed to the stateless catalog surface. */
@Composable
private fun chatArchiveStrings(): ChatArchiveStrings = ChatArchiveStrings(
    title = stringResource(R.string.chat_archive_title),
    back = stringResource(R.string.chat_archive_back_cd),
    restore = stringResource(R.string.chat_archive_restore),
    export = stringResource(R.string.chat_archive_export),
    deleteForever = stringResource(R.string.chat_archive_delete_forever),
    rowMenuCd = stringResource(R.string.chat_archive_row_menu_cd),
    ranAfterArchiving = stringResource(R.string.chat_archive_ran_after_archiving),
    footer = stringResource(R.string.chat_archive_footer),
    emptyTitle = stringResource(R.string.chat_archive_empty_title),
    emptySubtitle = stringResource(R.string.chat_archive_empty_body),
    errorTitle = stringResource(R.string.chat_archive_error_title),
    errorRetry = stringResource(R.string.chat_archive_error_retry),
    deleteTitle = stringResource(R.string.chat_archive_delete_title),
    deleteBodyTemplate = stringResource(R.string.chat_archive_delete_body),
    deleteConfirm = stringResource(R.string.chat_archive_delete_confirm),
    deleteCancel = stringResource(R.string.chat_archive_delete_cancel),
)

/**
 * Renders an [ArchivedAtLabel] bucket as its localised sentence. Kept in the
 * composable layer so the plural forms come from resources rather than being
 * assembled in Kotlin.
 */
@Composable
internal fun archivedAtText(label: ArchivedAtLabel): String = when (label) {
    ArchivedAtLabel.JustNow -> stringResource(R.string.chat_archive_when_just_now)
    ArchivedAtLabel.Yesterday -> stringResource(R.string.chat_archive_when_yesterday)
    ArchivedAtLabel.Unknown -> stringResource(R.string.chat_archive_when_unknown)
    is ArchivedAtLabel.Minutes ->
        pluralStringResource(R.plurals.chat_archive_when_minutes, label.minutes, label.minutes)
    is ArchivedAtLabel.Hours ->
        pluralStringResource(R.plurals.chat_archive_when_hours, label.hours, label.hours)
    is ArchivedAtLabel.Days ->
        pluralStringResource(R.plurals.chat_archive_when_days, label.days, label.days)
    is ArchivedAtLabel.Weeks ->
        pluralStringResource(R.plurals.chat_archive_when_weeks, label.weeks, label.weeks)
    is ArchivedAtLabel.Months ->
        pluralStringResource(R.plurals.chat_archive_when_months, label.months, label.months)
    is ArchivedAtLabel.Years ->
        pluralStringResource(R.plurals.chat_archive_when_years, label.years, label.years)
}

/**
 * Projects the ViewModel state onto the catalog view state.
 *
 * The error branch wins over loading and rows: a failed read must not be
 * dressed up as an empty archive, which would tell the user their chats are
 * gone.
 *
 * @param archivedLabels row id → localised archived-at sentence. Resolved by
 *   the caller because plural forms live in resources.
 * @param subtitle pre-formatted TopAppBar subtitle.
 */
internal fun ChatArchiveUiState.toViewState(
    archivedLabels: Map<String, String>,
    subtitle: String,
): ChatArchiveViewState {
    val visualState = when {
        errorMessage != null -> ChatArchiveVisualState.Error
        loading -> ChatArchiveVisualState.Loading
        rows.isEmpty() -> ChatArchiveVisualState.Empty
        else -> ChatArchiveVisualState.Default
    }
    val mappedRows = rows.map { row ->
        ChatArchiveRowUi(
            id = row.id,
            title = row.title,
            archivedLabel = archivedLabels[row.id].orEmpty(),
            starred = row.starred,
            ranAfterArchiving = row.ranAfterArchiving,
        )
    }
    return ChatArchiveViewState(
        visualState = visualState,
        rows = mappedRows,
        subtitle = if (visualState == ChatArchiveVisualState.Default) subtitle else "",
        openMenuRowId = openMenuRowId,
        deleteTarget = deleteTargetId?.let { id -> mappedRows.firstOrNull { it.id == id } },
        errorMessage = errorMessage,
    )
}

/** Stable test tag for the archive screen root — used by instrumented tests. */
const val CHAT_ARCHIVE_ROOT_TEST_TAG: String = "chat_archive_root"
