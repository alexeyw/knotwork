@file:Suppress("MatchingDeclarationName", "TooManyFunctions") // Hosts TriggerDetailContent + its private helpers.

package app.knotwork.design.screens.triggers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of a header meta-row leading glyph. */
private val HeaderGlyphSize = 18.dp

/** Side length of a journal timeline node (verdict) tile. */
private val TimelineTileSize = 36.dp

/** Inner glyph size on a timeline tile. */
private val TimelineGlyphSize = 19.dp

/** Width of the timeline gutter column. */
private val TimelineGutterWidth = 36.dp

/** Small inline glyph size on entry / outcome lines. */
private val InlineGlyphSize = 13.dp

/** Diameter of the pending "Running…" dot. */
private val PendingDotSize = 8.dp

/** Empty-state journal illustration tile. */
private val EmptyTileSize = 64.dp

/** Inner glyph on the empty-state tile. */
private val EmptyGlyphSize = 30.dp

/** Skeleton bar height on a loading journal row. */
private val SkeletonBarHeight = 12.dp

/** Pending-dot pulse period, matching `StatusPill`. */
private const val PENDING_PULSE_MS = 1400

private const val TILE_ACCENT_ALPHA = 0.12f
private const val TILE_BORDER_ALPHA = 0.24f
private const val BANNER_FILL_ALPHA = 0.12f
private const val BANNER_BORDER_ALPHA = 0.34f
private const val PENDING_MIN_ALPHA = 0.4f
private const val SKELETON_ROWS = 5

