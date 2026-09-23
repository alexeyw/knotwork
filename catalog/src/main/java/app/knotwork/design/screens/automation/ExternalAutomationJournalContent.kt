@file:Suppress("MatchingDeclarationName", "TooManyFunctions") // Hosts the journal screen + its private helpers.

package app.knotwork.design.screens.automation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.components.topbar.JournalExportActions
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of a timeline status tile. */
private val TimelineTileSize = 36.dp

/** Inner glyph size on a timeline tile. */
private val TimelineGlyphSize = 19.dp

/** Width of the timeline gutter column. */
private val TimelineGutterWidth = 36.dp

/** Small inline glyph size on detail lines. */
private val InlineGlyphSize = 13.dp

/** Banner leading glyph size. */
private val BannerGlyphSize = 18.dp

/** Diameter of the pending "Running…" dot. */
private val PendingDotSize = 8.dp

/** Empty-state illustration tile. */
private val EmptyTileSize = 64.dp

/** Inner glyph on the empty-state tile. */
private val EmptyGlyphSize = 30.dp

/** Skeleton bar height on a loading row. */
private val SkeletonBarHeight = 12.dp

/** Pending-dot pulse period, matching the trigger journal. */
private const val PENDING_PULSE_MS = 1400

/** Rotation applied to the disclosure chevron when the call block is open. */
private const val CHEVRON_EXPANDED_ROTATION = 180f

private const val TILE_ACCENT_ALPHA = 0.12f
private const val TILE_BORDER_ALPHA = 0.24f
private const val BANNER_FILL_ALPHA = 0.12f
private const val BANNER_BORDER_ALPHA = 0.34f
private const val PENDING_MIN_ALPHA = 0.4f
private const val SKELETON_ROWS = 5

/**
 * Stateless Knotwork external-automation request journal — the contract's current
 * posture (off / on-but-unbound / accepting) above a per-day timeline of every
 * request another app has sent, admitted or refused.
 *
 * The screen is history plus posture, never configuration: the switch and the
 * pipeline binding live one level up, on the Background settings category, so the
 * app's most security-sensitive toggle stays a settings row that search can find
 * and deep-link into.
 *
 * @param state immutable view state — posture, wire contract, grouped journal.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings.
 * @param callbacks one-shot callback bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalAutomationJournalContent(
    state: ExternalAutomationJournalViewState,
    modifier: Modifier = Modifier,
    strings: ExternalAutomationJournalStrings = ExternalAutomationJournalStrings(),
    callbacks: ExternalAutomationJournalCallbacks = noopExternalAutomationJournalCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                JournalTopBar(strings = strings, callbacks = callbacks)
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp16),
        ) {
            item(key = "posture") { PostureBanner(state = state, strings = strings) }
            if (state.callKeys.isNotEmpty()) {
                item(key = "call-block") { CallBlock(state = state, strings = strings, callbacks = callbacks) }
            }
            item(key = "section-header") { SectionHeader(strings = strings) }
            journalSection(state = state, strings = strings)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalTopBar(strings: ExternalAutomationJournalStrings, callbacks: ExternalAutomationJournalCallbacks) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = strings.title,
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
        actions = {
            // The same pair, in the same place, as the trigger journal: one kind
            // of artefact, so one affordance for it.
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

// ── Posture banner ──────────────────────────────────────────────────────────

/**
 * States what the contract is currently doing, in the reader's own terms.
 *
 * On the screen rather than only on the settings row because a journal full of
 * refusals is unreadable without it: "refused because the whole thing is off" and
 * "refused because your profile names the wrong pipeline" produce the same red
 * rows and need completely different fixes.
 */
