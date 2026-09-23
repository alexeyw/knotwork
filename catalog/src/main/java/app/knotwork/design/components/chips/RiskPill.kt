@file:Suppress("MatchingDeclarationName") // File hosts `Risk` enum + `RiskPill` composable + helpers.

package app.knotwork.design.components.chips

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Per-tool risk classification used by HITL prompts and agent dashboards.
 *
 * Maps to `extended.risk*` colours; never re-themed by accent (the colour
 * conveys state, not aesthetic).
 */
enum class Risk {
    /** Auto-approved read-only tool. */
    Readonly,

    /** User opt-in tool with a "Always allow" affordance. */
    Sensitive,

    /** High-risk tool requiring typed confirmation. */
    Destructive,
}

/**
 * Knotwork risk pill — transparent fill, 1 dp border in the risk colour,
 * leading 6 dp dot, mono `Mono13` label.
 *
 * Visual contract:
 *  - 24 dp tall pill, `KnotworkTheme.shapes.full`, 10 dp horizontal padding.
 *  - Container: transparent. The border + dot carry the colour signal so
 *    the pill reads on every surface (white card, console, chat bubble)
 *    without the previous filled-pill contrast trap on inverted surfaces.
 *  - Glyph dropped in favour of the leading dot to match the
 *    `StatusPill` family and keep the family geometry consistent — the
 *    label still carries the risk name, so the colour-is-not-the-only-signal
 *    rule is satisfied by `text + colour + dot`.
 *  - `contentDescription` reads `"Risk level: <level>"`.
 *
 * @param risk Risk tier driving border colour, dot colour, and label.
 * @param modifier Optional layout modifier applied to the pill root.
 */
@Composable
fun RiskPill(risk: Risk, modifier: Modifier = Modifier) {
    val color = riskColor(risk)
    Surface(
        shape = KnotworkTheme.shapes.full,
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(width = 1.dp, color = color),
        modifier = modifier
            .height(RiskPillHeight)
            .semantics { contentDescription = "Risk level: ${risk.label}" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(RiskDotSize)
                    .background(color = color, shape = CircleShape),
            )
            Text(
                text = risk.label,
                // JetBrains Mono 500, 11 px, +0.2 tracking.
                style = KnotworkTextStyles.MonoSm.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                ),
                color = color,
            )
        }
    }
}

/** Maps a [Risk] to the matching `extended.risk*` colour. */
@Composable
private fun riskColor(risk: Risk): Color = when (risk) {
    Risk.Readonly -> KnotworkTheme.extended.riskReadonly
    Risk.Sensitive -> KnotworkTheme.extended.riskSensitive
    Risk.Destructive -> KnotworkTheme.extended.riskDestructive
}

/** Human-readable label rendered inside the pill (also used in `contentDescription`). */
private val Risk.label: String
    get() = when (this) {
        Risk.Readonly -> "Read only"
        Risk.Sensitive -> "Sensitive"
        Risk.Destructive -> "Destructive"
    }

private val RiskPillHeight = 24.dp
private val RiskDotSize = 6.dp