/**
 * Stateless Knotwork Trigger-detail surface — a trigger's identity header
 * (condition, bound pipeline, enable state, Edit / Delete) above its
 * **evaluation journal**: a per-day timeline of every fire, re-arm and typed skip
 * and the outcome of each fired run. Sits between the list and the editor
 * (list → row tap → **detail** → Edit → editor).
 *
 * @param state immutable view state — identity header + grouped journal.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings.
 * @param callbacks one-shot callback bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerDetailContent(
    state: TriggerDetailViewState,
    modifier: Modifier = Modifier,
    strings: TriggerDetailStrings = TriggerDetailStrings(),
    callbacks: TriggerDetailCallbacks = noopTriggerDetailCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                DetailTopBar(state = state, strings = strings, callbacks = callbacks)
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp16),
        ) {
            item(key = "header") { IdentityHeader(state = state, strings = strings, callbacks = callbacks) }
            item(key = "journal-header") { JournalSectionHeader(state = state, strings = strings) }
            journalSection(state = state, strings = strings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(
    state: TriggerDetailViewState,
    strings: TriggerDetailStrings,
    callbacks: TriggerDetailCallbacks,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = state.name,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.subtitle,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
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
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

// ── Identity header ─────────────────────────────────────────────────────────

@Composable
private fun IdentityHeader(
    state: TriggerDetailViewState,
    strings: TriggerDetailStrings,
    callbacks: TriggerDetailCallbacks,
) {
    Column(
        modifier = Modifier.padding(
            start = KnotworkTheme.spacing.sp4,
            end = KnotworkTheme.spacing.sp4,
            top = KnotworkTheme.spacing.sp3,
        ),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        if (state.showStaleBanner) StaleBanner(state = state, strings = strings)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(KnotworkTheme.shapes.lg)
                .background(KnotworkTheme.extended.surface1)
                .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.lg),
        ) {
            HeaderMetaRow(icon = state.conditionType.glyph(), label = strings.whenLabel, value = state.conditionLabel)
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            if (state.isBound) {
                HeaderMetaRow(
                    icon = AppIcons.Flow,
                    label = strings.runsLabel,
                    value = state.pipelineName.orEmpty(),
                    mono = true,
                )
            } else {
                // Not dimmed, and the verb is in the words. On the list screen
                // the verb lives on its own button; here the row itself is the
                // control, so the row has to say what tapping it does. Dim ink
                // was carrying that meaning alone, and dim is exactly what the
                // first external tester read as "unavailable".
                HeaderMetaRow(
                    icon = AppIcons.Link,
                    label = strings.runsLabel,
                    value = strings.unboundAction,
                    onClick = callbacks.onBindPipeline,
                )
            }
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            StateRow(state = state, strings = strings, callbacks = callbacks)
        }
        ActionsRow(strings = strings, callbacks = callbacks)
    }
}

@Composable
private fun HeaderMetaRow(
    icon: ImageVector,
    label: String,
    value: String,
    mono: Boolean = false,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    val rowModifier = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Row(
        modifier = rowModifier.padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (dim) KnotworkTheme.extended.onSurfaceDim else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(HeaderGlyphSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
            Text(
                text = value,
                style = if (mono) KnotworkTextStyles.MonoBase else KnotworkTextStyles.BodyBase,
                color = if (dim) KnotworkTheme.extended.onSurface2 else MaterialTheme.colorScheme.onSurface,
                fontStyle = if (dim) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StateRow(state: TriggerDetailViewState, strings: TriggerDetailStrings, callbacks: TriggerDetailCallbacks) {
    val on = state.enabled && state.isBound
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Trigger,
            contentDescription = null,
            tint = if (on) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurfaceDim,
            modifier = Modifier.size(HeaderGlyphSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.stateLabel,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            Text(
                text = if (state.enabled) strings.stateEnabled else strings.stateDisabled,
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = if (state.isBound) ({ callbacks.onToggleEnabled() }) else null,
            enabled = state.isBound,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun ActionsRow(strings: TriggerDetailStrings, callbacks: TriggerDetailCallbacks) {
    Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
        KnotworkSecondaryButton(
            text = strings.edit,
            onClick = callbacks.onEdit,
            size = KnotworkButtonSize.Md,
            leadingIcon = AppIcons.Edit,
            modifier = Modifier.weight(1f),
        )
        KnotworkSecondaryButton(
            text = strings.delete,
            onClick = callbacks.onDelete,
            size = KnotworkButtonSize.Md,
            destructive = true,
            leadingIcon = AppIcons.Trash,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StaleBanner(state: TriggerDetailViewState, strings: TriggerDetailStrings) {
    val warn = KnotworkTheme.extended.signalWarn
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(warn.copy(alpha = BANNER_FILL_ALPHA))
            .border(1.dp, warn.copy(alpha = BANNER_BORDER_ALPHA), KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = AppIcons.Warn,
            contentDescription = null,
            tint = warn,
            modifier = Modifier.size(HeaderGlyphSize),
        )
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            Text(
                text = state.staleSinceLabel?.let { strings.staleBannerTitleFormat.format(it) }
                    ?: strings.staleBannerTitleNoTime,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = strings.staleBannerBody,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
            )
        }
    }
}

// ── Journal section ─────────────────────────────────────────────────────────

@Composable
private fun JournalSectionHeader(state: TriggerDetailViewState, strings: TriggerDetailStrings) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp5,
                bottom = KnotworkTheme.spacing.sp1,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.journalSectionLabel,
            style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        if (state.journalState == TriggerJournalVisualState.Populated) {
            Text(
                text = strings.journalWindowLabel,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceDim,
            )
        }
    }
}

private fun LazyListScope.journalSection(state: TriggerDetailViewState, strings: TriggerDetailStrings) {
    when (state.journalState) {
        TriggerJournalVisualState.Loading -> item(key = "loading") { JournalLoading() }
        TriggerJournalVisualState.Empty -> item(key = "empty") { JournalEmpty(strings = strings) }
        TriggerJournalVisualState.Populated -> {
            state.dayGroups.forEach { group ->
                item(key = "day-${group.headerLabel}") { DayHeader(label = group.headerLabel) }
                group.entries.forEach { entry ->
                    item(key = "entry-${entry.id}") { JournalEntryRow(entry = entry, strings = strings) }
                }
            }
            item(key = "retention") { RetentionFooter(strings = strings) }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp3,
                bottom = KnotworkTheme.spacing.sp1,
            ),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = KnotworkTheme.extended.divider)
    }
}

@Composable
private fun JournalEntryRow(entry: TriggerJournalEntryUi, strings: TriggerDetailStrings) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = KnotworkTheme.spacing.sp4),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Box(
            modifier = Modifier.width(TimelineGutterWidth).padding(top = KnotworkTheme.spacing.sp3),
            contentAlignment = Alignment.TopCenter,
        ) {
            VerdictTile(verdict = entry.verdict)
        }
        Column(
            modifier = Modifier.weight(1f).padding(vertical = KnotworkTheme.spacing.sp3),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            EntryFirstLine(entry = entry, strings = strings)
            EntrySecondLine(entry = entry, strings = strings)
            entry.hitl?.let { HitlLine(hitl = it, parked = entry.hitlParked, strings = strings) }
        }
    }
}

@Composable
private fun VerdictTile(verdict: TriggerJournalVerdictUi) {
    val accent = verdict == TriggerJournalVerdictUi.Fired
    val background = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = TILE_ACCENT_ALPHA)
    } else {
        KnotworkTheme.extended.surface2
    }
    val tint = if (accent) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurface2
    val borderColor = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = TILE_BORDER_ALPHA)
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(TimelineTileSize)
            .clip(KnotworkTheme.shapes.md)
            .background(background)
            .border(1.dp, borderColor, KnotworkTheme.shapes.md),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = verdict.glyph(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TimelineGlyphSize),
        )
    }
}

@Composable
private fun EntryFirstLine(entry: TriggerJournalEntryUi, strings: TriggerDetailStrings) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = entry.verdict.label(strings),
            style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Icon(
                imageVector = entry.source.glyph(),
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(InlineGlyphSize),
            )
            Text(
                text = entry.source.label(strings),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = entry.timestampLabel,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceDim,
        )
    }
}

@Composable
private fun EntrySecondLine(entry: TriggerJournalEntryUi, strings: TriggerDetailStrings) {
    when (entry.verdict) {
        TriggerJournalVerdictUi.Fired ->
            entry.outcome?.let { OutcomeLine(outcome = it, error = entry.outcomeError, strings = strings) }
        TriggerJournalVerdictUi.Skipped -> Text(
            text = entry.skipSentence(strings),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
        TriggerJournalVerdictUi.ReArmed -> Text(
            text = strings.reArmNote,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

@Composable
private fun OutcomeLine(outcome: TriggerJournalOutcomeUi, error: String?, strings: TriggerDetailStrings) {
    val color = outcomeColor(outcome)
    val label = outcome.label(strings)
    val text = if (outcome == TriggerJournalOutcomeUi.Failure && !error.isNullOrBlank()) "$label · $error" else label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        if (outcome == TriggerJournalOutcomeUi.Pending) {
            PendingDotIndicator(color = color)
        } else {
            Icon(
                imageVector = outcome.glyph(),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(InlineGlyphSize),
            )
        }
        Text(text = text, style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/**
 * The human-in-the-loop line of a fired entry: whether the run stopped to ask,
 * and what came of it.
 *
 * Rendered under the outcome line rather than folded into it, because the two
 * answer different questions — the outcome is what became of the *run*, this is
 * what became of the *request to the user*. A run can complete successfully after
 * an approval that sat in the shade for an hour, and both halves matter.
 */
