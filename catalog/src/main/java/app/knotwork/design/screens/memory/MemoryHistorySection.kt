package app.knotwork.design.screens.memory

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme

/**
 * The line under a paired row's body: *Update waiting* on the pinned half (accent dot,
 * primary) or *Updates a pinned memory* on the update (filled pin, muted).
 *
 * @param role Which half the row is.
 */
@Composable
internal fun MemoryPairLine(role: MemoryPairRole) {
    val pinnedHalf = role == MemoryPairRole.PinnedWithUpdate
    val tint = if (pinnedHalf) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurfaceMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Icon(
            imageVector = if (pinnedHalf) AppIcons.Dot else AppIcons.PinOn,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (pinnedHalf) 12.dp else 13.dp),
        )
        Text(
            text = stringResource(
                if (pinnedHalf) R.string.knotwork_memory_row_pair_pinned else R.string.knotwork_memory_row_pair_update,
            ),
            style = MemoryType.control,
            color = tint,
        )
    }
}

/**
 * The row footer's history marker: the `history` glyph and how many earlier versions
 * the entry keeps. Not a target; read as one phrase by TalkBack.
 *
 * @param count Number of earlier versions (the caller hides the marker at `0`).
 */
@Composable
internal fun MemoryHistoryMarker(count: Int) {
    val description = pluralStringResource(R.plurals.knotwork_memory_a11y_row_history, count, count)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(
            imageVector = AppIcons.History,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(14.dp),
        )
        Text(text = count.toString(), style = MemoryType.timestamp, color = KnotworkTheme.extended.onSurfaceMuted)
    }
}

/**
 * Draws an update row inset under its pinned entry, tied to it by an elbow connector.
 *
 * @param content The update's row.
 */
@Composable
internal fun MemoryPairChildRow(content: @Composable () -> Unit) {
    val connector = KnotworkTheme.extended.outlineStrong
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(PAIR_INSET)
                .fillMaxHeight()
                .drawBehind {
                    val x = PAIR_CONNECTOR_X.toPx()
                    val elbowY = PAIR_CONNECTOR_DEPTH.toPx()
                    val radius = PAIR_CONNECTOR_RADIUS.toPx()
                    val path = Path().apply {
                        moveTo(x, 0f)
                        lineTo(x, elbowY - radius)
                        quadraticTo(x, elbowY, x + radius, elbowY)
                        lineTo(size.width - 1.dp.toPx(), elbowY)
                    }
                    drawPath(path = path, color = connector, style = Stroke(width = 1.5.dp.toPx()))
                },
        )
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * The pair block on a paired entry's sheet: what the other half says, where it came
 * from, the recall rule, and the two ways to resolve the pair.
 *
 * @param entryId Id of the entry whose sheet this is (either half).
 * @param pair The other half.
 * @param callbacks Raised with [entryId]: *Use the update* / *Keep pinned*.
 */
@Composable
internal fun MemoryPairBlock(entryId: String, pair: MemoryPairView, callbacks: MemoryCallbacks) {
    val pinnedHalf = pair.role == MemoryPairRole.PinnedWithUpdate
    val otherDescription = stringResource(
        if (pinnedHalf) {
            R.string.knotwork_memory_a11y_pair_update_text
        } else {
            R.string.knotwork_memory_a11y_pair_pinned_text
        },
        pair.otherText,
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface2)
            .border(1.dp, KnotworkTheme.extended.divider, KnotworkTheme.shapes.md)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { heading() },
        ) {
            Icon(
                imageVector = if (pinnedHalf) AppIcons.Dot else AppIcons.PinOn,
                contentDescription = null,
                tint = if (pinnedHalf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(if (pinnedHalf) 12.dp else 14.dp),
            )
            Text(
                text = stringResource(
                    if (pinnedHalf) {
                        R.string.knotwork_memory_pair_pinned_title
                    } else {
                        R.string.knotwork_memory_pair_update_title
                    },
                ),
                style = MemoryType.cardTitle,
                color = if (pinnedHalf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                text = pair.otherText,
                style = MemoryType.detailBody.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { contentDescription = otherDescription },
            )
            Text(
                text = sourceLine(pair.otherSourceKind, pair.otherLearnedFrom) + "\n" +
                    stringResource(R.string.knotwork_memory_hist_captured, pair.otherCapturedLabel).unbroken(),
                style = MemoryType.timestamp,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = stringResource(R.string.knotwork_memory_pair_note),
            style = MemoryType.cardBody,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_memory_pair_use),
                onClick = { callbacks.onPairUse(entryId) },
            )
            KnotworkTextButton(
                text = stringResource(R.string.knotwork_memory_pair_keep),
                onClick = { callbacks.onPairKeep(entryId) },
            )
        }
    }
}

/**
 * The *Earlier versions* section of a sheet: a section title and a collapsible block
 * whose header counts the versions. Opens collapsed every time; each version has an
 * overflow menu with *Delete this version*, confirmed by a dialog.
 *
 * @param entryId Id of the entry; keys the collapsed state to it.
 * @param history The versions, the most recently replaced first (non-empty).
 * @param callbacks Raises [MemoryCallbacks.onVersionDelete] after confirmation.
 */