@Composable
private fun PostureBanner(state: ExternalAutomationJournalViewState, strings: ExternalAutomationJournalStrings) {
    val (accent, glyph) = when {
        !state.contractEnabled -> KnotworkTheme.extended.onSurfaceMuted to AppIcons.Block
        !state.isBound -> KnotworkTheme.extended.signalWarn to AppIcons.Warn
        else -> KnotworkTheme.extended.signalSuccess to AppIcons.Shield
    }
    val title = when {
        !state.contractEnabled -> strings.bannerOffTitle
        !state.isBound -> strings.bannerUnboundTitle
        else -> strings.bannerBoundTitle
    }
    val body = when {
        !state.contractEnabled -> strings.bannerOffBody
        !state.isBound -> strings.bannerUnboundBody
        else -> strings.bannerBoundBodyFormat.format(state.boundPipelineName.orEmpty())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .clip(KnotworkTheme.shapes.md)
            .background(accent.copy(alpha = BANNER_FILL_ALPHA))
            .border(1.dp, accent.copy(alpha = BANNER_BORDER_ALPHA), KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(BannerGlyphSize),
        )
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            Text(
                text = title,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = body, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
        }
    }
}

// ── "How another app calls this" ────────────────────────────────────────────

/**
 * The wire contract, in the one place a user who has just switched the surface on
 * is guaranteed to be standing. Collapsed by default so it never competes with
 * the journal; the values come from the contract source of truth, so the block
 * cannot describe a call the app does not actually answer.
 */
@Composable
private fun CallBlock(
    state: ExternalAutomationJournalViewState,
    strings: ExternalAutomationJournalStrings,
    callbacks: ExternalAutomationJournalCallbacks,
) {
    var expanded by remember { mutableStateOf(state.callBlockInitiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_EXPANDED_ROTATION else 0f,
        label = "call-block-chevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4)
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(1.dp, KnotworkTheme.extended.divider, KnotworkTheme.shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { contentDescription = strings.callBlockExpandCd }
                .padding(KnotworkTheme.spacing.sp3),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = AppIcons.Terminal,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(BannerGlyphSize),
            )
            Text(
                text = strings.callBlockTitle,
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = AppIcons.ArrowDown,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        if (expanded) CallBlockBody(state = state, strings = strings, callbacks = callbacks)
    }
}

@Composable
private fun CallBlockBody(
    state: ExternalAutomationJournalViewState,
    strings: ExternalAutomationJournalStrings,
    callbacks: ExternalAutomationJournalCallbacks,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = KnotworkTheme.spacing.sp3,
            end = KnotworkTheme.spacing.sp3,
            bottom = KnotworkTheme.spacing.sp3,
        ),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Text(text = strings.callBlockBody, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
        CallBlockLabel(text = strings.callBlockActionLabel)
        Text(
            text = state.callAction,
            style = KnotworkTextStyles.MonoSm,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CallBlockLabel(text = strings.callBlockKeysLabel)
        state.callKeys.forEach { key -> CallKeyRow(key = key) }
        KnotworkSecondaryButton(
            text = strings.callBlockCopy,
            onClick = callbacks.onCopyCallDetails,
            size = KnotworkButtonSize.Sm,
            leadingIcon = AppIcons.Copy,
        )
    }
}

@Composable
private fun CallBlockLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

@Composable
private fun CallKeyRow(key: ExternalCallKeyUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key.key,
            style = KnotworkTextStyles.MonoSm,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            Text(text = key.meaning, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
        }
    }
}

// ── Journal section ─────────────────────────────────────────────────────────

/**
 * Names the timeline below it.
 *
 * Carries no retention window of its own: the footer at the end of the list
 * already states it, and a second copy opposite the section label had nowhere to
 * go at the "Largest" text preset except on top of the label.
 */
@Composable
private fun SectionHeader(strings: ExternalAutomationJournalStrings) {
    Text(
        text = strings.sectionLabel,
        style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp5,
                bottom = KnotworkTheme.spacing.sp1,
            ),
    )
}

