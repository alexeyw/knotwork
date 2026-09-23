@file:Suppress("MatchingDeclarationName") // Hosts SkillLibraryContent + helpers.

package app.knotwork.design.screens.skills

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading skill identity tile on a library row. */
private val SkillRowTile = 40.dp

/** Inner glyph size on the row tile. */
private val SkillRowGlyph = 20.dp

/** Side length of the FAB icon. */
private val FabIconSize = 24.dp

/** Side length of the per-row overflow icon button. */
private val RowMenuButton = 36.dp

/** Height of the active-tab underline indicator. */
private val TabIndicatorHeight = 2.dp

/**
 * Stateless Knotwork Skill Library surface — a 2-tab (Bundled / Mine) list of
 * reusable skills with per-row overflow actions and a "New skill" FAB.
 *
 * @param state immutable view state — drives loader / list / empty / error.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings.
 * @param callbacks one-shot callback bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillLibraryContent(
    state: SkillLibraryViewState,
    modifier: Modifier = Modifier,
    strings: SkillLibraryStrings = SkillLibraryStrings(),
    callbacks: SkillLibraryCallbacks = noopSkillLibraryCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                SkillsTopBar(state = state, strings = strings, callbacks = callbacks)
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
        floatingActionButton = {
            if (state.visualState != SkillLibraryVisualState.Loading &&
                state.visualState != SkillLibraryVisualState.Error
            ) {
                FloatingActionButton(
                    onClick = callbacks.onNewSkill,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = KnotworkTheme.shapes.md,
                ) {
                    Icon(
                        imageVector = AppIcons.Add,
                        contentDescription = strings.fab,
                        modifier = Modifier.size(FabIconSize),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SkillsTabs(state = state, strings = strings, callbacks = callbacks)
            when (state.visualState) {
                SkillLibraryVisualState.Loading -> SkillsLoading()
                SkillLibraryVisualState.Error -> SkillsError(state = state, strings = strings, callbacks = callbacks)
                SkillLibraryVisualState.EmptyMine -> SkillsEmptyMine(strings = strings)
                SkillLibraryVisualState.Default -> SkillsList(state = state, strings = strings, callbacks = callbacks)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillsTopBar(
    state: SkillLibraryViewState,
    strings: SkillLibraryStrings,
    callbacks: SkillLibraryCallbacks,
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
                    contentDescription = strings.backCd,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        // Actions slot intentionally empty — the library is browsed by the
        // Bundled / Mine tabs, not a text search (matches the Prompt library).
        actions = {},
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun SkillsTabs(state: SkillLibraryViewState, strings: SkillLibraryStrings, callbacks: SkillLibraryCallbacks) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        SkillTab(
            label = strings.tabBundled,
            count = state.bundledCount,
            active = state.tab == SkillLibraryTab.Bundled,
            onClick = { callbacks.onTabSelected(SkillLibraryTab.Bundled) },
            modifier = Modifier.weight(1f),
        )
        SkillTab(
            label = strings.tabMine,
            count = state.mineCount,
            active = state.tab == SkillLibraryTab.Mine,
            onClick = { callbacks.onTabSelected(SkillLibraryTab.Mine) },
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = KnotworkTheme.extended.divider)
}

@Composable
private fun SkillTab(label: String, count: Int, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val tint = if (active) accent else KnotworkTheme.extended.onSurfaceMuted
    Column(
        modifier = modifier.clickable(onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.padding(vertical = KnotworkTheme.spacing.sp3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            Text(
                text = label,
                style = KnotworkTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
                color = tint,
            )
            Box(
                modifier = Modifier
                    .clip(KnotworkTheme.shapes.full)
                    .background(
                        if (active) {
                            accent.copy(alpha = 0.12f)
                        } else {
                            KnotworkTheme.extended.surface2
                        },
                    )
                    .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
            ) {
                Text(text = count.toString(), style = KnotworkTextStyles.MonoSm, color = tint)
            }
        }
        // Full-width 2 dp indicator flush to the bottom of the tab — transparent
        // when inactive so the row height never jumps. The sp3 bottom padding
        // above keeps a clear gap between the label and this line.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TabIndicatorHeight)
                .background(if (active) accent else androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun SkillsLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SkillsEmptyMine(strings: SkillLibraryStrings) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(title = strings.emptyMineTitle, subtitle = strings.emptyMineBody)
    }
}

@Composable
private fun SkillsError(state: SkillLibraryViewState, strings: SkillLibraryStrings, callbacks: SkillLibraryCallbacks) {
    Box(
        modifier = Modifier.fillMaxSize().padding(KnotworkTheme.spacing.sp6),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = strings.errorTitle,
            subtitle = state.errorMessage.orEmpty(),
            ctaLabel = strings.errorRetry,
            onCtaClick = callbacks.onRetry,
        )
    }
}

@Composable
private fun SkillsList(state: SkillLibraryViewState, strings: SkillLibraryStrings, callbacks: SkillLibraryCallbacks) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp16),
    ) {
        items(items = state.rows, key = { it.id }) { row ->
            SkillRow(row = row, strings = strings, menuOpen = state.openMenuRowId == row.id, callbacks = callbacks)
        }
    }
}

@Composable
private fun SkillRow(
    row: SkillRowUi,
    strings: SkillLibraryStrings,
    menuOpen: Boolean,
    callbacks: SkillLibraryCallbacks,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { callbacks.onRowClick(row.id) }
                    .padding(
                        horizontal = KnotworkTheme.spacing.sp4,
                        vertical = KnotworkTheme.spacing.sp3,
                    ),
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            ) {
                SkillTile()
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                    ) {
                        Text(
                            text = row.name,
                            style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.isBundled) BundledBadge(strings = strings)
                    }
                    Text(
                        text = row.description,
                        style = KnotworkTextStyles.BodySm,
                        color = KnotworkTheme.extended.onSurface2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ToolPill(
                        indicator = row.toolIndicator,
                        subsetLabel = row.toolCountLabel.ifEmpty { strings.toolsCountFormat.format(row.toolCount) },
                        strings = strings,
                        modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
                    )
                }
                Box {
                    IconButton(
                        onClick = { callbacks.onRowMenuOpen(row.id) },
                        modifier = Modifier.size(RowMenuButton),
                    ) {
                        Icon(
                            imageVector = AppIcons.More,
                            contentDescription = strings.moreCd,
                            tint = KnotworkTheme.extended.onSurfaceMuted,
                        )
                    }
                    SkillRowMenu(row = row, strings = strings, expanded = menuOpen, callbacks = callbacks)
                }
            }
        }
    }
}

@Composable
private fun SkillRowMenu(
    row: SkillRowUi,
    strings: SkillLibraryStrings,
    expanded: Boolean,
    callbacks: SkillLibraryCallbacks,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = callbacks.onRowMenuDismiss,
        containerColor = KnotworkTheme.extended.surface1,
    ) {
        // Bundled rows are read-only: View (open the read-only viewer) +
        // Duplicate. User rows: Edit + Duplicate + Delete.
        if (row.isBundled) {
            SkillMenuItem(label = strings.menuView, icon = AppIcons.Eye) {
                callbacks.onRowMenuDismiss()
                callbacks.onViewSkill(row.id)
            }
        } else {
            SkillMenuItem(label = strings.menuEdit, icon = AppIcons.Edit) {
                callbacks.onRowMenuDismiss()
                callbacks.onEditSkill(row.id)
            }
        }
        SkillMenuItem(label = strings.menuDuplicate, icon = AppIcons.Copy) {
            callbacks.onRowMenuDismiss()
            callbacks.onDuplicateSkill(row.id)
        }
        if (!row.isBundled) {
            SkillMenuItem(label = strings.menuDelete, icon = AppIcons.Trash, destructive = true) {
                callbacks.onRowMenuDismiss()
                callbacks.onDeleteSkill(row.id)
            }
        }
    }
}

@Composable
private fun SkillMenuItem(label: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) KnotworkTheme.extended.riskDestructive else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(text = label, style = KnotworkTextStyles.BodySm, color = tint) },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        },
        onClick = onClick,
    )
}

/** The skill identity tile — a tinted square holding the framed-star glyph. */
@Composable
private fun SkillTile() {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(SkillRowTile)
            .clip(KnotworkTheme.shapes.md)
            .background(accent.copy(alpha = 0.12f))
            .border(width = 1.dp, color = accent.copy(alpha = 0.24f), shape = KnotworkTheme.shapes.md),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Skill,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(SkillRowGlyph),
        )
    }
}