@Composable
internal fun MemoryHistorySection(entryId: String, history: List<MemoryVersionView>, callbacks: MemoryCallbacks) {
    var open by remember(entryId) { mutableStateOf(false) }
    var pendingDelete by remember(entryId) { mutableStateOf<MemoryVersionView?>(null) }
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val chevron by animateFloatAsState(
        targetValue = if (open) HALF_TURN else 0f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(
                KnotworkTheme.motion.dur3,
                easing = KnotworkTheme.motion.easeStd,
            )
        },
        label = "history-chevron",
    )
    Text(
        text = stringResource(R.string.knotwork_memory_hist_title).uppercase(),
        style = MemoryType.groupHeader,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = Modifier.padding(top = 6.dp).semantics { heading() },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface2)
            .border(1.dp, KnotworkTheme.extended.divider, KnotworkTheme.shapes.md)
            .animateContentSize(
                animationSpec = if (reducedMotion) {
                    snap()
                } else {
                    tween(
                        KnotworkTheme.motion.dur3,
                        easing = KnotworkTheme.motion.easeStd,
                    )
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    onClickLabel = stringResource(
                        if (open) R.string.knotwork_memory_a11y_hist_hide else R.string.knotwork_memory_a11y_hist_show,
                    ),
                    onClick = { open = !open },
                )
                .padding(start = 14.dp, end = 14.dp),
        ) {
            Icon(
                imageVector = AppIcons.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.knotwork_memory_hist_count, history.size, history.size),
                style = MemoryType.detailBody,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = AppIcons.ArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp).rotate(chevron),
            )
        }
        if (open) {
            history.forEachIndexed { index, version ->
                HorizontalDivider(color = KnotworkTheme.extended.divider)
                MemoryVersionItem(
                    version = version,
                    position = index + 1,
                    total = history.size,
                    onDeleteRequest = { pendingDelete = version },
                )
            }
        }
    }
    pendingDelete?.let { version ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.knotwork_memory_dlg_delete_version_title)) },
            text = { Text(stringResource(R.string.knotwork_memory_dlg_delete_version_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        callbacks.onVersionDelete(version.id)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.knotwork_memory_detail_delete),
                        color = KnotworkTheme.extended.riskDestructive,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.knotwork_memory_detail_cancel))
                }
            },
        )
    }
}

/**
 * One earlier version: its text (six lines at most until tapped — a version can be a
 * whole message saved by hand), where it came from and when it was captured and
 * replaced, and an overflow menu with *Delete this version*.
 *
 * @param version The version.
 * @param position Its 1-based position in the history, for TalkBack.
 * @param total How many versions the history holds, for TalkBack.
 * @param onDeleteRequest The user picked *Delete this version* (not yet confirmed).
 */
@Composable
private fun MemoryVersionItem(version: MemoryVersionView, position: Int, total: Int, onDeleteRequest: () -> Unit) {
    var expanded by remember(version.id) { mutableStateOf(false) }
    var clamped by remember(version.id) { mutableStateOf(false) }
    var menuOpen by remember(version.id) { mutableStateOf(false) }
    val sourceLine = sourceLine(version.sourceKind, version.learnedFrom)
    val datesLine = stringResource(R.string.knotwork_memory_hist_captured, version.capturedLabel).unbroken() + " · " +
        stringResource(R.string.knotwork_memory_hist_replaced, version.replacedLabel).unbroken()
    val description = stringResource(
        R.string.knotwork_memory_a11y_version,
        position,
        total,
        version.text,
        "$sourceLine. $datesLine",
    )
    val deleteLabel = stringResource(R.string.knotwork_memory_hist_delete_version)
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clearAndSetSemantics {
                    contentDescription = description
                    customActions = listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            onDeleteRequest()
                            true
                        },
                    )
                }
                .clickable(enabled = clamped || expanded) { expanded = !expanded }
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = version.text,
                style = MemoryType.detailBody.copy(textDirection = TextDirection.Content),
                color = KnotworkTheme.extended.onSurface2,
                maxLines = if (expanded) Int.MAX_VALUE else VERSION_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout -> if (!expanded) clamped = layout.hasVisualOverflow },
            )
            Text(
                text = "$sourceLine\n$datesLine",
                style = MemoryType.timestamp,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = AppIcons.More,
                    contentDescription = stringResource(R.string.knotwork_memory_hist_version_more),
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    leadingIcon = { Icon(AppIcons.Trash, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        onDeleteRequest()
                    },
                )
            }
        }
    }
}

/**
 * The one line an edit-mode sheet adds when the entry keeps history: an edit replaces
 * the text without adding an earlier version.
 */
@Composable
internal fun MemoryEditHistoryNote() {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = AppIcons.Info,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = stringResource(R.string.knotwork_memory_edit_history_note),
            style = MemoryType.cardBody,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

/** Source label of a version or pair half, with the chat it was learned from when known. */
@Composable
private fun sourceLine(kind: MemorySourceKind, learnedFrom: String?): String {
    val source = stringResource(
        when (kind) {
            MemorySourceKind.Auto -> R.string.knotwork_memory_hist_src_auto
            MemorySourceKind.Manual -> R.string.knotwork_memory_hist_src_manual
            MemorySourceKind.Compaction -> R.string.knotwork_memory_hist_src_compact
            MemorySourceKind.Unknown -> R.string.knotwork_memory_hist_src_unknown
        },
    )
    return if (learnedFrom == null) {
        source
    } else {
        source + " · " + stringResource(R.string.knotwork_memory_hist_learned_from, learnedFrom)
    }
}

/** A dated phrase kept on one line: its spaces become no-break spaces, so a line breaks between phrases only. */
private fun String.unbroken(): String = replace(' ', '\u00A0')

/** Horizontal inset of an update row under its pinned entry. */
private val PAIR_INSET = 20.dp

/** Where the connector's vertical stroke runs inside the inset. */
private val PAIR_CONNECTOR_X = 10.dp

/** How far down the update row the connector turns. */
private val PAIR_CONNECTOR_DEPTH = 26.dp

/** Radius of the connector's elbow. */
private val PAIR_CONNECTOR_RADIUS = 6.dp

/** Lines a version shows before it is tapped open. */
private const val VERSION_MAX_LINES = 6

/** The chevron's rotation when the history is open. */
private const val HALF_TURN = 180f
