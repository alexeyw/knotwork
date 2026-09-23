@file:Suppress("MatchingDeclarationName") // File hosts `Status` enum + `StatusPill` composable + pulse helper.

package app.knotwork.design.components.chips

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Pipeline / connection run status. Maps to the `extended.signal*` palette
 * and the new neutral / dim tones for queued / cancelled runs.
 *
 * Spec mapping:
 *  - [Queued] — neutral muted tone, no animation.
 *  - [Running] — accent primary, **pulsing dot** (alpha 0.4↔1.0 over 1.4 s).
 *  - [Success] — `signalSuccess`.
 *  - [Warning] — `signalWarn`.
 *  - [Error] — `riskDestructive` (matches the spec rename; signalError still
 *    points at the same hue but the visual contract names it after the risk
 *    family it shares the colour with).
 *  - [Cancelled] — dim tone, no animation.
 *  - [Idle] — kept for callers that already rely on the previous default;
 *    behaves like [Queued].
 */
enum class Status {
    /** Queued for execution; no dot animation. */
    Queued,

    /** No run yet. Treated as a neutral synonym for [Queued]; kept for source compatibility. */
    Idle,

    /** Run in progress; the leading dot pulses while this state is active. */
    Running,

    /** Run completed successfully. */
    Success,

    /** Run finished with non-fatal warnings. */
    Warning,

    /** Run failed. */
    Error,

    /** Run cancelled by the user; dim tone, no animation. */
    Cancelled,
}

/**
 * Knotwork status pill — same geometry as [RiskPill], colour family bound
 * to the pipeline state machine.
 *
 * Visual contract:
 *  - 24 dp tall pill, `KnotworkTheme.shapes.full`, transparent fill,
 *    1 dp coloured border + leading 6 dp dot, `Mono13` label.
 *  - [Status.Running] pulses the dot's alpha through an
 *    [infiniteRepeatable] tween. The pulse honours
 *    `KnotworkTheme.a11y.reducedMotion` — when reduced motion is on, the
 *    dot stays at full opacity so the user never sees vestibular motion.
 *  - `contentDescription` reads `"Status: <label>"`.
 *
 * @param status Status driving the colour and label.
 * @param modifier Optional layout modifier applied to the pill root.
 */
@Composable
fun StatusPill(status: Status, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    val pulseAlpha = if (status == Status.Running && !KnotworkTheme.a11y.reducedMotion()) {
        rememberPulseAlpha()
    } else {
        1f
    }
    Surface(
        shape = KnotworkTheme.shapes.full,
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(width = 1.dp, color = color),
        modifier = modifier
            .height(StatusPillHeight)
            .semantics { contentDescription = "Status: ${status.label}" },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .alpha(pulseAlpha)
                    .size(StatusDotSize)
                    .background(color = color, shape = CircleShape),
            )
            Text(
                text = status.label,
                style = KnotworkTextStyles.MonoSm,
                color = color,
            )
        }
    }
}

@Composable
private fun rememberPulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "status-pill-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "status-pill-pulse-alpha",
    )
    return alpha
}

private const val PULSE_DURATION_MS = 1400

@Composable
private fun statusColor(status: Status): Color = when (status) {
    Status.Queued, Status.Idle -> KnotworkTheme.extended.onSurfaceMuted
    Status.Running -> MaterialTheme.colorScheme.primary
    Status.Success -> KnotworkTheme.extended.signalSuccess
    Status.Warning -> KnotworkTheme.extended.signalWarn
    Status.Error -> KnotworkTheme.extended.riskDestructive
    Status.Cancelled -> KnotworkTheme.extended.onSurfaceDim
}

/** Human-readable label rendered inside the pill (also used in `contentDescription`). */
private val Status.label: String
    get() = when (this) {
        Status.Queued -> "Queued"
        Status.Idle -> "Idle"
        Status.Running -> "Running"
        Status.Success -> "Success"
        Status.Warning -> "Warning"
        Status.Error -> "Error"
        Status.Cancelled -> "Cancelled"
    }

private val StatusPillHeight = 24.dp
private val StatusDotSize = 6.dp
