package app.knotwork.design.components.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Indent per sub-pipeline level of a verdict row. */
private val VerdictIndent = 18.dp

/** Width of a verdict row's glyph column. */
private val VerdictGlyphColumn = 20.dp

/** Size of a verdict glyph. */
private val VerdictGlyph = 18.dp

/** Size of the summary glyph. */
private val SummaryGlyph = 22.dp

/** Height of the determinate progress bar. */
private val ProgressHeight = 4.dp

/** Alpha of the progress track under the primary colour. */
private const val PROGRESS_TRACK_ALPHA = 0.18f

/** Alpha of the warn tint behind the first diverged row. */
private const val DIVERGED_TINT_ALPHA = 0.12f

/** Alpha of the leading dot of the row being repeated. */
private const val CHECKING_DOT_LEAD_ALPHA = 1f

/** Alpha of the middle dot. */
private const val CHECKING_DOT_MIDDLE_ALPHA = 0.55f

/** Alpha of the trailing dot. */
private const val CHECKING_DOT_TRAIL_ALPHA = 0.25f

/** The three dots' alphas, leading to trailing. */
private val CHECKING_DOT_ALPHAS = listOf(CHECKING_DOT_LEAD_ALPHA, CHECKING_DOT_MIDDLE_ALPHA, CHECKING_DOT_TRAIL_ALPHA)

/** Alpha of all three dots under reduced motion: static, and still read as one glyph. */
private const val CHECKING_DOT_STATIC_ALPHA = 0.9f

/** Alpha of the warn outline around the first diverged row. */
private const val DIVERGED_OUTLINE_ALPHA = 0.6f

/**
 * The check of a finished run, in its own sheet over the console, on the app's
 * surface (it follows the theme; the console behind it stays dark).
 *
 * The head names the seed, backend and model. While calls are repeated, a
 * determinate bar counts them; at the end a summary card says what was found and
 * is the live region TalkBack reads once. Below, every node visit of the run in
 * run order with its depth: rows fill in place, nothing reorders, and only the
 * first diverged visit is tinted — in warn, not error, because the check worked
 * and found that the answers differ. A result that claims a match ends with the
 * not-signed sentence; a cancelled or failed check offers to verify again.
 *
 * @param ui What to show.
 * @param onCancel Stops the check.
 * @param onVerifyAgain Starts it again.
 * @param onCopyHash Copies a recorded or replayed hash of a diverged visit.
 * @param onDismiss Closes the sheet; a running check is cancelled by the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationSheet(
    ui: VerificationUi,
    onCancel: () -> Unit,
    onVerifyAgain: () -> Unit,
    onCopyHash: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        VerificationSheetContent(
            ui = ui,
            onCancel = onCancel,
            onVerifyAgain = onVerifyAgain,
            onCopyHash = onCopyHash,
            onClose = onDismiss,
        )
    }
}

/**
 * The sheet's body, apart from the sheet itself — what the snapshots capture.
 *
 * @param ui What to show.
 * @param onCancel Stops the check.
 * @param onVerifyAgain Starts it again.
 * @param onCopyHash Copies a hash of a diverged visit.
 * @param onClose Closes the sheet.
 * @param modifier Optional layout modifier.
 */
@Composable
fun VerificationSheetContent(
    ui: VerificationUi,
    onCancel: () -> Unit,
    onVerifyAgain: () -> Unit,
    onCopyHash: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = ui.stage
    val listState = rememberLazyListState()
    val checking = ui.rows.indexOfFirst { it.verdict is VerdictUi.Checking }
    // Keeps the row being repeated in view; a user who scrolls takes over.
    LaunchedEffect(checking) {
        if (checking >= 0 && !listState.isScrollInProgress) listState.scrollToItem(checking)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHead(
            title = stringResource(R.string.knotwork_run_verify_title),
            sub = stringResource(R.string.knotwork_run_verify_sub, ui.seed, ui.backend, ui.model),
            onClose = onClose,
        )
        Box(modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2)) {
            if (stage is VerificationStageUi.Running) {
                Progress(stage = stage, onCancel = onCancel)
            } else {
                Summary(stage = stage)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(KnotworkTheme.extended.divider))
        val firstDiverged = ui.rows.indexOfFirst { it.verdict is VerdictUi.Diverged }
        LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
            itemsIndexed(ui.rows) { index, row ->
                VerdictRow(row = row, highlighted = index == firstDiverged, onCopyHash = onCopyHash)
            }
        }
        Footer(stage = stage, onVerifyAgain = onVerifyAgain)
    }
}

