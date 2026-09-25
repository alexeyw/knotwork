@file:Suppress("MatchingDeclarationName") // Hosts ChatArchiveContent + its private helpers.

package app.knotwork.design.screens.chatarchive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.lists.SwipeRevealAction
import app.knotwork.design.components.lists.SwipeRevealRow
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading archive tile on a row. */
private val ArchiveRowTile = 40.dp

/** Glyph size inside the row tile. */
private val ArchiveRowTileGlyph = 20.dp

/** Side length of the leading star marking a favorited chat. */
private val ArchiveRowStar = 14.dp

/**
 * Side length of the per-row overflow icon button, per the design handoff
 * ("targets ≥ 48 dp: ⋮ 48"). This is a **visual** size: Material's
 * `IconButton` already expands its touch bounds to 48 dp whatever it is laid
 * out at, so the a11y floor is met either way.
 */
private val ArchiveRowMenuButton = 48.dp

/** Side length of the empty/error illustration tile. */
private val ArchiveIllustrationTile = 72.dp

/** Glyph size inside the empty/error illustration tile. */
private val ArchiveIllustrationGlyph = 34.dp

/** Number of skeleton rows drawn while the archive loads. */
private const val SKELETON_ROW_COUNT = 5

/**
 * Font scale at or above which the row sheds decoration: the leading archive
 * tile goes, since on a screen where *every* row is archived it carries no
 * information. The gain goes to the title — the trailing slot never wins.
 */
private const val COMPACT_FONT_SCALE = 2.0f

/** Alpha of the tinted fill behind an illustration glyph. */
private const val ILLUSTRATION_FILL_ALPHA = 0.10f

/** Alpha of the border around an illustration tile. */
private const val ILLUSTRATION_BORDER_ALPHA = 0.24f

/**
 * Stateless Knotwork chat-archive surface — the chats the user has taken out of
 * the drawer without deleting them.
 *
 * Behavioural contract:
 *  - Rows are ordered most-recently-**archived** first and labelled with a
 *    relative archived-at string; the archive is a stack you put things on.
 *  - Tapping a row **opens** the chat (read-only, elsewhere); it never
 *    un-archives it. Archive state changes only when the user says so.
 *  - Restore is reachable from the row overflow and from a single 64 dp swipe
 *    action, so the gesture is never the only path. The design also placed an
 *    inline Restore button on the row; it was dropped in dogfooding, where it
 *    ate the title — the one thing identifying the chat — and put a third
 *    Restore beside two others already on screen.
 *  - **Delete forever** lives in the row overflow only, behind a confirmation.
 *    The swipe stays a single safe action.
 *
 * @param state immutable view state — drives loader / list / empty / error.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings.
 * @param callbacks one-shot callback bundle.
 * @param snackbarHost Where the screen's snackbars render: the Scaffold places it above its
 *   bottom bar and floating action button. The app passes a `KnotworkSnackbarHost`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatArchiveContent(
    state: ChatArchiveViewState,
    modifier: Modifier = Modifier,
    strings: ChatArchiveStrings = ChatArchiveStrings(),
    callbacks: ChatArchiveCallbacks = noopChatArchiveCallbacks(),
    snackbarHost: @Composable () -> Unit = {},
) {
    Scaffold(
        snackbarHost = snackbarHost,
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                ChatArchiveTopBar(state = state, strings = strings, callbacks = callbacks)
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.visualState) {
                ChatArchiveVisualState.Loading -> ChatArchiveSkeleton()
                ChatArchiveVisualState.Error -> ChatArchiveError(
                    state = state,
                    strings = strings,
                    callbacks = callbacks,
                )
                ChatArchiveVisualState.Empty -> ChatArchiveEmpty(strings = strings)
                ChatArchiveVisualState.Default -> ChatArchiveList(
                    state = state,
                    strings = strings,
                    callbacks = callbacks,
                )
            }
        }
    }
    state.deleteTarget?.let { target ->
        DeleteForeverDialog(target = target, strings = strings, callbacks = callbacks)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatArchiveTopBar(
    state: ChatArchiveViewState,
    strings: ChatArchiveStrings,
    callbacks: ChatArchiveCallbacks,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = strings.title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.subtitle.isNotEmpty()) {
                    Text(
                        text = state.subtitle,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = strings.back,
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
private fun ChatArchiveList(
    state: ChatArchiveViewState,
    strings: ChatArchiveStrings,
    callbacks: ChatArchiveCallbacks,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp16),
    ) {
        items(items = state.rows, key = { it.id }) { row ->
            ChatArchiveRow(
                row = row,
                strings = strings,
                menuOpen = state.openMenuRowId == row.id,
                revealed = state.revealedRowId?.let { it == row.id },
                callbacks = callbacks,
            )
        }
        item {
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            Text(
                text = strings.footer,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(KnotworkTheme.spacing.sp4),
            )
        }
    }
}

/**
 * One archived chat. Wrapped in the shared single-action [SwipeRevealRow]
 * (Restore); the same action is repeated inline and in the overflow so the
 * gesture is an accelerator, never the only route.
 */
