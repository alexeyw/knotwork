@file:Suppress(
    "MatchingDeclarationName", // File hosts `SnackbarVariant` plus the `KnotworkSnackbar` composable.
)

package app.knotwork.design.components.misc

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Knotwork snackbar variant — `Default` (neutral surface), `Error`
 * (`errorContainer`), or `Success` (1 dp `signalSuccess` border).
 */
enum class SnackbarVariant {
    /** Default neutral surface; `extended.surface3` container. */
    Default,

    /** Error surface; `colorScheme.errorContainer` background. */
    Error,

    /** Success border; transparent container plus a 1 dp `signalSuccess` border. */
    Success,
}

/**
 * Knotwork snackbar — thin wrapper over Material3 [Snackbar] that recolours
 * by [variant] and pushes the action label through [KnotworkTextStyles].
 *
 * Visual contract:
 *  - Default: container `extended.surface3`, label `onSurface`.
 *  - Error:   container `colorScheme.errorContainer`, label
 *    `colorScheme.onErrorContainer`.
 *  - Success: transparent container, 1 dp `signalSuccess` border, label
 *    `onSurface`.
 *
 * Typically rendered from a [androidx.compose.material3.SnackbarHost]; the
 * caller obtains [SnackbarData] from the host's snapshot flow.
 *
 * @param data Material3 [SnackbarData] from the host; carries the message
 * and optional action.
 * @param modifier optional layout modifier applied to the snackbar root.
 * @param variant visual variant; defaults to [SnackbarVariant.Default].
 */
@Composable
fun KnotworkSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
    variant: SnackbarVariant = SnackbarVariant.Default,
) {
    val container = when (variant) {
        SnackbarVariant.Default -> KnotworkTheme.extended.surface3
        SnackbarVariant.Error -> MaterialTheme.colorScheme.errorContainer
        SnackbarVariant.Success -> Color.Transparent
    }
    val contentColor = when (variant) {
        SnackbarVariant.Default -> MaterialTheme.colorScheme.onSurface
        SnackbarVariant.Error -> MaterialTheme.colorScheme.onErrorContainer
        SnackbarVariant.Success -> MaterialTheme.colorScheme.onSurface
    }
    val borderedModifier = when (variant) {
        SnackbarVariant.Success -> modifier.border(
            width = 1.dp,
            color = KnotworkTheme.extended.signalSuccess,
            shape = KnotworkTheme.shapes.md,
        )
        else -> modifier
    }
    Snackbar(
        modifier = borderedModifier,
        shape = KnotworkTheme.shapes.md,
        containerColor = container,
        contentColor = contentColor,
        action = data.visuals.actionLabel?.let { actionLabel ->
            {
                TextButton(onClick = { data.performAction() }) {
                    Text(
                        text = actionLabel,
                        style = KnotworkTextStyles.LabelLg,
                        color = contentColor,
                    )
                }
            }
        },
    ) {
        Text(text = data.visuals.message, style = KnotworkTextStyles.BodyBase)
    }
}
