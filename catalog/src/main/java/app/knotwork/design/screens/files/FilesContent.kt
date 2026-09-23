package app.knotwork.design.screens.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The Files screen — the first user-facing window over the agent's workspace
 * sandbox. Lets the user see what the agent produced, take it out (share /
 * save-as), put a file in (import), and clean up (delete).
 *
 * Mirrors the catalog vocabulary of `MemoryContent` (multi-select bar, detail
 * bottom sheet, confirm dialogs, empty/error states) — no new design-system
 * component is introduced. Everything is data-driven from [state]; all
 * validation, I/O and SAF launching live in the host `FilesViewModel` /
 * `FilesScreen`.
 *
 * @param state immutable screen state.
 * @param modifier layout modifier from the host.
 * @param callbacks event bundle wired to the host ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesContent(
    state: FilesViewState,
    modifier: Modifier = Modifier,
    callbacks: FilesCallbacks = noopFilesCallbacks(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                KnotworkTopAppBarShell {
                    if (state.selectionMode) {
                        FilesSelectionBar(count = state.selectedPaths.size, callbacks = callbacks)
                    } else {
                        FilesTopBar(callbacks = callbacks)
                    }
                }
            },
            floatingActionButton = {
                if (state.visualState == FilesVisualState.Populated && !state.selectionMode) {
                    ExtendedFloatingActionButton(
                        onClick = callbacks.onImport,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = { Icon(AppIcons.ImportFile, contentDescription = null) },
                        text = { Text(stringResource(R.string.knotwork_files_import)) },
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            FilesBody(state = state, callbacks = callbacks, padding = padding)
        }

        state.preview?.let { FilesPreviewSheet(preview = it, callbacks = callbacks) }
        state.deleteDialog?.let { FilesDeleteDialog(view = it, callbacks = callbacks) }
        state.collisionDialog?.let { FilesCollisionDialog(view = it, callbacks = callbacks) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesTopBar(callbacks: FilesCallbacks) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.knotwork_files_title),
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.knotwork_files_subtitle),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = stringResource(R.string.knotwork_files_back_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = {
            IconButton(onClick = callbacks.onRefresh) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = stringResource(R.string.knotwork_files_refresh_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun FilesSelectionBar(count: Int, callbacks: FilesCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            // Hand-laid-out, so it owns its status-bar inset: the shell and this
            // screen's Scaffold both zero theirs, and only M3's TopAppBar — which the
            // normal bar is — adds one by itself. After the background, so the tint
            // fills the band behind the status bar the way a TopAppBar container does.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp2),
    ) {
        IconButton(onClick = callbacks.onExitSelection) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = stringResource(R.string.knotwork_files_selection_close_cd),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = pluralStringResource(R.plurals.knotwork_files_selected, count, count),
            style = KnotworkTextStyles.TitleMd,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = callbacks.onSelectAll) {
            Icon(
                imageVector = AppIcons.CheckSquare,
                contentDescription = stringResource(R.string.knotwork_files_select_all_cd),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        IconButton(onClick = callbacks.onShareSelected) {
            Icon(
                imageVector = AppIcons.Share,
                contentDescription = stringResource(R.string.knotwork_files_selection_share_cd),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        IconButton(onClick = callbacks.onDeleteSelected) {
            Icon(
                imageVector = AppIcons.Trash,
                contentDescription = stringResource(R.string.knotwork_files_selection_delete_cd),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FilesBody(state: FilesViewState, callbacks: FilesCallbacks, padding: PaddingValues) {
    when (state.visualState) {
        FilesVisualState.Error -> FilesError(callbacks = callbacks, padding = padding)
        FilesVisualState.Empty -> FilesEmpty(quota = state.quota, callbacks = callbacks, padding = padding)
        FilesVisualState.Populated -> FilesPopulated(state = state, callbacks = callbacks, padding = padding)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesPopulated(state: FilesViewState, callbacks: FilesCallbacks, padding: PaddingValues) {
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = callbacks.onRefresh,
        modifier = Modifier.fillMaxSize().padding(padding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp16),
        ) {
            item(key = "quota") {
                QuotaStrip(quota = state.quota, onImport = callbacks.onImport)
            }
            if (!state.selectionMode) {
                item(key = "list-label") {
                    Text(
                        text = pluralStringResource(
                            R.plurals.knotwork_files_list_label,
                            state.files.size,
                            state.files.size,
                        ),
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                        modifier = Modifier.padding(
                            start = KnotworkTheme.spacing.sp4,
                            end = KnotworkTheme.spacing.sp4,
                            top = KnotworkTheme.spacing.sp2,
                            bottom = KnotworkTheme.spacing.sp1,
                        ),
                    )
                }
            }
            items(state.files, key = { it.path }) { item ->
                FileRow(
                    item = item,
                    selectionMode = state.selectionMode,
                    selected = item.path in state.selectedPaths,
                    callbacks = callbacks,
                )
            }
        }
    }
}

@Composable
private fun QuotaStrip(quota: QuotaView, onImport: () -> Unit) {
    val barColor = when (quota.tone) {
        QuotaTone.Normal -> MaterialTheme.colorScheme.primary
        QuotaTone.Warn -> KnotworkTheme.extended.signalWarn
        QuotaTone.Over -> KnotworkTheme.extended.signalError
    }
    val usageColor = when (quota.tone) {
        QuotaTone.Normal -> KnotworkTheme.extended.onSurfaceMuted
        QuotaTone.Warn -> KnotworkTheme.extended.signalWarn
        QuotaTone.Over -> KnotworkTheme.extended.signalError
    }
    Column(
        modifier = Modifier
            .padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp3,
                bottom = KnotworkTheme.spacing.sp1,
            )
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(width = 1.dp, color = KnotworkTheme.extended.divider, shape = KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp3),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = quota.count.toString(),
                        style = KnotworkTextStyles.TitleLg,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp1))
                    Text(
                        text = pluralStringResource(R.plurals.knotwork_files_count, quota.count, quota.count),
                        style = KnotworkTextStyles.BodySm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                        modifier = Modifier.padding(bottom = QUOTA_COUNT_BASELINE_PAD),
                    )
                }
                Text(
                    text = quota.usageText,
                    style = KnotworkTextStyles.MonoSm,
                    color = usageColor,
                )
            }
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_files_import),
                onClick = onImport,
                size = KnotworkButtonSize.Sm,
                leadingIcon = AppIcons.ImportFile,
            )
        }
        QuotaBar(fraction = quota.fraction, color = barColor)
        if (quota.full) {
            QuotaFullBanner()
        }
    }
}

@Composable
private fun QuotaBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(QUOTA_BAR_HEIGHT)
            .clip(KnotworkTheme.shapes.full)
            .background(KnotworkTheme.extended.surface3),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = fraction.coerceIn(0f, 1f))
                .clip(KnotworkTheme.shapes.full)
                .background(color),
        )
    }
}

