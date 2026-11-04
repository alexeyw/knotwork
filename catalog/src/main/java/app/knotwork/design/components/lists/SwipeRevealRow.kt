package app.knotwork.design.components.lists

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Width of the single revealed action — also the reveal distance of the row. */
private val SwipeRevealActionWidth = 64.dp

/** Fraction of the reveal width past which a release snaps open. */
private const val SWIPE_OPEN_THRESHOLD = 0.5f

/** Glyph size inside the revealed action. */
private val SwipeRevealGlyphSize = 20.dp

/**
 * The single action a [SwipeRevealRow] reveals.
 *
 * @property icon glyph rendered above the label.
 * @property label short verb ("Archive", "Restore"). Rendered *and* used as the
 *   action's `contentDescription` — the affordance is never colour-only.
 * @property background strip fill (`extended.signalWarn` for Archive,
 *   `colorScheme.primary` for Restore).
 * @property foreground icon + label colour on [background].
 * @property onClick invoked when the user taps the revealed action.
 */
data class SwipeRevealAction(
    val icon: ImageVector,
    val label: String,
    val background: Color,
    val foreground: Color,
    val onClick: () -> Unit,
)

/**
 * Wraps a list row in a **single** swipe-revealed action.
 *
 * Mechanics are [PipelineListRow]'s — a hand-rolled [Animatable] offset driven
 * by `Modifier.draggable`, clamped to `[-64.dp, 0]`, snapping at
 * [SWIPE_OPEN_THRESHOLD] of the reveal — but with one action instead of three,
 * so the strip claims 64 dp (20 % of a 320 dp drawer) instead of 192 dp. A
 * partial reveal is **never** a dismiss: releasing below the threshold springs
 * the row shut and nothing happens.
 *
 * The strip sits **behind** the row and the row slides over it, so a row that
 * paints its own background (an active drawer thread's `primaryContainer` fill)
 * carries that fill with it instead of fighting the strip.
 *
 * Swipe is never the only path to the action — callers must also expose it in
 * the row's overflow menu and as a TalkBack custom action.
 *
 * Under reduced motion the row snaps between the two positions instead of
 * animating; the gesture itself still tracks the finger.
 *
 * @param action the revealed action.
 * @param modifier optional layout modifier applied to the root box.
 * @param revealed when non-null, drives the reveal state programmatically and
 *   disables the drag (used by snapshot tests and pre-seeded screens). `null`
 *   (default) hands control to the user's gesture.
 * @param content the row body, laid out over the strip. Must paint an opaque
 *   background of its own, otherwise the strip shows through.
 */
@Composable
fun SwipeRevealRow(
    action: SwipeRevealAction,
    modifier: Modifier = Modifier,
    revealed: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealPx = with(density) { SwipeRevealActionWidth.toPx() }
    val offsetAnimatable = remember { Animatable(initialValue = 0f) }
    val scope = rememberCoroutineScope()
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()

    LaunchedEffect(revealed, revealPx) {
        when (revealed) {
            true -> offsetAnimatable.animateTo(-revealPx)
            false -> offsetAnimatable.animateTo(0f)
            null -> Unit
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // `matchParentSize` rather than `fillMaxHeight`: this Box wraps its
        // content, so inside a LazyColumn the incoming height is unbounded and
        // `fillMaxHeight` silently collapses the strip to its own ~32 dp — a
        // short floating band beside the row instead of the full-height strip
        // the design draws. Matching the size the *content* settled on makes it
        // exactly as tall as the row.
        //
        // Visual, not reach: Compose already expands a `clickable`'s touch
        // bounds to the 48 dp minimum, so the stunted strip was still hittable.
        Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            SwipeRevealActionButton(
                action = action,
                modifier = Modifier
                    .width(SwipeRevealActionWidth)
                    .fillMaxHeight(),
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetAnimatable.value.roundToInt(), 0) }
                .fillMaxWidth()
                .draggable(
                    state = rememberDraggableState { delta ->
                        if (revealed == null) {
                            scope.launch {
                                val target = (offsetAnimatable.value + delta).coerceIn(-revealPx, 0f)
                                offsetAnimatable.snapTo(target)
                            }
                        }
                    },
                    orientation = Orientation.Horizontal,
                    enabled = revealed == null,
                    onDragStopped = {
                        scope.launch {
                            val openTarget = -revealPx
                            val snapTarget = if (offsetAnimatable.value < openTarget * SWIPE_OPEN_THRESHOLD) {
                                openTarget
                            } else {
                                0f
                            }
                            if (reducedMotion) {
                                offsetAnimatable.snapTo(snapTarget)
                            } else {
                                offsetAnimatable.animateTo(snapTarget)
                            }
                        }
                    },
                ),
        ) {
            content()
        }
    }
}

/** The revealed action itself — coloured strip + glyph + label, both spoken. */
@Composable
private fun SwipeRevealActionButton(action: SwipeRevealAction, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(action.background)
            .clickable(onClick = action.onClick, role = Role.Button)
            .semantics { contentDescription = action.label },
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = action.foreground,
            modifier = Modifier.size(SwipeRevealGlyphSize),
        )
        Text(
            text = action.label,
            style = KnotworkTextStyles.LabelSm,
            color = action.foreground,
        )
    }
}
