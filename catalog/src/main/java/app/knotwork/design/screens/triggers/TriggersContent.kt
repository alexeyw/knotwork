@file:Suppress("MatchingDeclarationName") // Hosts TriggersContent + helpers.

package app.knotwork.design.screens.triggers

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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.HealthBadge
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.components.topbar.JournalExportActions
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading trigger identity tile on a list row. */
private val TriggerRowTile = 40.dp

/** Inner glyph size on the row tile. */
private val TriggerRowGlyph = 20.dp

/** Side length of the FAB icon. */
private val FabIconSize = 24.dp

/** Side length of the per-row overflow icon button. */
private val RowMenuButton = 36.dp

/** Down-scale factor for the inline row switch (matches `LabeledSwitchRow`). */
private const val ROW_SWITCH_SCALE = 0.78f

/**
 * Resolves the leading / condition-line glyph for a condition type.
 */
internal fun TriggerConditionType.glyph(): ImageVector = when (this) {
    TriggerConditionType.Interval -> AppIcons.Refresh
    TriggerConditionType.Daily -> AppIcons.History
    TriggerConditionType.Charging -> AppIcons.Battery
    TriggerConditionType.Network -> AppIcons.Globe
}

/**
 * Stateless Knotwork Triggers surface — a list of automation rules (condition →
 * bound pipeline) with an inline enable switch and per-row overflow actions, a
 * "New trigger" FAB, and a bespoke teaching empty state.
 *
 * @param state immutable view state — drives loader / list / empty / error.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings.
 * @param callbacks one-shot callback bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggersContent(
    state: TriggersViewState,
    modifier: Modifier = Modifier,
    strings: TriggersStrings = TriggersStrings(),
    callbacks: TriggersCallbacks = noopTriggersCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                TriggersTopBar(state = state, strings = strings, callbacks = callbacks)
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
        floatingActionButton = {
            // FAB suppressed on loading, error and the teaching empty state (which
            // owns its own centred CTA).
            if (state.visualState == TriggersVisualState.Default) {
                FloatingActionButton(
                    onClick = callbacks.onNewTrigger,
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.visualState) {
                TriggersVisualState.Loading -> TriggersLoading()
                TriggersVisualState.Error -> TriggersError(state = state, strings = strings, callbacks = callbacks)
                TriggersVisualState.Empty -> TriggersEmpty(strings = strings, callbacks = callbacks)
                TriggersVisualState.Default -> TriggersList(state = state, strings = strings, callbacks = callbacks)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggersTopBar(state: TriggersViewState, strings: TriggersStrings, callbacks: TriggersCallbacks) {
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
        actions = {
            // Deliberately not gated on the list's own load state: the journal is
            // read separately, so a screen that failed to list its triggers can
            // still hand over the journal — which is exactly the file worth
            // sending when that happens.
            IconButton(onClick = callbacks.onOpenDocumentation) {
                Icon(
                    imageVector = AppIcons.Book,
                    contentDescription = strings.documentationCd,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            JournalExportActions(
                shareContentDescription = strings.exportShareCd,
                saveContentDescription = strings.exportSaveCd,
                onShare = callbacks.onShareJournal,
                onSave = callbacks.onSaveJournal,
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun TriggersLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TriggersError(state: TriggersViewState, strings: TriggersStrings, callbacks: TriggersCallbacks) {
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

/**
 * The bespoke teaching empty state — explains the "trigger → background run →
 * result in chat" model with a three-step illustration and a primary CTA.
 */
@Composable
private fun TriggersEmpty(strings: TriggersStrings, callbacks: TriggersCallbacks) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = strings.emptyTitle,
            subtitle = strings.emptyBody,
            ctaLabel = strings.emptyCta,
            onCtaClick = callbacks.onNewTrigger,
            illustration = { TriggerStepsIllustration(strings = strings) },
        )
    }
}