@Composable
private fun HitlLine(hitl: TriggerJournalHitlUi, parked: Boolean, strings: TriggerDetailStrings) {
    val color = hitlColor(hitl)
    val qualifier = when {
        !parked -> null
        hitl == TriggerJournalHitlUi.Waiting -> strings.hitlParkedPending
        else -> strings.hitlParkedSettled
    }
    val label = hitl.label(strings)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Icon(
            imageVector = hitl.glyph(),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(InlineGlyphSize),
        )
        Text(
            text = if (qualifier == null) label else "$label · $qualifier",
            style = KnotworkTextStyles.BodySm,
            color = color,
        )
    }
}

@Composable
private fun PendingDotIndicator(color: Color) {
    val pulseAlpha = if (KnotworkTheme.a11y.reducedMotion()) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "pending-pulse")
        val alpha by transition.animateFloat(
            initialValue = PENDING_MIN_ALPHA,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PENDING_PULSE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pending-pulse-alpha",
        )
        alpha
    }
    Box(
        modifier = Modifier
            .alpha(pulseAlpha)
            .size(PendingDotSize)
            .clip(KnotworkTheme.shapes.full)
            .background(color),
    )
}

@Composable
private fun RetentionFooter(strings: TriggerDetailStrings) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = KnotworkTheme.spacing.sp4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = strings.retentionFooter,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceDim,
        )
    }
}

