package app.knotwork.design.components.misc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * A persistent, warning-toned notice with one inline action.
 *
 * Use it for a condition the user has to resolve deliberately and which should
 * stay visible until they do — not for transient feedback (that is
 * [KnotworkSnackbar]) and not for a blocking decision (that is a dialog). The
 * memory provider-mismatch notice and the unencrypted-connection consent notice
 * are both this shape: a sentence explaining the state, and one button that
 * resolves it.
 *
 * @param text the message, already localised.
 * @param actionLabel label for the single action button, already localised.
 * @param onAction invoked when the action button is pressed.
 * @param modifier layout modifier applied to the banner surface.
 * @param testTag tag for UI / snapshot tests; empty means no tag is applied.
 */
@Composable
fun KnotworkWarningBanner(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "",
) {
    Surface(
        shape = KnotworkTheme.shapes.md,
        color = KnotworkTheme.extended.signalWarn.copy(alpha = BANNER_TINT_ALPHA),
        border = BorderStroke(BANNER_BORDER_WIDTH, KnotworkTheme.extended.signalWarn),
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag.isEmpty()) Modifier else Modifier.testTag(testTag)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp3),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            Text(text = text, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
            KnotworkSecondaryButton(text = actionLabel, onClick = onAction, size = KnotworkButtonSize.Sm)
        }
    }
}

/** Tint strength of the warning fill behind the banner text. */
private const val BANNER_TINT_ALPHA = 0.12f

/** Hairline border, matching the settings section cards. */
private val BANNER_BORDER_WIDTH = 1.dp
