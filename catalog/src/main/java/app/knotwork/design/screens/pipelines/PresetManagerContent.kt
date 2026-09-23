package app.knotwork.design.screens.pipelines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.chips.KnotworkChipSize
import app.knotwork.design.components.chips.KnotworkFilterChip
import app.knotwork.design.components.dialogs.SingleFieldDialog
import app.knotwork.design.components.dialogs.SingleFieldDialogUi
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The preset manager: bundled and saved presets, filtered by category, each
 * with rename / export / delete.
 *
 * Moved here from `:app` because the screen had no visual baseline at all while
 * its composition lived there — the same gap that let the provider screen grow a
 * look of its own unnoticed.
 *
 * Transient messages are deliberately not part of this surface. Export outcomes
 * go to the activity-level relay, which renders above the NavGraph, so a message
 * survives the screen being navigated away from.
 *
 * @param state Resolved rows, chips, tabs and dialogs.
 * @param modifier Layout modifier from the caller.
 * @param callbacks Selection, the row actions and the dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetManagerContent(
    state: PresetManagerViewState,
    modifier: Modifier = Modifier,
    callbacks: PresetManagerCallbacks = PresetManagerCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(tag = MANAGER_ROOT_TEST_TAG),
        // Suppress the default body insets so the bottom-nav bar does not leave
        // a visible gap above the row list. Matches `ChatHomeContent`, the only
        // other multi-tab body sitting directly above the bottom nav.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            KnotworkTopAppBarShell {
                TopAppBar(
                    title = {
                        Column {
                            Text(text = state.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = state.subtitle,
                                style = KnotworkTextStyles.MonoSm,
                                color = KnotworkTheme.extended.onSurfaceMuted,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = callbacks.onBack) {
                            Icon(AppIcons.Back, contentDescription = state.backContentDescription)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PresetTabRow(tabs = state.tabs, onTabSelected = callbacks.onTabSelected)
            PresetCategoryChipRow(
                chips = state.chips,
                onCategorySelected = callbacks.onCategorySelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = KnotworkTheme.spacing.sp4,
                        vertical = KnotworkTheme.spacing.sp3,
                    ),
            )
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            if (state.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(KnotworkTheme.spacing.sp6),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = KnotworkTheme.spacing.sp1),
                ) {
                    items(items = state.rows, key = { it.id }) { row ->
                        PresetManagerRow(row = row, actionLabels = state.actionLabels, callbacks = callbacks)
                        HorizontalDivider(color = KnotworkTheme.extended.divider)
                    }
                }
            }
        }
    }

    state.rename?.let { dialog ->
        RenamePresetDialog(
            dialog = dialog,
            onDismiss = callbacks.onRenameDismiss,
            onConfirm = callbacks.onRenameConfirm,
        )
    }
    state.delete?.let { dialog ->
        AlertDialog(
            onDismissRequest = callbacks.onDeleteDismiss,
            title = { Text(dialog.title) },
            text = { Text(dialog.body) },
            confirmButton = {
                TextButton(onClick = callbacks.onDeleteConfirm) { Text(dialog.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDeleteDismiss) { Text(dialog.cancelLabel) }
            },
        )
    }
}

/**
 * Bundled / Mine tabs with their counts. Shared with the preset picker sheet so
 * the chrome is identical on both.
 *
 * @param tabs The tabs, in order; exactly one is selected.
 * @param onTabSelected A tab was tapped, by id.
 * @param modifier Layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetTabRow(tabs: List<PresetTabUi>, onTabSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    PrimaryTabRow(
        selectedTabIndex = tabs.indexOfFirst { it.selected }.coerceAtLeast(0),
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab.selected,
                onClick = { onTabSelected(tab.id) },
                text = {
                    // "Bundled · N" on one text baseline. A separate badge atom
                    // would shift the label's vertical centre and break the
                    // underline indicator's alignment.
                    Text(
                        text = "${tab.label} · ${tab.count}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (tab.selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            KnotworkTheme.extended.onSurfaceMuted
                        },
                    )
                },
                modifier = Modifier.testTag(tag = presetTabTestTag(tab.id)),
            )
        }
    }
}

/**
 * Category filter chips, the reset chip first. Shared with the picker sheet.
 *
 * @param chips The chips, in order.
 * @param onCategorySelected A chip was tapped; `null` is the reset chip.
 * @param modifier Layout modifier.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetCategoryChipRow(
    chips: List<PresetChipUi>,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier,
    ) {
        chips.forEach { chip ->
            KnotworkFilterChip(
                label = chip.label,
                selected = chip.selected,
                onClick = { onCategorySelected(chip.id) },
                size = KnotworkChipSize.Sm,
                trailingCount = chip.count,
            )
        }
    }
}

/**
 * One preset row: name and category badge, description, the graph's shape, and
 * an overflow carrying only the actions this preset allows.
 *
 * @param row The resolved row.
 * @param actionLabels The overflow's words.
 * @param callbacks Row actions.
 */