@Composable
private fun JournalEmpty(strings: TriggerDetailStrings) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp8),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(title = strings.emptyTitle, subtitle = strings.emptyBody, illustration = { EmptyJournalTile() })
    }
}

@Composable
private fun EmptyJournalTile() {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(EmptyTileSize)
            .clip(KnotworkTheme.shapes.lg)
            .background(accent.copy(alpha = TILE_ACCENT_ALPHA))
            .border(1.dp, accent.copy(alpha = TILE_BORDER_ALPHA), KnotworkTheme.shapes.lg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.History,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(EmptyGlyphSize),
        )
    }
}

@Composable
private fun JournalLoading() {
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(SKELETON_ROWS) { JournalSkeletonRow() }
    }
}

@Composable
private fun JournalSkeletonRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Box(
            modifier = Modifier
                .size(TimelineTileSize)
                .clip(KnotworkTheme.shapes.md)
                .background(KnotworkTheme.extended.surface3),
        )
        Column(
            modifier = Modifier.weight(1f).padding(top = KnotworkTheme.spacing.sp1),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            SkeletonBar(fraction = 0.52f)
            SkeletonBar(fraction = 0.36f)
        }
    }
}

@Composable
private fun SkeletonBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(SkeletonBarHeight)
            .clip(KnotworkTheme.shapes.sm)
            .background(KnotworkTheme.extended.surface3),
    )
}

// ── Vocabulary → glyph / label mappers ──────────────────────────────────────
// `TriggerConditionType.glyph()` is shared from TriggersContent (same package).

private fun TriggerJournalVerdictUi.glyph(): ImageVector = when (this) {
    TriggerJournalVerdictUi.Fired -> AppIcons.Bolt
    TriggerJournalVerdictUi.ReArmed -> AppIcons.Refresh
    TriggerJournalVerdictUi.Skipped -> AppIcons.MinusCircle
}

private fun TriggerJournalVerdictUi.label(strings: TriggerDetailStrings): String = when (this) {
    TriggerJournalVerdictUi.Fired -> strings.verdictFired
    TriggerJournalVerdictUi.ReArmed -> strings.verdictReArmed
    TriggerJournalVerdictUi.Skipped -> strings.verdictSkipped
}

private fun TriggerJournalSourceUi.glyph(): ImageVector = when (this) {
    TriggerJournalSourceUi.Poll -> AppIcons.History
    TriggerJournalSourceUi.Event -> AppIcons.Spark
    TriggerJournalSourceUi.Charging -> AppIcons.Battery
}

private fun TriggerJournalSourceUi.label(strings: TriggerDetailStrings): String = when (this) {
    TriggerJournalSourceUi.Poll -> strings.sourcePoll
    TriggerJournalSourceUi.Event -> strings.sourceEvent
    TriggerJournalSourceUi.Charging -> strings.sourceCharging
}

private fun TriggerJournalOutcomeUi.glyph(): ImageVector = when (this) {
    TriggerJournalOutcomeUi.Pending -> AppIcons.Play
    TriggerJournalOutcomeUi.Success -> AppIcons.Check
    TriggerJournalOutcomeUi.Failure -> AppIcons.AlertCircle
    TriggerJournalOutcomeUi.CancelledBySystem -> AppIcons.Warn
    TriggerJournalOutcomeUi.Cancelled -> AppIcons.Stop
    TriggerJournalOutcomeUi.HitlTimeout -> AppIcons.Hourglass
    TriggerJournalOutcomeUi.StoppedByCeiling -> AppIcons.Shield
}

