package app.knotwork.design.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.lists.SwipeRevealAction
import app.knotwork.design.components.lists.SwipeRevealRow
import app.knotwork.design.components.misc.KnotworkLoader
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Width of the drawer overlay panel (Material spec for navigation drawers). */
private val DrawerWidth = 320.dp

/** Alpha of the scrim painted over the chat surface while the drawer is open. */
private const val DRAWER_SCRIM_ALPHA = 0.32f

/**
 * Slide-in drawer overlay rendered when [ChatHomeVisualState.DrawerOpen]
 * is active:
 *  - `SESSIONS` mono header.
 *  - Big rounded `+ New chat` pill on `Accent50` with brand-primary glyph
 *    and label.
 *  - Thread list with leading status dot, bold title, mono subtitle, and a
 *    trailing `⋮` overflow (Rename / Archive / Delete chat). Each row also
 *    reveals a single Archive action on swipe. The active thread tints its row
 *    with `primaryContainer` and pulls the dot up to the brand primary.
 *  - Footer list rows — `Archived chats` (only once something is archived),
 *    `Import chat (From JSON / text)` and `Settings (API keys · model params)`
 *    — separated from the list by a divider.
 */
@Composable
internal fun ChatHomeDrawerOverlay(state: ChatHomeViewState, callbacks: ChatHomeCallbacks) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = Color.Black.copy(alpha = DRAWER_SCRIM_ALPHA))
            .scrimClickable(onClick = callbacks.onCloseDrawer),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            color = KnotworkTheme.extended.surface1,
            tonalElevation = KnotworkTheme.elevation.el2,
            modifier = Modifier
                .width(DrawerWidth)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .absorbClicks(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // -------- Header + New chat pill --------
                Text(
                    text = stringResource(R.string.knotwork_chat_home_drawer_sessions_header),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.padding(
                        start = KnotworkTheme.spacing.sp4,
                        end = KnotworkTheme.spacing.sp4,
                        top = KnotworkTheme.spacing.sp4,
                        bottom = KnotworkTheme.spacing.sp2,
                    ),
                )
                DrawerNewChatPill(
                    onClick = {
                        callbacks.onNewThread()
                        callbacks.onCloseDrawer()
                    },
                )
                Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp2))
                // -------- Sessions list --------
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(items = state.threads, key = { it.id }) { thread ->
                        ChatHomeDrawerThreadRow(
                            row = thread,
                            menuOpen = state.openThreadMenuId == thread.id,
                            revealed = state.revealedThreadId?.let { it == thread.id },
                            callbacks = callbacks,
                        )
                    }
                }
                // -------- Footer entries --------
                HorizontalDivider(color = KnotworkTheme.extended.divider)
                // Shown only when something is actually archived: a permanently
                // empty row is clutter in the 320 dp list the user opens to
                // switch threads. The always-present More-tab entry is what
                // keeps the feature discoverable at zero.
                if (state.archivedCount > 0) {
                    DrawerFooterRow(
                        icon = AppIcons.Archive,
                        title = stringResource(R.string.knotwork_chat_home_drawer_archive_title),
                        subtitle = pluralStringResource(
                            R.plurals.knotwork_chat_home_drawer_archive_subtitle,
                            state.archivedCount,
                            state.archivedCount,
                        ),
                        onClick = {
                            callbacks.onOpenArchive()
                            callbacks.onCloseDrawer()
                        },
                    )
                }
                DrawerFooterRow(
                    icon = AppIcons.Download,
                    title = stringResource(R.string.knotwork_chat_home_drawer_import_title),
                    subtitle = stringResource(R.string.knotwork_chat_home_drawer_import_subtitle),
                    onClick = {
                        callbacks.onImportChat()
                        callbacks.onCloseDrawer()
                    },
                )
                DrawerFooterRow(
                    icon = AppIcons.Theme,
                    title = stringResource(R.string.knotwork_chat_home_drawer_settings_title),
                    subtitle = stringResource(R.string.knotwork_chat_home_drawer_settings_subtitle),
                    onClick = {
                        callbacks.onOpenSettings()
                        callbacks.onCloseDrawer()
                    },
                )
            }
        }
    }
}

@Composable
private fun DrawerNewChatPill(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .padding(horizontal = KnotworkTheme.spacing.sp4)
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.full)
            .background(color = app.knotwork.design.tokens.KnotworkPalette.Accent100)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = AppIcons.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.knotwork_chat_home_drawer_new_thread),
            style = KnotworkTextStyles.LabelLg.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * One drawer thread.
 *
 * The trailing slot carries a single control — `⋮` — because 320 dp will not
 * hold two icon buttons at font-scale 200 %, and Rename is not more important
 * than Archive; both now live in the menu. Archive is additionally reachable by
 * a one-action swipe and as a TalkBack custom action, so the gesture is an
 * accelerator and never the only path.
 */