@Composable
private fun ChatArchiveRow(
    row: ChatArchiveRowUi,
    strings: ChatArchiveStrings,
    menuOpen: Boolean,
    revealed: Boolean?,
    callbacks: ChatArchiveCallbacks,
) {
    val compact = KnotworkTheme.a11y.fontScale() >= COMPACT_FONT_SCALE
    val restoreAction = CustomAccessibilityAction(strings.restore) {
        callbacks.onRestore(row.id)
        true
    }
    val deleteAction = CustomAccessibilityAction(strings.deleteForever) {
        callbacks.onDeleteRequest(row.id)
        true
    }
    Column {
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        SwipeRevealRow(
            action = SwipeRevealAction(
                icon = AppIcons.Unarchive,
                label = strings.restore,
                background = MaterialTheme.colorScheme.primary,
                foreground = MaterialTheme.colorScheme.onPrimary,
                onClick = { callbacks.onRestore(row.id) },
            ),
            revealed = revealed,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(role = Role.Button) { callbacks.onRowClick(row.id) }
                    .semantics { customActions = listOf(restoreAction, deleteAction) }
                    .padding(
                        start = KnotworkTheme.spacing.sp4,
                        end = KnotworkTheme.spacing.sp2,
                        top = KnotworkTheme.spacing.sp3,
                        bottom = KnotworkTheme.spacing.sp3,
                    ),
            ) {
                if (!compact) {
                    ArchiveTile()
                }
                ChatArchiveRowText(row = row, strings = strings, modifier = Modifier.weight(1f))
                Box {
                    // 48 dp because the design asks for it, not for reach:
                    // Material lays `IconButton` out at 40 dp but expands its
                    // touch bounds to 48 regardless, so this changes how the
                    // control looks, not how easy it is to hit.
                    IconButton(
                        onClick = { callbacks.onRowMenuOpen(row.id) },
                        modifier = Modifier.size(ArchiveRowMenuButton),
                    ) {
                        Icon(
                            imageVector = AppIcons.More,
                            contentDescription = strings.rowMenuCd,
                            tint = KnotworkTheme.extended.onSurfaceMuted,
                        )
                    }
                    ChatArchiveRowMenu(
                        row = row,
                        strings = strings,
                        expanded = menuOpen,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }
}

/** Leading tile carrying the archive glyph. Decoration — dropped at 200 %. */
@Composable
private fun ArchiveTile() {
    Box(
        modifier = Modifier
            .size(ArchiveRowTile)
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface2)
            .border(1.dp, KnotworkTheme.extended.outlineStrong, KnotworkTheme.shapes.md),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Archive,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(ArchiveRowTileGlyph),
        )
    }
}

@Composable
private fun ChatArchiveRowText(row: ChatArchiveRowUi, strings: ChatArchiveStrings, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            if (row.starred) {
                Icon(
                    imageVector = AppIcons.Star,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurface2,
                    modifier = Modifier.size(ArchiveRowStar),
                )
            }
            Text(
                text = row.title,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = row.archivedLabel,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            maxLines = 1,
            // Ellipsis, not a hard clip: "Archived yesterday" losing its last
            // word reads as a different (and wrong) statement.
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
        if (row.ranAfterArchiving) {
            // Its own line, not trailing the timestamp as the mockup showed: a
            // 360 dp row that also carries the Restore pill leaves the note ~2
            // characters, so inline it degrades to a bare tick — state carried
            // by a glyph and a colour alone, which is exactly what this surface
            // must not do.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            ) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.signalSuccess,
                    modifier = Modifier.size(ArchiveRowStar),
                )
                Text(
                    text = strings.ranAfterArchiving,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurface2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Row overflow — Restore · Export chat · ─── · Delete forever. */
@Composable
private fun ChatArchiveRowMenu(
    row: ChatArchiveRowUi,
    strings: ChatArchiveStrings,
    expanded: Boolean,
    callbacks: ChatArchiveCallbacks,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = callbacks.onRowMenuDismiss) {
        DropdownMenuItem(
            text = { Text(strings.restore) },
            leadingIcon = { Icon(imageVector = AppIcons.Unarchive, contentDescription = null) },
            onClick = {
                callbacks.onRowMenuDismiss()
                callbacks.onRestore(row.id)
            },
        )
        DropdownMenuItem(
            text = { Text(strings.export) },
            leadingIcon = { Icon(imageVector = AppIcons.Download2, contentDescription = null) },
            onClick = {
                callbacks.onRowMenuDismiss()
                callbacks.onExport(row.id)
            },
        )
        // Destructive item set apart by three cues, not colour alone: below a
        // divider, in the error colour, with the trash glyph.
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        DropdownMenuItem(
            text = { Text(text = strings.deleteForever, color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    imageVector = AppIcons.Trash,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                callbacks.onRowMenuDismiss()
                callbacks.onDeleteRequest(row.id)
            },
        )
    }
}

/**
 * Teaching empty state. Deliberately **no CTA**: the only way to archive a chat
 * is from the chat drawer, and a button that closes the screen the user just
 * opened is a dead end — so the teaching sentence carries the discovery.
 */
@Composable
private fun ChatArchiveEmpty(strings: ChatArchiveStrings) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = strings.emptyTitle,
            subtitle = strings.emptySubtitle,
            illustration = {
                IllustrationTile(icon = AppIcons.Archive, tint = MaterialTheme.colorScheme.primary)
            },
        )
    }
}

@Composable
private fun ChatArchiveError(
    state: ChatArchiveViewState,
    strings: ChatArchiveStrings,
    callbacks: ChatArchiveCallbacks,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(KnotworkTheme.spacing.sp6),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = strings.errorTitle,
            subtitle = state.errorMessage.orEmpty(),
            ctaLabel = strings.errorRetry,
            onCtaClick = callbacks.onRetry,
            illustration = {
                IllustrationTile(icon = AppIcons.AlertCircle, tint = KnotworkTheme.extended.signalError)
            },
        )
    }
}