private fun LazyListScope.journalSection(
    state: ExternalAutomationJournalViewState,
    strings: ExternalAutomationJournalStrings,
) {
    when (state.journalState) {
        ExternalJournalVisualState.Loading -> item(key = "loading") { JournalLoading() }
        ExternalJournalVisualState.Empty -> item(key = "empty") { JournalEmpty(strings = strings) }
        ExternalJournalVisualState.Populated -> {
            state.dayGroups.forEach { group ->
                item(key = "day-${group.headerLabel}") { DayHeader(label = group.headerLabel) }
                group.entries.forEach { entry ->
                    item(key = "entry-${entry.id}") { RequestRow(entry = entry, strings = strings) }
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
private fun RequestRow(entry: ExternalRequestEntryUi, strings: ExternalAutomationJournalStrings) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = KnotworkTheme.spacing.sp4),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Box(
            modifier = Modifier.width(TimelineGutterWidth).padding(top = KnotworkTheme.spacing.sp3),
            contentAlignment = Alignment.TopCenter,
        ) {
            StatusTile(status = entry.status)
        }
        Column(
            modifier = Modifier.weight(1f).padding(vertical = KnotworkTheme.spacing.sp3),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            RowFirstLine(entry = entry, strings = strings)
            RowSecondLine(entry = entry, strings = strings)
            RowProvenanceLine(entry = entry, strings = strings)
        }
    }
}

@Composable
private fun StatusTile(status: ExternalRequestStatusUi) {
    val accent = status == ExternalRequestStatusUi.Accepted || status == ExternalRequestStatusUi.Completed
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
            imageVector = status.glyph(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TimelineGlyphSize),
        )
    }
}

/**
 * Status word · what was asked for · repeat badge · moment.
 *
 * A [FlowRow] rather than a [Row], because at the "Largest" text preset these
 * four items cannot share a line: laid out in a fixed row, the target — the one
 * field that says *what was asked for*, and the only variable-length one — gets
 * squeezed to two characters while the fixed-width status word and timestamp keep
 * their space. Wrapping puts it on its own line instead of deleting it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowFirstLine(entry: ExternalRequestEntryUi, strings: ExternalAutomationJournalStrings) {
    FlowRow(
        verticalArrangement = Arrangement.Center,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.status.label(strings),
            style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        entry.targetLabel?.let { target ->
            Text(
                text = "${strings.targetPrefix} $target",
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.repeatCount > 1) RepeatBadge(count = entry.repeatCount, strings = strings)
        Text(
            text = entry.timestampLabel,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceDim,
            maxLines = 1,
        )
    }
}

/**
 * How many identical consecutive refusals this row stands for.
 *
 * A misconfigured profile retries on its own cadence, so the realistic shape of a
 * broken caller is one problem repeated hundreds of times. Collapsing it onto one
 * row with a count is what keeps that readable as a single recurring fault rather
 * than a wall of separate incidents — and the badge carries a spoken word, not
 * just the multiplication sign, so a screen reader announces what the number is.
 */