@Composable
private fun QuotaFullBanner() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.sm)
            .background(KnotworkTheme.extended.signalError.copy(alpha = BANNER_TINT_ALPHA))
            .border(
                width = 1.dp,
                color = KnotworkTheme.extended.signalError.copy(alpha = BANNER_BORDER_ALPHA),
                shape = KnotworkTheme.shapes.sm,
            )
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = AppIcons.Warn,
            contentDescription = null,
            tint = KnotworkTheme.extended.signalError,
            modifier = Modifier.size(BANNER_ICON_SIZE),
        )
        Text(
            text = stringResource(R.string.knotwork_files_quota_full_banner),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: FileRowItem, selectionMode: Boolean, selected: Boolean, callbacks: FilesCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1)
            .clip(KnotworkTheme.shapes.md)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = ROW_SELECTED_ALPHA))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = { callbacks.onRowClick(item.path) },
                onLongClick = { callbacks.onRowLongClick(item.path) },
            )
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp2),
    ) {
        if (selectionMode) {
            FileCheckbox(checked = selected)
        } else {
            FileKindTile(kind = item.kind)
        }
        FileRowText(item = item, modifier = Modifier.weight(1f))
        if (!selectionMode) {
            FileRowMenu(item = item, callbacks = callbacks)
        }
    }
}

