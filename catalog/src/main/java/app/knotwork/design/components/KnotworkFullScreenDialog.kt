package app.knotwork.design.components

import android.view.WindowInsetsController
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.knotwork.design.theme.KnotworkTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Opening: the dialog rises this far as it fades in. */
private const val OPEN_RISE_DP = 24

private const val OPEN_MS = 250
private const val CLOSE_MS = 200
private const val REDUCED_MOTION_FADE_MS = 100

/** The dialog's scale at the end of a back gesture's preview. */
internal const val BACK_PREVIEW_SCALE = 0.92f

/**
 * A full-screen dialog over the screen that opened it, which keeps its own state —
 * a list keeps its scroll — underneath. The console's value viewer and the chat's
 * message reader are drawn in it.
 *
 * It rises 24 dp and fades in on open (250 ms, decelerating) and drops out on close
 * (200 ms, accelerating); under reduced motion both are a 100 ms fade. [onClose] is
 * called once the closing motion has ended.
 *
 * Back closes it. During a predictive back gesture the content follows the gesture:
 * it scales towards [BACK_PREVIEW_SCALE] and rounds its corners to `shapes.xl`,
 * showing the screen behind; a cancelled gesture returns it, a committed one runs the
 * closing motion from where the preview left it. Under reduced motion there is no
 * preview.
 *
 * @param onClose called once the closing motion has ended.
 * @param darkBackground whether the content is dark, so the status and navigation
 *   bars draw light icons over it; dark icons otherwise.
 * @param content the dialog's surface, given the function that closes the dialog and
 *   the modifier that draws the back preview, which it applies to its root.
 */
@Composable
internal fun KnotworkFullScreenDialog(
    onClose: () -> Unit,
    darkBackground: Boolean,
    content: @Composable (close: () -> Unit, modifier: Modifier) -> Unit,
) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState && !visible.targetState) onClose()
    }
    val close = { visible.targetState = false }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SystemBarIcons(darkBackground)
        val reduced = KnotworkTheme.a11y.reducedMotion()
        val motion = KnotworkTheme.motion
        val rise = with(LocalDensity.current) { OPEN_RISE_DP.dp.roundToPx() }
        val preview = rememberBackPreview(enabled = visible.targetState, reduced = reduced, onBack = close)
        val corner = KnotworkTheme.shapes.xl
        AnimatedVisibility(
            visibleState = visible,
            enter = if (reduced) {
                fadeIn(tween(REDUCED_MOTION_FADE_MS))
            } else {
                fadeIn(tween(OPEN_MS, easing = motion.easeDecel)) +
                    slideInVertically(tween(OPEN_MS, easing = motion.easeDecel)) { rise }
            },
            exit = if (reduced) {
                fadeOut(tween(REDUCED_MOTION_FADE_MS))
            } else {
                fadeOut(tween(CLOSE_MS, easing = motion.easeAccel)) +
                    slideOutVertically(tween(CLOSE_MS, easing = motion.easeAccel)) { rise }
            },
        ) {
            content(close, Modifier.backPreview(preview, corner))
        }
    }
}

/**
 * How far a predictive back gesture has drawn the dialog into its preview, from 0 to 1:
 * the gesture's progress through the decelerate curve, as the platform asks of an
 * in-app preview; always 0 under reduced motion.
 *
 * @param progress the gesture's progress, from 0 to 1.
 * @param reduced whether reduced motion is on.
 * @param easing the curve the progress goes through.
 * @return the preview's share, from 0 to 1.
 */
internal fun backPreviewShare(progress: Float, reduced: Boolean, easing: Easing): Float =
    if (reduced) 0f else easing.transform(progress.coerceIn(0f, 1f))

/**
 * Follows a predictive back gesture while [enabled]: the returned state is the preview's
 * share ([backPreviewShare]). A committed gesture — or a back press with no gesture —
 * calls [onBack] and leaves the preview where it was, so the closing motion starts from
 * it; a cancelled one returns the preview to 0.
 */
@Composable
private fun rememberBackPreview(enabled: Boolean, reduced: Boolean, onBack: () -> Unit): State<Float> {
    val preview = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val motion = KnotworkTheme.motion
    PredictiveBackHandler(enabled = enabled) { events: Flow<BackEventCompat> ->
        var committed = false
        try {
            events.collect { event ->
                preview.snapTo(backPreviewShare(event.progress, reduced, motion.easeDecel))
            }
            committed = true
            onBack()
        } finally {
            // A cancelled gesture cancels this block; the return runs on its own.
            if (!committed) scope.launch { preview.animateTo(0f, tween(motion.dur2, easing = motion.easeStd)) }
        }
    }
    return preview.asState()
}

/** Scales the dialog's content and rounds its corners by the back preview's share. */
private fun Modifier.backPreview(preview: State<Float>, corner: CornerBasedShape): Modifier = graphicsLayer {
    val share = preview.value
    val scale = 1f - (1f - BACK_PREVIEW_SCALE) * share
    scaleX = scale
    scaleY = scale
    if (share > 0f) {
        shape = RoundedCornerShape(corner.topStart.toPx(size, this) * share)
        clip = true
    }
}

/** Light icons on the status and navigation bars over a dark background, dark icons over a light one. */
@Composable
private fun SystemBarIcons(darkBackground: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window?.insetsController?.setSystemBarsAppearance(if (darkBackground) 0 else light, light)
    }
}