@Composable
private fun ChatHomeDrawerThreadRow(
    row: ChatHomeThreadRow,
    menuOpen: Boolean,
    revealed: Boolean?,
    callbacks: ChatHomeCallbacks,
) {
    SwipeRevealRow(
        action = SwipeRevealAction(
            icon = AppIcons.Archive,
            label = stringResource(R.string.knotwork_chat_home_drawer_archive_action),
            background = KnotworkTheme.extended.signalWarn,
            foreground = MaterialTheme.colorScheme.onPrimary,
            onClick = { callbacks.onArchiveThread(row.id) },
        ),
        revealed = revealed,
    ) {
        ChatHomeDrawerThreadRowBody(
            row = row,
            menuOpen = menuOpen,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun ChatHomeDrawerThreadRowBody(row: ChatHomeThreadRow, menuOpen: Boolean, callbacks: ChatHomeCallbacks) {
    val onClick = {
        callbacks.onSelectThread(row.id)
        callbacks.onCloseDrawer()
    }
    // Pair the selected-row background and the on-row text colour through the
    // Material3 colour scheme so the contrast stays WCAG-AA in both themes.
    // The previous `KnotworkPalette.Accent50` was a static tan that washed out
    // against `onSurface` on dark theme (mirror of the onboarding row fix).
    val selected = row.active || row.selected
    val rowBg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val rowFg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowSubtitleFg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        KnotworkTheme.extended.onSurfaceMuted
    }
    val dotColor = if (row.active) {
        MaterialTheme.colorScheme.primary
    } else {
        KnotworkTheme.extended.onSurfaceMuted
    }
    val runningDescription = stringResource(R.string.knotwork_chat_home_drawer_running_cd)
    // At 200 % the 8 dp dot (plus its gap) is the cheapest ~20 dp to give back
    // to the title: "active" is already carried by the primaryContainer fill,
    // so the dot is a redundant channel here, unlike the star / loader / ⋮ which
    // each say something nothing else does.
    val compact = KnotworkTheme.a11y.fontScale() >= DRAWER_COMPACT_FONT_SCALE
    val archiveAction = CustomAccessibilityAction(
        stringResource(R.string.knotwork_chat_home_drawer_archive_a11y),
    ) {
        callbacks.onArchiveThread(row.id)
        true
    }
    val renameAction = CustomAccessibilityAction(
        stringResource(R.string.knotwork_chat_home_drawer_menu_rename),
    ) {
        callbacks.onEditThread(row.id)
        true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            // Opaque even when unselected: the row slides over the swipe strip,
            // so a transparent background would let the strip bleed through.
            // Matches the drawer Surface underneath, so nothing changes visually
            // at rest; the selected fill paints over it and travels with the row.
            .background(color = KnotworkTheme.extended.surface1)
            .background(color = rowBg)
            .clickable(onClick = onClick)
            .semantics { customActions = listOf(renameAction, archiveAction) }
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        if (!compact) {
            Box(
                modifier = Modifier
                    .size(DRAWER_STATUS_DOT_SIZE)
                    .background(color = dotColor, shape = CircleShape),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            ) {
                if (row.starred) {
                    Icon(
                        imageVector = AppIcons.Star,
                        contentDescription =
                        stringResource(R.string.knotwork_chat_home_drawer_starred_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(DRAWER_STARRED_ICON_SIZE),
                    )
                }
                Text(
                    text = row.title,
                    // Compact list-title token (15 sp Medium) — the drawer holds many
                    // rows, so a lighter, denser title reads better than the larger
                    // 17 sp SemiBold used for standalone list rows.
                    style = KnotworkTextStyles.LabelLg,
                    color = rowFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.subtitle,
                style = KnotworkTextStyles.MonoSm,
                color = rowSubtitleFg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.running) {
            // The design-system loader (not a raw Material spinner): it
            // honours the reduced-motion contract by collapsing to a static
            // glyph, and keeps the in-progress visual language consistent
            // with the other inline-busy surfaces. The wrapper overrides
            // the loader's generic "Loading" announcement with row-specific
            // copy.
            Box(
                modifier = Modifier.semantics {
                    contentDescription = runningDescription
                },
            ) {
                KnotworkLoader()
            }
        }
        Box {
            IconButton(onClick = { callbacks.onThreadMenuOpen(row.id) }) {
                Icon(
                    imageVector = AppIcons.More,
                    contentDescription = stringResource(R.string.knotwork_chat_home_drawer_menu_cd),
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            ChatHomeDrawerThreadMenu(row = row, expanded = menuOpen, callbacks = callbacks)
        }
    }
}

/**
 * Drawer row overflow — Rename · Archive · ─── · Delete chat.
 *
 * Delete is set apart by three cues rather than colour alone: it sits below a
 * divider, in the error colour, behind the trash glyph. Deleting is confirmed
 * by the host, never here.
 */
@Composable
private fun ChatHomeDrawerThreadMenu(row: ChatHomeThreadRow, expanded: Boolean, callbacks: ChatHomeCallbacks) {
    DropdownMenu(expanded = expanded, onDismissRequest = callbacks.onThreadMenuDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.knotwork_chat_home_drawer_menu_rename)) },
            leadingIcon = { Icon(imageVector = AppIcons.Edit, contentDescription = null) },
            onClick = {
                callbacks.onThreadMenuDismiss()
                callbacks.onEditThread(row.id)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.knotwork_chat_home_drawer_archive_action)) },
            leadingIcon = { Icon(imageVector = AppIcons.Archive, contentDescription = null) },
            onClick = {
                callbacks.onThreadMenuDismiss()
                callbacks.onArchiveThread(row.id)
            },
        )
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.knotwork_chat_home_drawer_menu_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = AppIcons.Trash,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                callbacks.onThreadMenuDismiss()
                callbacks.onDeleteThread(row.id)
            },
        )
    }
}

@Composable
private fun DrawerFooterRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(KnotworkTheme.spacing.sp6),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = KnotworkTextStyles.TitleMd.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

/** Diameter of the leading status dot rendered next to each session row. */
private val DRAWER_STATUS_DOT_SIZE = 8.dp

/**
 * Font scale at or above which the drawer row drops its 8 dp status dot and
 * gives the space to the title. "Active" is already carried by the row's
 * primaryContainer fill, so the dot is the only redundant element on the row.
 */
private const val DRAWER_COMPACT_FONT_SCALE = 2.0f

/** Size of the inline star glyph rendered before a favorited session's title. */
private val DRAWER_STARRED_ICON_SIZE = 14.dp