@Composable
private fun BundledBadge(strings: SkillLibraryStrings) {
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.xs)
            .background(KnotworkTheme.extended.surface3)
            .padding(horizontal = KnotworkTheme.spacing.sp1, vertical = 1.dp),
    ) {
        Text(
            text = strings.bundledBadge,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

/**
 * Tool-restriction indicator. The whole point is that All ≠ None,
 * unambiguously: All is a filled accent pill, None is a muted outline pill,
 * and Subset is a neutral outline pill with a count.
 */
@Composable
private fun ToolPill(
    indicator: SkillToolIndicator,
    subsetLabel: String,
    strings: SkillLibraryStrings,
    modifier: Modifier = Modifier,
) {
    when (indicator) {
        SkillToolIndicator.All -> ToolPillContent(
            icon = AppIcons.Globe,
            label = strings.toolsAll,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            border = null,
            modifier = modifier,
        )
        SkillToolIndicator.None -> ToolPillContent(
            icon = AppIcons.Block,
            label = strings.toolsNone,
            container = null,
            content = KnotworkTheme.extended.onSurfaceMuted,
            border = MaterialTheme.colorScheme.outline,
            modifier = modifier,
        )
        SkillToolIndicator.Subset -> ToolPillContent(
            icon = AppIcons.Tool,
            label = subsetLabel,
            container = null,
            content = KnotworkTheme.extended.onSurface2,
            border = KnotworkTheme.extended.outlineStrong,
            modifier = modifier,
        )
    }
}

@Composable
private fun ToolPillContent(
    icon: ImageVector,
    label: String,
    container: androidx.compose.ui.graphics.Color?,
    content: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(KnotworkTheme.shapes.full)
            .background(container ?: androidx.compose.ui.graphics.Color.Transparent)
            .then(
                if (border != null) {
                    Modifier.border(width = 1.dp, color = border, shape = KnotworkTheme.shapes.full)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
        Text(text = label, style = KnotworkTextStyles.MonoSm, color = content)
    }
}

/** Localised string bundle threaded into [SkillLibraryContent]. */
@Suppress("LongParameterList")
data class SkillLibraryStrings(
    val title: String = "Skill library",
    val backCd: String = "Back",
    val searchCd: String = "Search",
    val moreCd: String = "More actions",
    val fab: String = "New skill",
    val tabBundled: String = "Bundled",
    val tabMine: String = "Mine",
    val bundledBadge: String = "BUNDLED",
    val toolsAll: String = "All tools",
    val toolsNone: String = "No tools",
    val toolsCountFormat: String = "%1\$d tools",
    val menuView: String = "View",
    val menuEdit: String = "Edit",
    val menuDuplicate: String = "Duplicate",
    val menuDelete: String = "Delete",
    val emptyMineTitle: String = "No skills of your own yet",
    val emptyMineBody: String =
        "Duplicate a bundled skill to tweak it, or tap New skill to describe a capability " +
            "once and reuse it across pipelines.",
    val errorTitle: String = "Couldn't load skills",
    val errorRetry: String = "Retry",
)