@Composable
private fun FileKindTile(kind: FileKind) {
    val tint = if (kind == FileKind.Text) KnotworkTheme.extended.memAutoRail else KnotworkTheme.extended.onSurfaceDim
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ROW_TILE_SIZE)
            .clip(KnotworkTheme.shapes.sm)
            .background(KnotworkTheme.extended.surface2),
    ) {
        Icon(
            imageVector = if (kind == FileKind.Text) AppIcons.FileText else AppIcons.FileBin,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ROW_ICON_SIZE),
        )
    }
}

@Composable
private fun FileCheckbox(checked: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CHECKBOX_SIZE)
            .clip(KnotworkTheme.shapes.xs)
            .background(if (checked) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.surface2)
            .border(
                width = 2.dp,
                color = if (checked) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.outlineStrong,
                shape = KnotworkTheme.shapes.xs,
            ),
    ) {
        if (checked) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(CHECKBOX_TICK_SIZE),
            )
        }
    }
}

@Composable
private fun FileRowText(item: FileRowItem, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = item.dir + item.name,
            style = KnotworkTextStyles.MonoBase,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Text(
                text = item.sizeLabel + DOT_SEPARATOR + item.dateLabel,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            if (item.isFresh) {
                Text(
                    text = DOT_SEPARATOR + stringResource(R.string.knotwork_files_badge_new),
                    style = KnotworkTextStyles.MonoSm,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (item.kind == FileKind.Binary) {
                Text(
                    text = DOT_SEPARATOR + stringResource(R.string.knotwork_files_badge_binary),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun FileRowMenu(item: FileRowItem, callbacks: FilesCallbacks) {
    var open by remember { mutableStateOf(false) }
    Box {
        // Constrain the overflow button so its default 48 dp min touch target does not
        // inflate every row's height.
        IconButton(onClick = { open = true }, modifier = Modifier.size(ROW_MENU_BUTTON_SIZE)) {
            Icon(
                imageVector = AppIcons.More,
                contentDescription = stringResource(R.string.knotwork_files_row_menu_cd, item.name),
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (item.kind == FileKind.Text) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.knotwork_files_action_preview)) },
                    onClick = {
                        open = false
                        callbacks.onFilePreview(item.path)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.knotwork_files_action_share)) },
                onClick = {
                    open = false
                    callbacks.onFileShare(item.path)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.knotwork_files_action_save_as)) },
                onClick = {
                    open = false
                    callbacks.onFileSaveAs(item.path)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.knotwork_files_action_delete)) },
                onClick = {
                    open = false
                    callbacks.onFileDelete(item.path)
                },
            )
        }
    }
}

@Composable
private fun FilesEmpty(quota: QuotaView, callbacks: FilesCallbacks, padding: PaddingValues) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        QuotaStrip(quota = quota, onImport = callbacks.onImport)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.knotwork_files_empty_title),
                subtitle = stringResource(R.string.knotwork_files_empty_body),
                ctaLabel = stringResource(R.string.knotwork_files_empty_cta),
                onCtaClick = callbacks.onImport,
                illustration = {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(HERO_TILE_SIZE)
                            .clip(KnotworkTheme.shapes.xl)
                            .background(KnotworkTheme.extended.surface2),
                    ) {
                        Icon(
                            imageVector = AppIcons.FolderOpen,
                            contentDescription = null,
                            tint = KnotworkTheme.extended.onSurfaceMuted,
                            modifier = Modifier.size(HERO_ICON_SIZE),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun FilesError(callbacks: FilesCallbacks, padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.knotwork_files_error_title),
            subtitle = stringResource(R.string.knotwork_files_error_body),
            ctaLabel = stringResource(R.string.knotwork_files_error_retry),
            onCtaClick = callbacks.onErrorRetry,
            illustration = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(ERROR_TILE_SIZE)
                        .clip(KnotworkTheme.shapes.lg)
                        .background(KnotworkTheme.extended.signalError.copy(alpha = BANNER_TINT_ALPHA)),
                ) {
                    Icon(
                        imageVector = AppIcons.AlertCircle,
                        contentDescription = null,
                        tint = KnotworkTheme.extended.signalError,
                        modifier = Modifier.size(ERROR_ICON_SIZE),
                    )
                }
            },
        )
    }
}

