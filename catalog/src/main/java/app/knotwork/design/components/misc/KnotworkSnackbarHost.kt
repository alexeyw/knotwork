package app.knotwork.design.components.misc

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one snackbar host of the app: Material3's [SnackbarHost] rendering every message
 * as a [KnotworkSnackbar] with Material's outer margin.
 *
 * Screens used to render their own: the raw Material3 snackbar on some, the Knotwork
 * one on others, and the Knotwork one without the margin (flush with the screen edges)
 * on the rest. `SnackbarHostGuardTest` keeps every host going through this one.
 *
 * **Placement stays the caller's**, and there are two right answers:
 *  - inside a `Scaffold`, pass it to the `snackbarHost` slot — the Scaffold lifts it
 *    above its bottom bar and floating action button, whatever their height;
 *  - over a screen with no Scaffold, align it to the bottom centre of a container
 *    whose bottom edge is already clear of the system bars and of any control pinned
 *    there.
 * A host placed any other way — unaligned in a `Box` put it at the top-left, over the
 * top bar — is what this component exists to end.
 *
 * @param hostState The state the screen shows its messages through.
 * @param modifier Placement modifier.
 * @param variant Visual variant of every message this host shows.
 */
@Composable
fun KnotworkSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    variant: SnackbarVariant = SnackbarVariant.Default,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        KnotworkSnackbar(data = data, modifier = Modifier.padding(SnackbarOuterMargin), variant = variant)
    }
}

/** Material3's own snackbar margin, which the host applies around each message. */
private val SnackbarOuterMargin = 12.dp