/** Title, the run's seed · backend · model, and the close button. */
@Composable
internal fun SheetHead(title: String, sub: String?, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KnotworkTheme.spacing.sp5, end = KnotworkTheme.spacing.sp1),
    ) {
        Column(modifier = Modifier.weight(1f).padding(top = KnotworkTheme.spacing.sp2)) {
            Text(text = title, style = KnotworkTextStyles.TitleMd, modifier = Modifier.semantics { heading() })
            if (sub != null) {
                Text(
                    text = sub,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(imageVector = AppIcons.X, contentDescription = stringResource(R.string.knotwork_run_verify_close_cd))
        }
    }
}

/** The determinate bar, the count and the time left, and Cancel. */
@Composable
private fun Progress(stage: VerificationStageUi.Running, onCancel: () -> Unit) {
    val target = if (stage.total == 0) 0f else stage.done.toFloat() / stage.total
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = if (KnotworkTheme.a11y.reducedMotion()) {
            tween(durationMillis = 0)
        } else {
            tween(durationMillis = KnotworkTheme.motion.dur3, easing = KnotworkTheme.motion.easeStd)
        },
        label = "verification_progress",
    )
    val primary = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        LinearProgressIndicator(
            progress = { progress },
            color = primary,
            trackColor = primary.copy(alpha = PROGRESS_TRACK_ALPHA),
            drawStopIndicator = {},
            gapSize = 0.dp,
            modifier = Modifier.fillMaxWidth().height(ProgressHeight).clip(KnotworkTheme.shapes.full),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (stage.left != null) {
                    pluralStringResource(
                        R.plurals.knotwork_run_verify_progress,
                        stage.total,
                        stage.done,
                        stage.total,
                        stage.left,
                    )
                } else {
                    pluralStringResource(
                        R.plurals.knotwork_run_verify_progress_count,
                        stage.total,
                        stage.done,
                        stage.total,
                    )
                },
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            KnotworkSecondaryButton(text = stringResource(R.string.knotwork_run_verify_cancel), onClick = onCancel)
        }
    }
}