@Composable
private fun FilesPreviewSheet(preview: PreviewView, callbacks: FilesCallbacks) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Scrim(onClick = callbacks.onClosePreview)
        Surface(
            color = KnotworkTheme.extended.surface1,
            tonalElevation = KnotworkTheme.elevation.el3,
            shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(fraction = SHEET_MAX_HEIGHT_FRACTION),
        ) {
            Column(modifier = Modifier.padding(KnotworkTheme.spacing.sp4)) {
                SheetHandle()
                PreviewHeader(preview = preview, onClose = callbacks.onClosePreview)
                if (preview.truncated) {
                    PreviewTruncationBanner(preview = preview)
                }
                PreviewBody(preview = preview, modifier = Modifier.weight(1f))
                PreviewActions(path = preview.path, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun Scrim(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    )
}

@Composable
private fun SheetHandle() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(bottom = KnotworkTheme.spacing.sp2)
                .width(SHEET_HANDLE_WIDTH)
                .height(SHEET_HANDLE_HEIGHT)
                .clip(KnotworkTheme.shapes.full)
                .background(KnotworkTheme.extended.outlineStrong),
        )
    }
}

@Composable
private fun PreviewHeader(preview: PreviewView, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth().padding(bottom = KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = AppIcons.FileText,
            contentDescription = null,
            tint = KnotworkTheme.extended.memAutoRail,
            modifier = Modifier.size(ROW_ICON_SIZE),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preview.dir + preview.name,
                style = KnotworkTextStyles.MonoBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview.sizeLabel + DOT_SEPARATOR + stringResource(R.string.knotwork_files_preview_read_only),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = stringResource(R.string.knotwork_files_preview_close_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun PreviewTruncationBanner(preview: PreviewView) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = KnotworkTheme.spacing.sp2)
            .clip(KnotworkTheme.shapes.sm)
            .background(KnotworkTheme.extended.surface2)
            .border(width = 1.dp, color = KnotworkTheme.extended.divider, shape = KnotworkTheme.shapes.sm)
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = AppIcons.Info,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(BANNER_ICON_SIZE),
        )
        Text(
            text = stringResource(
                R.string.knotwork_files_preview_truncation,
                preview.shownBytesLabel,
                preview.sizeLabel,
            ),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

@Composable
private fun PreviewBody(preview: PreviewView, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.consoleBg)
            .padding(KnotworkTheme.spacing.sp3)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = preview.body,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.consoleFg,
        )
        if (preview.truncated) {
            Text(
                text = stringResource(R.string.knotwork_files_preview_truncation_marker, preview.shownBytesLabel),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.consoleTag,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2),
            )
        }
    }
}

@Composable
private fun PreviewActions(path: String, callbacks: FilesCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp3),
    ) {
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_files_action_delete),
            onClick = { callbacks.onFileDelete(path) },
            destructive = true,
        )
        Spacer(modifier = Modifier.weight(1f))
        KnotworkTextButton(
            text = stringResource(R.string.knotwork_files_action_save_as),
            onClick = { callbacks.onFileSaveAs(path) },
        )
        KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_files_action_share),
            onClick = { callbacks.onFileShare(path) },
            leadingIcon = AppIcons.Share,
        )
    }
}

private val QUOTA_BAR_HEIGHT = 6.dp
private val QUOTA_COUNT_BASELINE_PAD = 2.dp
private val ROW_TILE_SIZE = 38.dp
private val ROW_MENU_BUTTON_SIZE = 36.dp
internal val ROW_ICON_SIZE = 19.dp
private val CHECKBOX_SIZE = 22.dp
private val CHECKBOX_TICK_SIZE = 14.dp
private val BANNER_ICON_SIZE = 16.dp
private val HERO_TILE_SIZE = 72.dp
private val HERO_ICON_SIZE = 34.dp
private val ERROR_TILE_SIZE = 56.dp
private val ERROR_ICON_SIZE = 28.dp
private val SHEET_CORNER = 24.dp
private val SHEET_HANDLE_WIDTH = 36.dp
private val SHEET_HANDLE_HEIGHT = 4.dp
private const val SHEET_MAX_HEIGHT_FRACTION = 0.86f
private const val SCRIM_ALPHA = 0.36f
private const val ROW_SELECTED_ALPHA = 0.08f
private const val BANNER_TINT_ALPHA = 0.12f
private const val BANNER_BORDER_ALPHA = 0.4f
private const val DOT_SEPARATOR = " · "
