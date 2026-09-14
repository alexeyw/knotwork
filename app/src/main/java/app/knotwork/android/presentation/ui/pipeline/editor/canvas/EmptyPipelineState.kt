package app.knotwork.android.presentation.ui.pipeline.editor.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.knotwork.android.R
import app.knotwork.android.presentation.ui.pipeline.editor.core.CanvasTransform
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlin.math.roundToInt

/**
 * Empty-pipeline canvas state — replaces the old single-line
 * "Long-press anywhere…" hint with the designer's hero treatment.
 *
 * Layout (top-down): brand-mark tile → `Empty pipeline` title → 2-line helper
 * copy → action row (`+ Start with INPUT` / `From template`) → info pill
 * (`canvas: 1200 × 1600 · 24 dp grid · 1.00×`).
 *
 * The composable centres itself in the parent box and clamps its width so it
 * reads cleanly on phones and tablets alike.
 *
 * @param scale current canvas scale — drives the trailing zoom-percentage in
 *   the info pill (`1.00×`).
 * @param onStartWithInput invoked when the primary CTA is tapped. Caller adds
 *   an `INPUT` node at viewport centre.
 * @param onFromTemplate invoked when the secondary CTA is tapped. Caller opens
 *   the template gallery (a placeholder snackbar until the gallery lands).
 * @param modifier optional layout modifier (typically `.fillMaxSize()`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyPipelineState(
    scale: Float,
    onStartWithInput: () -> Unit,
    onFromTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
            modifier = Modifier.widthIn(max = MaxContentWidth),
        ) {
            BrandTile()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = stringResource(R.string.pipeline_editor_empty_title),
                    style = KnotworkTextStyles.TitleLg,
                    color = KnotworkTheme.extended.onSurface2,
                )
                Text(
                    text = stringResource(R.string.pipeline_editor_empty_helper),
                    style = KnotworkTextStyles.BodyBase,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp4),
                )
            }
            // `FlowRow` lets the secondary CTA drop onto a second line when the
            // narrow-screen layout would otherwise truncate `From template` to
            // `Fro…`. `KnotworkButtonSize.Sm` shrinks the buttons enough that
            // both fit side-by-side on most phones. The secondary CTA drops its
            // leading icon — the label is already self-descriptive and the icon
            // ate the horizontal budget that caused the truncation.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(
                    space = KnotworkTheme.spacing.sp3,
                    alignment = Alignment.CenterHorizontally,
                ),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier.widthIn(max = MaxContentWidth),
            ) {
                KnotworkPrimaryButton(
                    text = stringResource(R.string.pipeline_editor_empty_cta_start),
                    onClick = onStartWithInput,
                    size = KnotworkButtonSize.Sm,
                    leadingIcon = AppIcons.Add,
                )
                KnotworkSecondaryButton(
                    text = stringResource(R.string.pipeline_editor_empty_cta_template),
                    onClick = onFromTemplate,
                    size = KnotworkButtonSize.Sm,
                )
            }
            InfoPill(scale = scale)
        }
    }
}

/** 80 dp brand tile with dashed-style border and the Knotwork Flow glyph. */
@Composable
private fun BrandTile() {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(KnotworkTheme.shapes.md)
            .border(
                width = 1.dp,
                color = KnotworkTheme.extended.outlineStrong,
                shape = KnotworkTheme.shapes.md,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Flow,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * Info pill displayed at the bottom of the empty state.
 *
 * Wording (`canvas: 1200 × 1600 · 24 dp grid · 1.00×`) is informational —
 * useful for designers comparing canvas geometry against the Figma source. The
 * canvas extent is illustrative; the editor itself is mathematically infinite.
 */
@Composable
private fun InfoPill(scale: Float) {
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .background(color = KnotworkTheme.extended.surface2, shape = KnotworkTheme.shapes.full)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = stringResource(
                R.string.pipeline_editor_empty_info_pill,
                CANVAS_REFERENCE_WIDTH,
                CANVAS_REFERENCE_HEIGHT,
                CanvasTransform.GRID_STEP.roundToInt(),
                formatScalePercent(scale),
            ),
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

/** Maximum content width — keeps the helper copy from sprawling across a tablet. */
private val MaxContentWidth = 360.dp

/** Reference canvas geometry numbers reported in the info pill. */
private const val CANVAS_REFERENCE_WIDTH = 1200
private const val CANVAS_REFERENCE_HEIGHT = 1600