/** The summary card of a finished, cancelled, stopped or failed check — the live region. */
@Composable
private fun Summary(stage: VerificationStageUi) {
    val extended = KnotworkTheme.extended
    val (glyph, tint) = when (stage) {
        is VerificationStageUi.AllMatched -> AppIcons.Check to extended.signalSuccess
        is VerificationStageUi.SomeDiverged -> AppIcons.Warn to extended.signalWarn
        VerificationStageUi.NothingVerifiable -> AppIcons.MinusCircle to extended.onSurfaceMuted
        is VerificationStageUi.Cancelled -> AppIcons.Stop to extended.onSurfaceMuted
        is VerificationStageUi.Stopped -> AppIcons.Stop to extended.signalWarn
        is VerificationStageUi.Failed -> AppIcons.AlertCircle to extended.signalError
        is VerificationStageUi.Running -> AppIcons.Circle to extended.onSurfaceMuted
    }
    val head = summaryHead(stage)
    val body = summaryBody(stage)
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(extended.surface1)
            .border(width = 1.dp, color = extended.divider, shape = KnotworkTheme.shapes.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Icon(imageVector = glyph, contentDescription = null, tint = tint, modifier = Modifier.size(SummaryGlyph))
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            Text(text = head, style = KnotworkTextStyles.TitleMd)
            body?.let {
                Text(text = it, style = KnotworkTextStyles.BodySm, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The summary's head. */
@Composable
private fun summaryHead(stage: VerificationStageUi): String = when (stage) {
    is VerificationStageUi.AllMatched ->
        pluralStringResource(R.plurals.knotwork_run_verify_all_head, stage.calls, stage.calls)
    is VerificationStageUi.SomeDiverged ->
        pluralStringResource(R.plurals.knotwork_run_verify_div_head, stage.nodes, stage.nodes)
    VerificationStageUi.NothingVerifiable -> stringResource(R.string.knotwork_run_verify_none_head)
    is VerificationStageUi.Cancelled ->
        pluralStringResource(R.plurals.knotwork_run_verify_cancelled_head, stage.total, stage.done, stage.total)
    is VerificationStageUi.Stopped ->
        pluralStringResource(R.plurals.knotwork_run_verify_stopped_head, stage.total, stage.done, stage.total)
    is VerificationStageUi.Failed -> stringResource(R.string.knotwork_run_verify_failed_head)
    is VerificationStageUi.Running -> ""
}

/** The summary's body, if it has one. */
@Composable
private fun summaryBody(stage: VerificationStageUi): String? = when (stage) {
    is VerificationStageUi.AllMatched -> {
        val matched = stringResource(R.string.knotwork_run_verify_all_body)
        if (stage.notVerifiable > 0) {
            val skipped = pluralStringResource(
                R.plurals.knotwork_run_verify_all_body_nv,
                stage.notVerifiable,
                stage.notVerifiable,
            )
            "$matched $skipped"
        } else {
            matched
        }
    }
    is VerificationStageUi.SomeDiverged ->
        stringResource(R.string.knotwork_run_verify_div_body, stage.firstNode, stage.call, stage.of)
    VerificationStageUi.NothingVerifiable -> stringResource(R.string.knotwork_run_verify_none_body)
    is VerificationStageUi.Cancelled -> stringResource(
        if (stage.allMatched) {
            R.string.knotwork_run_verify_cancelled_body
        } else {
            R.string.knotwork_run_verify_cancelled_body_diverged
        },
    )
    is VerificationStageUi.Stopped -> mismatchText(stage.reason)
    is VerificationStageUi.Failed -> stringResource(R.string.knotwork_run_verify_failed_body, stage.model, stage.reason)
    is VerificationStageUi.Running -> null
}

/** One node visit with its glyph, label, type and verdict line; the first diverged one tinted. */
@Composable
private fun VerdictRow(row: VerdictRowUi, highlighted: Boolean, onCopyHash: (String) -> Unit) {
    val extended = KnotworkTheme.extended
    val warn = extended.signalWarn
    val verdictText = verdictText(row.verdict)
    val spoken = stringResource(R.string.knotwork_run_verify_node_a11y, row.label, verdictText ?: row.type)
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = KnotworkTheme.spacing.sp4)) {
        if (row.depth > 0) {
            Box(modifier = Modifier.width(VerdictIndent * row.depth).padding(start = 9.dp)) {
                Box(
                    modifier = Modifier.width(
                        1.dp,
                    ).height(VerdictGlyph * 2).background(MaterialTheme.colorScheme.outline),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = if (highlighted) KnotworkTheme.spacing.sp1 else 0.dp)
                .then(
                    if (highlighted) {
                        Modifier
                            .clip(KnotworkTheme.shapes.md)
                            .background(warn.copy(alpha = DIVERGED_TINT_ALPHA))
                            .border(
                                width = 1.5.dp,
                                color = warn.copy(alpha = DIVERGED_OUTLINE_ALPHA),
                                shape = KnotworkTheme.shapes.md,
                            )
                            .padding(KnotworkTheme.spacing.sp3)
                    } else {
                        Modifier.padding(vertical = KnotworkTheme.spacing.sp2, horizontal = 2.dp)
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.width(VerdictGlyphColumn)) {
                VerdictGlyph(verdict = row.verdict)
            }
            Column(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(text = row.label, style = KnotworkTextStyles.BodyBase, fontWeight = FontWeight.SemiBold)
                        Text(text = row.type, style = KnotworkTextStyles.MonoSm, color = extended.onSurfaceMuted)
                    }
                    verdictText?.let {
                        Text(text = it, style = KnotworkTextStyles.MonoSm, color = verdictColor(row.verdict))
                    }
                }
                val diverged = row.verdict as? VerdictUi.Diverged
                if (highlighted && diverged != null) {
                    DivergedHashes(verdict = diverged, onCopyHash = onCopyHash)
                }
            }
        }
    }
}

/** The recorded and replayed hashes of the first diverged visit, each copyable. */
@Composable
private fun DivergedHashes(verdict: VerdictUi.Diverged, onCopyHash: (String) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    listOf(
        R.string.knotwork_run_verify_hash_recorded to verdict.recordedSha256,
        R.string.knotwork_run_verify_hash_replayed to verdict.replayedSha256,
    ).forEach { (template, sha) ->
        val text = stringResource(template, sha.take(SHORT_HASH_LENGTH))
        HashChip(
            text = text,
            textColor = onSurface,
            borderColor = outline,
            glyphColor = KnotworkTheme.extended.onSurfaceMuted,
            description = stringResource(
                R.string.knotwork_run_hash_short_a11y,
                text,
                spaced(sha.take(SHORT_HASH_LENGTH)),
            ),
            onCopy = { onCopyHash(sha) },
        )
    }
}

/** A verdict's glyph: the dots while checking, a group heading's node glyph, otherwise one of five. */
@Composable
private fun VerdictGlyph(verdict: VerdictUi) {
    if (verdict is VerdictUi.Checking) {
        CheckingDots()
        return
    }
    val icon: ImageVector = when (verdict) {
        VerdictUi.Group -> AppIcons.NodePipeline
        is VerdictUi.Matched -> AppIcons.Check
        is VerdictUi.Diverged -> AppIcons.Warn
        is VerdictUi.NotVerifiable -> AppIcons.MinusCircle
        VerdictUi.Waiting, VerdictUi.Unchecked, is VerdictUi.Checking -> AppIcons.Circle
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = glyphColor(verdict),
        modifier = Modifier.size(VerdictGlyph),
    )
}

/** Three dots for the row being repeated; static under reduced motion. */
@Composable
private fun CheckingDots() {
    val primary = MaterialTheme.colorScheme.primary
    val reduced = KnotworkTheme.a11y.reducedMotion()
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2),
    ) {
        CHECKING_DOT_ALPHAS.forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(KnotworkTheme.shapes.full)
                    .background(primary.copy(alpha = if (reduced) CHECKING_DOT_STATIC_ALPHA else alpha)),
            )
        }
    }
}