@Composable
private fun RepeatBadge(count: Int, strings: ExternalAutomationJournalStrings) {
    // Merged, and spelling the number out: read as a bare node, "×43" is
    // announced as punctuation plus a number and the fact that it is a repeat
    // count is exactly the part that goes missing.
    val cd = strings.repeatCdFormat.format(count)
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .background(KnotworkTheme.extended.surface3)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1)
            .semantics(mergeDescendants = true) { contentDescription = cd },
    ) {
        Text(
            text = strings.repeatFormat.format(count),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

/** The outcome sentence for an admitted request, or the refusal reason for a refused one. */
@Composable
private fun RowSecondLine(entry: ExternalRequestEntryUi, strings: ExternalAutomationJournalStrings) {
    when (entry.status) {
        ExternalRequestStatusUi.Accepted,
        ExternalRequestStatusUi.Completed,
        ExternalRequestStatusUi.Failed,
        -> OutcomeLine(status = entry.status, strings = strings)

        ExternalRequestStatusUi.Rejected,
        ExternalRequestStatusUi.Blocked,
        -> RefusalLines(entry = entry, strings = strings)
    }
}

@Composable
private fun OutcomeLine(status: ExternalRequestStatusUi, strings: ExternalAutomationJournalStrings) {
    val color = status.color()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        if (status == ExternalRequestStatusUi.Accepted) {
            PendingDotIndicator(color = color)
        } else {
            Icon(
                imageVector = status.outcomeGlyph(),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(InlineGlyphSize),
            )
        }
        Text(
            text = status.outcomeSentence(strings),
            style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

/**
 * The refusal reason plus the one sentence that separates the two refusal kinds.
 *
 * The hint is not decoration: `Refused` and `Held back` demand opposite responses
 * — fix the profile versus wait and call less often — and a reader who only sees
 * a red row and a reason has no way to tell which one they are looking at.
 */
@Composable
private fun RefusalLines(entry: ExternalRequestEntryUi, strings: ExternalAutomationJournalStrings) {
    val color = entry.status.color()
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Icon(
                imageVector = entry.status.outcomeGlyph(),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(InlineGlyphSize).padding(top = KnotworkTheme.spacing.sp1),
            )
            Text(
                text = entry.reason.sentence(strings),
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = color,
            )
        }
        if (entry.reason == ExternalRequestReasonUi.ReturnPackageMismatch) {
            Text(
                text = strings.reasonReturnPackageMismatchNote,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
            )
        }
        Text(
            text = if (entry.status == ExternalRequestStatusUi.Blocked) strings.blockedHint else strings.rejectedHint,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceDim,
        )
    }
}

/**
 * Who asked, on whose word, plus the correlation id and any unrecognised action.
 *
 * The sender is never promoted into the row's identity line: in the ordinary case
 * the app has nothing but the caller's own claim about where to send the answer,
 * and the wording says so on every such row rather than letting the reader assume
 * the name was verified.
 */
@Composable
private fun RowProvenanceLine(entry: ExternalRequestEntryUi, strings: ExternalAutomationJournalStrings) {
    val senderLine = entry.senderLabel?.let { sender ->
        val format = when (entry.senderKind) {
            ExternalRequestSenderKindUi.Attested -> strings.senderAttestedFormat
            ExternalRequestSenderKindUi.Claimed -> strings.senderClaimedFormat
        }
        format.format(sender)
    }
    // The correlation id gets its own line rather than sharing the sender's.
    // Joined, a package name long enough to wrap pushes the id past the ellipsis
    // on every row — and the id is the only field that ties a row back to the
    // caller's own log, which is what a reader came here to do.
    val metaLine = listOfNotNull(
        strings.actionPrefixFormat.format(entry.actionLabel)
            .takeIf { entry.showAction && entry.actionLabel.isNotBlank() },
        entry.requestIdLabel?.let { strings.requestIdFormat.format(it) },
    ).joinToString(separator = " · ")

    senderLine?.let { MetaText(text = it, maxLines = 2) }
    if (metaLine.isNotEmpty()) MetaText(text = metaLine, maxLines = 1)
}

/** One dim monospaced provenance line under a request row. */
@Composable
private fun MetaText(text: String, maxLines: Int) {
    Text(
        text = text,
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.onSurfaceDim,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
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
private fun RetentionFooter(strings: ExternalAutomationJournalStrings) {
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
private fun JournalEmpty(strings: ExternalAutomationJournalStrings) {
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
            imageVector = AppIcons.External,
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
            SkeletonBar(fraction = 0.58f)
            SkeletonBar(fraction = 0.34f)
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

// ── Vocabulary → glyph / label / colour mappers ─────────────────────────────

private fun ExternalRequestStatusUi.glyph(): ImageVector = when (this) {
    ExternalRequestStatusUi.Accepted -> AppIcons.Play
    ExternalRequestStatusUi.Completed -> AppIcons.Check
    ExternalRequestStatusUi.Failed -> AppIcons.AlertCircle
    ExternalRequestStatusUi.Rejected -> AppIcons.Block
    ExternalRequestStatusUi.Blocked -> AppIcons.Hourglass
}

private fun ExternalRequestStatusUi.outcomeGlyph(): ImageVector = when (this) {
    ExternalRequestStatusUi.Accepted -> AppIcons.Play
    ExternalRequestStatusUi.Completed -> AppIcons.Check
    ExternalRequestStatusUi.Failed -> AppIcons.AlertCircle
    ExternalRequestStatusUi.Rejected -> AppIcons.X
    ExternalRequestStatusUi.Blocked -> AppIcons.Hourglass
}

private fun ExternalRequestStatusUi.label(strings: ExternalAutomationJournalStrings): String = when (this) {
    ExternalRequestStatusUi.Accepted -> strings.statusAccepted
    ExternalRequestStatusUi.Completed -> strings.statusCompleted
    ExternalRequestStatusUi.Failed -> strings.statusFailed
    ExternalRequestStatusUi.Rejected -> strings.statusRejected
    ExternalRequestStatusUi.Blocked -> strings.statusBlocked
}

private fun ExternalRequestStatusUi.outcomeSentence(strings: ExternalAutomationJournalStrings): String = when (this) {
    ExternalRequestStatusUi.Accepted -> strings.outcomeRunning
    ExternalRequestStatusUi.Completed -> strings.outcomeCompleted
    ExternalRequestStatusUi.Failed -> strings.outcomeFailed
    // Never rendered: a refusal takes the reason line instead of an outcome one.
    ExternalRequestStatusUi.Rejected -> strings.statusRejected
    ExternalRequestStatusUi.Blocked -> strings.statusBlocked
}

@Composable
private fun ExternalRequestStatusUi.color(): Color = when (this) {
    ExternalRequestStatusUi.Accepted -> MaterialTheme.colorScheme.primary
    ExternalRequestStatusUi.Completed -> KnotworkTheme.extended.signalSuccess
    ExternalRequestStatusUi.Failed -> KnotworkTheme.extended.signalError
    // A refusal is a decision the app made on purpose, not a fault it is
    // apologising for, so it reads as a warning rather than an error.
    ExternalRequestStatusUi.Rejected -> KnotworkTheme.extended.signalWarn
    ExternalRequestStatusUi.Blocked -> KnotworkTheme.extended.signalWarn
}

private fun ExternalRequestReasonUi?.sentence(strings: ExternalAutomationJournalStrings): String = when (this) {
    ExternalRequestReasonUi.ContractDisabled -> strings.reasonContractDisabled
    ExternalRequestReasonUi.SurfaceNotBound -> strings.reasonSurfaceNotBound
    ExternalRequestReasonUi.TargetNotAllowed -> strings.reasonTargetNotAllowed
    ExternalRequestReasonUi.TargetMissing -> strings.reasonTargetMissing
    ExternalRequestReasonUi.TargetAmbiguous -> strings.reasonTargetAmbiguous
    ExternalRequestReasonUi.UnknownAction -> strings.reasonUnknownAction
    ExternalRequestReasonUi.PromptMissing -> strings.reasonPromptMissing
    ExternalRequestReasonUi.PromptAmbiguous -> strings.reasonPromptAmbiguous
    ExternalRequestReasonUi.PromptUndecodable -> strings.reasonPromptUndecodable
    ExternalRequestReasonUi.RequestIdMissing -> strings.reasonRequestIdMissing
    ExternalRequestReasonUi.RateLimited -> strings.reasonRateLimited
    ExternalRequestReasonUi.ReturnPackageMismatch -> strings.reasonReturnPackageMismatch
    // A refusal row always carries a reason; an absent one would be a mapper bug,
    // and an empty line is a quieter failure than an invented sentence.
    null -> ""
}
