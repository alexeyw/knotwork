package app.knotwork.design.components.misc

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/** Diameter of one loader dot. */
private val LoaderDotSize = 8.dp

/**
 * Total visible loop duration (full alpha down-and-up cycle), in milliseconds.
 *
 * The per-direction tween is half of this — `RepeatMode.Reverse` plays the
 * tween forward and then backward, so a 1200 ms full cycle needs a 600 ms
 * tween. Computing the half here keeps the documented loop value as the
 * single source of truth.
 */
private const val LOADER_LOOP_MS = 1200

/** Duration of a single direction of the reverse loop (half of [LOADER_LOOP_MS]). */
private const val LOADER_HALF_LOOP_MS = LOADER_LOOP_MS / 2

/** Per-dot stagger inside the loop, in milliseconds. */
private const val LOADER_STAGGER_MS = 200

/** Minimum and maximum alpha values reached during the loop. */
private const val LOADER_MIN_ALPHA = 0.2f
private const val LOADER_MAX_ALPHA = 1.0f

/**
 * Knotwork brand loader — three pulsing dots in the accent ramp
 * `Accent300 → Accent400 → Accent500` (no `Accent600`; the spec ramp ends
 * at 500). Used for chat "Generating…" and editor "Validating…".
 *
 * Visual contract:
 *  - 1.2 s loop with a 200 ms stagger between dots; each dot fades from 1.0
 *    to 0.2 to 1.0 alpha.
 *  - Reduced motion: looping animation collapses to
 *    a static `"•••"` glyph in `LabelLg onSurfaceMuted` — never strobes.
 *  - Marked `contentDescription = "Loading"` so TalkBack announces the
 *    state instead of decorative dots.
 *
 * @param modifier optional layout modifier applied to the loader root.
 */
@Composable
fun KnotworkLoader(modifier: Modifier = Modifier) {
    if (KnotworkTheme.a11y.reducedMotion()) {
        Text(
            text = "•••",
            style = KnotworkTextStyles.LabelLg,
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = modifier.semantics { contentDescription = "Loading" },
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "knotwork_loader")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = modifier.semantics { contentDescription = "Loading" },
    ) {
        LoaderDot(transition = transition, color = KnotworkPalette.Accent300, delayMs = 0)
        LoaderDot(transition = transition, color = KnotworkPalette.Accent400, delayMs = LOADER_STAGGER_MS)
        LoaderDot(transition = transition, color = KnotworkPalette.Accent500, delayMs = LOADER_STAGGER_MS * 2)
    }
}

/** One pulsing dot — animated alpha keyed off the shared infinite transition. */
@Composable
private fun LoaderDot(transition: androidx.compose.animation.core.InfiniteTransition, color: Color, delayMs: Int) {
    val alpha by transition.animateFloat(
        initialValue = LOADER_MIN_ALPHA,
        targetValue = LOADER_MAX_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = LOADER_HALF_LOOP_MS, easing = LinearEasing, delayMillis = delayMs),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "knotwork_loader_dot",
    )
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(LoaderDotSize)
            .alpha(alpha)
            .background(color = color, shape = CircleShape),
    )
}
