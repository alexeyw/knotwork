package app.knotwork.design.components.chips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.triggers.TriggerHealthUi
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Trigger health-badge — a compact pill on the trigger list row telling, at a
 * glance, whether a background trigger is still being evaluated and whether its
 * last run succeeded.
 *
 * Visual contract:
 *  - Same pill geometry as [StatusPill] (24 dp tall, [KnotworkTheme.shapes.full],
 *    1 dp coloured border) but a **tinted** fill and, crucially, a **glyph**
 *    instead of the status dot: the state is carried by **icon + label**, never
 *    colour alone (a11y). Colour is a redundant channel — success `signalSuccess`,
 *    overdue `signalWarn`, error `signalError`.
 *  - At font-scale ≥ 2.0 the badge collapses to **icon-only** so a long trigger
 *    name + badge + switch + overflow still fit on the row; the label is retained
 *    in `contentDescription` for TalkBack.
 *  - `contentDescription` always reads `"Health: <label>"`.
 *
 * @param state Health state driving the colour, glyph and default label.
 * @param label Localised human label (also spoken by TalkBack when icon-only).
 * @param modifier Optional layout modifier applied to the pill root.
 */
@Composable
fun HealthBadge(state: TriggerHealthUi, label: String, modifier: Modifier = Modifier) {
    val color = healthColor(state)
    val iconOnly = KnotworkTheme.a11y.fontScale() >= ICON_ONLY_FONT_SCALE
    Surface(
        shape = KnotworkTheme.shapes.full,
        color = color.copy(alpha = FILL_ALPHA),
        contentColor = color,
        border = BorderStroke(width = 1.dp, color = color.copy(alpha = BORDER_ALPHA)),
        modifier = modifier
            .height(BadgeHeight)
            // Replace the subtree's semantics with a single description so TalkBack
            // announces "Health: <label>" once, not the glyph-less label a second
            // time from the inner Text (and so the label survives the icon-only
            // collapse at font-scale ≥ 2.0).
            .clearAndSetSemantics { contentDescription = "Health: $label" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (iconOnly) 0.dp else IconLabelGap),
            modifier = Modifier.padding(horizontal = if (iconOnly) IconOnlyPadding else LabelPadding),
        ) {
            Icon(
                imageVector = state.glyph(),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(BadgeGlyphSize),
            )
            if (!iconOnly) {
                Text(text = label, style = KnotworkTextStyles.MonoSm, color = color)
            }
        }
    }
}

/** The glyph for a health state — reuses existing `AppIcons`, no colour-alone. */
private fun TriggerHealthUi.glyph() = when (this) {
    TriggerHealthUi.Healthy -> AppIcons.Check
    TriggerHealthUi.Overdue -> AppIcons.Warn
    TriggerHealthUi.LastRunFailed -> AppIcons.AlertCircle
}

@Composable
private fun healthColor(state: TriggerHealthUi): Color = when (state) {
    TriggerHealthUi.Healthy -> KnotworkTheme.extended.signalSuccess
    TriggerHealthUi.Overdue -> KnotworkTheme.extended.signalWarn
    TriggerHealthUi.LastRunFailed -> KnotworkTheme.extended.signalError
}

/** Font scale at or above which the badge drops its label to protect the row. */
private const val ICON_ONLY_FONT_SCALE = 2.0f

/** Tinted fill / border alphas over the state colour. */
private const val FILL_ALPHA = 0.12f
private const val BORDER_ALPHA = 0.34f

private val BadgeHeight = 22.dp
private val BadgeGlyphSize = 13.dp
private val IconLabelGap = 5.dp
private val LabelPadding = 9.dp
private val IconOnlyPadding = 5.dp