@Composable
fun PresetManagerRow(row: PresetRowUi, actionLabels: PresetRowActionLabels, callbacks: PresetManagerCallbacks) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .testTag(tag = managerRowTestTag(row.id)),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                // `weight(fill = false)` is load-bearing: without it the title is
                // measured first against the full row width, leaving the badge
                // zero width — its label then wraps one character per line and
                // renders as a tall vertical sliver that inflates the row.
                // `maxLines = 1` does not prevent this on its own, because the
                // title only ellipsizes once something else constrains it.
                // `fill = false` keeps a short title snug against its badge
                // instead of pushing it to the far edge.
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PresetCategoryBadge(label = row.categoryLabel, tone = row.categoryTone)
            }
            if (row.description.isNotBlank()) {
                Text(
                    text = row.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.flowPreview,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.testTag(tag = managerOverflowTestTag(row.id)),
            ) {
                Icon(AppIcons.More, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (row.canRename) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                        text = { Text(actionLabels.rename) },
                        onClick = {
                            menuOpen = false
                            callbacks.onRename(row.id)
                        },
                    )
                }
                DropdownMenuItem(
                    leadingIcon = { Icon(AppIcons.Download, contentDescription = null) },
                    text = { Text(actionLabels.export) },
                    onClick = {
                        menuOpen = false
                        callbacks.onExport(row.id)
                    },
                )
                if (row.canDelete) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = AppIcons.Trash,
                                contentDescription = null,
                                tint = KnotworkTheme.extended.signalError,
                            )
                        },
                        text = { Text(text = actionLabels.delete, color = KnotworkTheme.extended.signalError) },
                        onClick = {
                            menuOpen = false
                            callbacks.onDelete(row.id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Pill badge naming a preset's category: a 1 dp coloured border, a leading dot,
 * and the label in the same hue.
 *
 * @param label The category's word.
 * @param tone Which accent it takes.
 * @param modifier Layout modifier.
 */
@Composable
fun PresetCategoryBadge(label: String, tone: PresetCategoryToneUi, modifier: Modifier = Modifier) {
    val tint = tone.accent()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = modifier
            .clip(RoundedCornerShape(percent = BADGE_CORNER_PERCENT))
            .border(width = 1.dp, color = tint, shape = RoundedCornerShape(percent = BADGE_CORNER_PERCENT))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = BADGE_VERTICAL_PADDING),
    ) {
        Box(
            modifier = Modifier
                .size(BADGE_DOT_SIZE)
                .clip(RoundedCornerShape(percent = BADGE_CORNER_PERCENT))
                .background(tint),
        )
        Text(text = label, style = KnotworkTextStyles.LabelSm, color = tint)
    }
}

/**
 * The accent for a category tone.
 *
 * Node and signal hues, both hue-locked across light and dark themes, so a badge
 * stays legible in either palette.
 *
 * @return The tint.
 */
@Composable
private fun PresetCategoryToneUi.accent(): Color = when (this) {
    PresetCategoryToneUi.Local -> KnotworkTheme.extended.signalSuccess
    PresetCategoryToneUi.Cloud -> KnotworkTheme.extended.nodeCloud
    PresetCategoryToneUi.Hybrid -> KnotworkTheme.extended.signalWarn
    PresetCategoryToneUi.Tool -> KnotworkTheme.extended.nodeTool
    PresetCategoryToneUi.Research -> KnotworkTheme.extended.nodeDecomposition
    PresetCategoryToneUi.Other -> KnotworkTheme.extended.onSurfaceMuted
}

/**
 * Rename dialog. Holds the edited name itself, because a text field that
 * round-trips every keystroke through the caller is the shape that makes
 * a rename feel laggy.
 *
 * @param dialog Resolved copy and the initial name.
 * @param onDismiss Dialog dismissed.
 * @param onConfirm Confirmed with the new name.
 */
@Composable
private fun RenamePresetDialog(dialog: PresetRenameDialogUi, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    SingleFieldDialog(
        ui = SingleFieldDialogUi(
            title = dialog.title,
            label = dialog.label,
            initialValue = dialog.initialName,
            confirmLabel = dialog.confirmLabel,
            cancelLabel = dialog.cancelLabel,
        ),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** Root test tag of the preset manager surface. */
const val MANAGER_ROOT_TEST_TAG: String = "pipeline_presets_manager"

/**
 * Test tag of one tab.
 *
 * @param tabId The tab's id.
 * @return Its tag.
 */
fun presetTabTestTag(tabId: String): String = "preset_picker_tab_$tabId"

/**
 * Test tag of one preset row.
 *
 * @param id The preset's id.
 * @return Its tag.
 */
fun managerRowTestTag(id: String): String = "manager_row_$id"

/**
 * Test tag of one row's overflow button.
 *
 * @param id The preset's id.
 * @return Its tag.
 */
fun managerOverflowTestTag(id: String): String = "manager_overflow_$id"

private const val BADGE_CORNER_PERCENT = 50
private val BADGE_DOT_SIZE = 6.dp
private val BADGE_VERTICAL_PADDING = 2.dp