/** Three-step "moment fires → runs on its own → result in chat" motif. */
@Composable
private fun TriggerStepsIllustration(strings: TriggersStrings) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        TriggerStep(icon = AppIcons.Trigger, label = strings.emptyStepTrigger)
        StepArrow()
        TriggerStep(icon = AppIcons.Flow, label = strings.emptyStepRun)
        StepArrow()
        TriggerStep(icon = AppIcons.Chat, label = strings.emptyStepResult)
    }
}

@Composable
private fun TriggerStep(icon: ImageVector, label: String) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Box(
            modifier = Modifier
                .size(TriggerRowTile)
                .clip(KnotworkTheme.shapes.md)
                .background(accent.copy(alpha = 0.12f))
                .border(1.dp, accent.copy(alpha = 0.24f), KnotworkTheme.shapes.md),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(TriggerRowGlyph),
            )
        }
        Text(text = label, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
    }
}

@Composable
private fun StepArrow() {
    Icon(
        imageVector = AppIcons.ArrowR,
        contentDescription = null,
        tint = KnotworkTheme.extended.onSurfaceDim,
        modifier = Modifier.size(16.dp),
    )
}

@Composable
private fun TriggersList(state: TriggersViewState, strings: TriggersStrings, callbacks: TriggersCallbacks) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp16),
    ) {
        items(items = state.rows, key = { it.id }) { row ->
            TriggerRow(row = row, strings = strings, menuOpen = state.openMenuRowId == row.id, callbacks = callbacks)
        }
    }
}

@Composable
private fun TriggerRow(row: TriggerRowUi, strings: TriggersStrings, menuOpen: Boolean, callbacks: TriggersCallbacks) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { callbacks.onRowClick(row.id) }
                    .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                verticalAlignment = Alignment.Top,
            ) {
                TriggerTile(type = row.conditionType, inert = !row.isBound)
                TriggerRowText(row = row, strings = strings, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // An unbound trigger cannot be switched on, and the greyed
                    // switch that used to say so said the wrong thing: the first
                    // external tester read dimmed controls as broken rather than
                    // as waiting on him — "Не выглядят как выключенными, а
                    // выглядят как недоступные". So the control is absent, and
                    // its place is taken by the verb that fixes it. The reason
                    // is already on the row, in words, above.
                    if (row.isBound) {
                        RowSwitch(checked = row.enabled, enabled = true) {
                            callbacks.onRowToggle(row.id)
                        }
                    } else {
                        KnotworkTextButton(
                            text = strings.bind,
                            onClick = { callbacks.onRowClick(row.id) },
                            // Named per row: in a list of unbound triggers a bare
                            // "Bind, button" repeated N times tells a screen-reader
                            // user which rows need attention but not which one they
                            // are on. The switch it replaced inherited the row label.
                            modifier = Modifier.semantics {
                                contentDescription = strings.bindCd.format(row.name)
                            },
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
                        TriggerRowMenu(row = row, strings = strings, expanded = menuOpen, callbacks = callbacks)
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerRowText(row: TriggerRowUi, strings: TriggersStrings, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        // Name + health badge. The name is weighted `fill = false` so a long name
        // ellipsises *before* it can squeeze the fixed-size badge off the row
        // (see reference_compose_row_badge_squeeze).
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
            row.health?.let { HealthBadge(state = it, label = it.label(strings)) }
        }
        // Condition line.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Icon(
                imageVector = row.conditionType.glyph(),
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = row.conditionLabel,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Bound-pipeline line.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            if (row.isBound) {
                Icon(
                    imageVector = AppIcons.Flow,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = row.pipelineName.orEmpty(),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Icon(
                    imageVector = AppIcons.Link,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceDim,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = strings.unboundHint,
                    style = KnotworkTextStyles.BodySm.copy(fontStyle = FontStyle.Italic),
                    color = KnotworkTheme.extended.onSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TriggerRowMenu(
    row: TriggerRowUi,
    strings: TriggersStrings,
    expanded: Boolean,
    callbacks: TriggersCallbacks,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = callbacks.onRowMenuDismiss,
        containerColor = KnotworkTheme.extended.surface1,
    ) {
        TriggerMenuItem(label = strings.menuEdit, icon = AppIcons.Edit) {
            callbacks.onRowMenuDismiss()
            callbacks.onEditTrigger(row.id)
        }
        TriggerMenuItem(label = strings.menuDelete, icon = AppIcons.Trash, destructive = true) {
            callbacks.onRowMenuDismiss()
            callbacks.onDeleteTrigger(row.id)
        }
    }
}

@Composable
private fun TriggerMenuItem(label: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) KnotworkTheme.extended.riskDestructive else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(text = label, style = KnotworkTextStyles.BodySm, color = tint) },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        },
        onClick = onClick,
    )
}

/**
 * The trigger identity tile — a tinted square holding the condition glyph. An
 * unbound (inert) trigger renders desaturated with a dashed outline so the row
 * reads as "fires nothing".
 */
@Composable
private fun TriggerTile(type: TriggerConditionType, inert: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val background = if (inert) KnotworkTheme.extended.surface2 else accent.copy(alpha = 0.12f)
    val borderColor = if (inert) KnotworkTheme.extended.outlineStrong else accent.copy(alpha = 0.24f)
    val tint = if (inert) KnotworkTheme.extended.onSurfaceDim else accent
    Box(
        modifier = Modifier
            .size(TriggerRowTile)
            .clip(KnotworkTheme.shapes.md)
            .background(background)
            .border(1.dp, borderColor, KnotworkTheme.shapes.md),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = type.glyph(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TriggerRowGlyph),
        )
    }
}

/** Inline enable switch on a list row; disabled (non-interactive) when unbound. */
@Composable
private fun RowSwitch(checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = if (enabled) ({ onToggle() }) else null,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.scale(ROW_SWITCH_SCALE),
    )
}