/**
 * Tinted rounded square framing a single glyph — the same treatment the trigger
 * journal's empty state uses. `AppIcons.Archive` reads cleanly at this size
 * (wide bands, no fine detail), so the archive needs no bespoke illustration.
 */
@Composable
private fun IllustrationTile(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(ArchiveIllustrationTile)
            .clip(KnotworkTheme.shapes.lg)
            .background(tint.copy(alpha = ILLUSTRATION_FILL_ALPHA))
            .border(1.dp, tint.copy(alpha = ILLUSTRATION_BORDER_ALPHA), KnotworkTheme.shapes.lg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ArchiveIllustrationGlyph),
        )
    }
}

/** Skeleton rows drawn while the archived list is first read. */
@Composable
private fun ChatArchiveSkeleton() {
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(SKELETON_ROW_COUNT) {
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
            ) {
                SkeletonBlock(modifier = Modifier.size(ArchiveRowTile), shapeFull = false)
                Column(
                    verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                    modifier = Modifier.weight(1f),
                ) {
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION).height(SkeletonTitleHeight))
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(SKELETON_META_FRACTION).height(SkeletonMetaHeight))
                }
            }
        }
    }
}

/** Width fraction of a skeleton row's title bar. */
private const val SKELETON_TITLE_FRACTION = 0.58f

/** Width fraction of a skeleton row's metadata bar. */
private const val SKELETON_META_FRACTION = 0.34f

/** Height of a skeleton title bar. */
private val SkeletonTitleHeight = 13.dp

/** Height of a skeleton metadata bar. */
private val SkeletonMetaHeight = 11.dp

@Composable
private fun SkeletonBlock(modifier: Modifier = Modifier, shapeFull: Boolean = true) {
    Box(
        modifier = modifier
            .clip(if (shapeFull) KnotworkTheme.shapes.full else KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface3),
    )
}

/**
 * Delete-forever confirmation. A plain [AlertDialog] with a destructive text
 * button rather than the typed-confirm dialog: typed confirmation is for whole
 * data sets, and one thread does not earn that ceremony.
 */
@Composable
private fun DeleteForeverDialog(
    target: ChatArchiveRowUi,
    strings: ChatArchiveStrings,
    callbacks: ChatArchiveCallbacks,
) {
    AlertDialog(
        onDismissRequest = callbacks.onDeleteDismiss,
        title = { Text(strings.deleteTitle) },
        text = { Text(strings.deleteBodyTemplate.format(target.title)) },
        confirmButton = {
            KnotworkTextButton(
                text = strings.deleteConfirm,
                destructive = true,
                onClick = { callbacks.onDeleteConfirm(target.id) },
            )
        },
        dismissButton = {
            KnotworkTextButton(text = strings.deleteCancel, onClick = callbacks.onDeleteDismiss)
        },
    )
}