/** A verdict's glyph colour. */
@Composable
private fun glyphColor(verdict: VerdictUi): Color = when (verdict) {
    is VerdictUi.Matched -> KnotworkTheme.extended.signalSuccess
    is VerdictUi.Diverged -> KnotworkTheme.extended.signalWarn
    is VerdictUi.NotVerifiable, VerdictUi.Group -> KnotworkTheme.extended.onSurfaceMuted
    VerdictUi.Waiting, VerdictUi.Unchecked -> KnotworkTheme.extended.onSurfaceDim
    is VerdictUi.Checking -> MaterialTheme.colorScheme.primary
}

/** A verdict line's colour. */
@Composable
private fun verdictColor(verdict: VerdictUi): Color = when (verdict) {
    is VerdictUi.Matched -> KnotworkTheme.extended.signalSuccess
    is VerdictUi.Diverged, is VerdictUi.Checking -> MaterialTheme.colorScheme.onSurface
    is VerdictUi.NotVerifiable -> MaterialTheme.colorScheme.onSurfaceVariant
    VerdictUi.Waiting, VerdictUi.Unchecked, VerdictUi.Group -> KnotworkTheme.extended.onSurfaceMuted
}

/** A verdict's line, or `null` for a sub-pipeline heading. */
@Composable
private fun verdictText(verdict: VerdictUi): String? = when (verdict) {
    VerdictUi.Group -> null
    VerdictUi.Waiting -> stringResource(R.string.knotwork_run_verify_node_waiting)
    VerdictUi.Unchecked -> stringResource(R.string.knotwork_run_verify_node_unchecked)
    is VerdictUi.Checking -> stringResource(R.string.knotwork_run_verify_node_checking, verdict.call, verdict.of)
    is VerdictUi.Matched -> pluralStringResource(
        R.plurals.knotwork_run_verify_node_matched,
        verdict.calls,
        verdict.calls,
    )
    is VerdictUi.Diverged -> stringResource(R.string.knotwork_run_verify_node_diverged, verdict.call, verdict.of)
    is VerdictUi.NotVerifiable -> stringResource(
        when (verdict.reason) {
            NotVerifiableUi.TOOL -> R.string.knotwork_run_nv_tool
            NotVerifiableUi.CLOUD -> R.string.knotwork_run_nv_cloud
            NotVerifiableUi.NO_CALL -> R.string.knotwork_run_nv_no_call
            NotVerifiableUi.NPU -> R.string.knotwork_run_nv_npu
            NotVerifiableUi.IMAGE -> R.string.knotwork_run_nv_image
            NotVerifiableUi.MODEL_NOT_RECORDED -> R.string.knotwork_run_nv_model_not_recorded
        },
    )
}

/** The not-signed sentence under a result that claims a match; Verify again after an interrupted check. */
@Composable
private fun Footer(stage: VerificationStageUi, onVerifyAgain: () -> Unit) {
    val signed = stage is VerificationStageUi.AllMatched || stage is VerificationStageUi.SomeDiverged
    val again = stage is VerificationStageUi.Cancelled ||
        stage is VerificationStageUi.Failed ||
        stage is VerificationStageUi.Stopped
    if (!signed && !again) return
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(KnotworkTheme.extended.divider))
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp5, vertical = KnotworkTheme.spacing.sp3),
    ) {
        if (signed) {
            Text(
                text = stringResource(R.string.knotwork_run_not_signed),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        if (again) {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_run_verify_again),
                onClick = onVerifyAgain,
                leadingIcon = AppIcons.Refresh,
            )
        }
    }
}