/** Localised string bundle threaded into [TriggersContent]. */
@Suppress("LongParameterList")
data class TriggersStrings(
    val title: String = "Triggers",
    val backCd: String = "Back",
    val moreCd: String = "More actions",
    val fab: String = "New trigger",
    val unboundHint: String = "No pipeline yet",
    val bind: String = "Bind",
    val bindCd: String = "Bind a pipeline to %1\$s",
    val menuEdit: String = "Edit",
    val menuDelete: String = "Delete",
    val emptyTitle: String = "Run the agent on its own",
    val emptyBody: String =
        "A trigger watches for a moment — a time of day or a device event — then runs a pipeline in the " +
            "background and drops the result into a chat. No need to be there.",
    val emptyCta: String = "Create your first trigger",
    val emptyStepTrigger: String = "Trigger",
    val emptyStepRun: String = "Runs",
    val emptyStepResult: String = "Result",
    val errorTitle: String = "Couldn't load triggers",
    val errorRetry: String = "Retry",
    val healthHealthy: String = "Healthy",
    val healthOverdue: String = "Overdue",
    val healthLastRunFailed: String = "Last run failed",
    // Journal export. The two actions live on the list and not on a trigger's
    // detail screen because the document they produce is the whole journal, every
    // trigger at once — the only shape an analysis of "which day had no
    // evaluation at all" can be answered from.
    val exportShareCd: String = "Share the evaluation journal",
    val exportSaveCd: String = "Save the evaluation journal",
    val documentationCd: String = "Read about triggers",
)

/** Resolves the localised health-badge label for a row's health state. */
internal fun TriggerHealthUi.label(strings: TriggersStrings): String = when (this) {
    TriggerHealthUi.Healthy -> strings.healthHealthy
    TriggerHealthUi.Overdue -> strings.healthOverdue
    TriggerHealthUi.LastRunFailed -> strings.healthLastRunFailed
}