private fun TriggerJournalOutcomeUi.label(strings: TriggerDetailStrings): String = when (this) {
    TriggerJournalOutcomeUi.Pending -> strings.outcomePending
    TriggerJournalOutcomeUi.Success -> strings.outcomeSuccess
    TriggerJournalOutcomeUi.Failure -> strings.outcomeFailure
    TriggerJournalOutcomeUi.CancelledBySystem -> strings.outcomeCancelledBySystem
    TriggerJournalOutcomeUi.Cancelled -> strings.outcomeCancelled
    TriggerJournalOutcomeUi.HitlTimeout -> strings.outcomeHitlTimeout
    TriggerJournalOutcomeUi.StoppedByCeiling -> strings.outcomeStoppedByCeiling
}

@Composable
private fun outcomeColor(outcome: TriggerJournalOutcomeUi): Color = when (outcome) {
    TriggerJournalOutcomeUi.Pending -> MaterialTheme.colorScheme.primary
    TriggerJournalOutcomeUi.Success -> KnotworkTheme.extended.signalSuccess
    TriggerJournalOutcomeUi.Failure -> KnotworkTheme.extended.signalError
    TriggerJournalOutcomeUi.CancelledBySystem -> KnotworkTheme.extended.signalWarn
    TriggerJournalOutcomeUi.HitlTimeout -> KnotworkTheme.extended.signalWarn
    // A ceiling stop is a guard doing its job, so it reads as a notice rather
    // than as the error colour a genuine failure gets.
    TriggerJournalOutcomeUi.StoppedByCeiling -> KnotworkTheme.extended.signalWarn
    TriggerJournalOutcomeUi.Cancelled -> KnotworkTheme.extended.onSurface2
}

private fun TriggerJournalHitlUi.glyph(): ImageVector = when (this) {
    TriggerJournalHitlUi.Waiting -> AppIcons.Hourglass
    TriggerJournalHitlUi.Approved -> AppIcons.Shield
    TriggerJournalHitlUi.Denied -> AppIcons.Block
    TriggerJournalHitlUi.Answered -> AppIcons.Chat
    TriggerJournalHitlUi.TimedOut -> AppIcons.Hourglass
    TriggerJournalHitlUi.Abandoned -> AppIcons.MinusCircle
}

private fun TriggerJournalHitlUi.label(strings: TriggerDetailStrings): String = when (this) {
    TriggerJournalHitlUi.Waiting -> strings.hitlWaiting
    TriggerJournalHitlUi.Approved -> strings.hitlApproved
    TriggerJournalHitlUi.Denied -> strings.hitlDenied
    TriggerJournalHitlUi.Answered -> strings.hitlAnswered
    TriggerJournalHitlUi.TimedOut -> strings.hitlTimedOut
    TriggerJournalHitlUi.Abandoned -> strings.hitlAbandoned
}

@Composable
private fun hitlColor(hitl: TriggerJournalHitlUi): Color = when (hitl) {
    // A gate still open is the only one asking for action; the settled ones are
    // context for the outcome above, so they stay muted rather than competing
    // with it. Timing out is the one failure mode of the request itself.
    TriggerJournalHitlUi.Waiting -> MaterialTheme.colorScheme.primary
    TriggerJournalHitlUi.TimedOut -> KnotworkTheme.extended.signalWarn
    else -> KnotworkTheme.extended.onSurface2
}

private fun TriggerJournalEntryUi.skipSentence(strings: TriggerDetailStrings): String = when (skipReason) {
    TriggerJournalSkipReasonUi.Disabled -> strings.skipDisabled
    TriggerJournalSkipReasonUi.Unbound -> strings.skipUnbound
    TriggerJournalSkipReasonUi.ConditionNotMet -> strings.skipConditionNotMetFormat.format(skipMomentLabel.orEmpty())
    TriggerJournalSkipReasonUi.AlreadyFired -> strings.skipAlreadyFired
    null -> ""
}
