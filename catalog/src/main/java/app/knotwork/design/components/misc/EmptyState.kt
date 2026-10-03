package app.knotwork.design.components.misc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Default illustration slot size when the caller does not override the slot. */
private val DefaultIllustrationSize = 160.dp

/**
 * Knotwork empty-state — centered illustration slot + title + subtitle +
 * optional primary CTA. Used in screens that have no data to show
 * (`PipelineLibraryScreen` empty, `MemoryScreen` no-results, …).
 *
 * Visual contract:
 *  - Default illustration is a [StripedPlaceholder] sized to 160 × 160 dp;
 *    callers may override the [illustration] slot to drop in a real asset.
 *  - Title `TitleLg`, subtitle `BodyBase` in `extended.onSurfaceMuted`,
 *    centred horizontally.
 *  - CTA renders a [KnotworkPrimaryButton] when both [ctaLabel] and
 *    [onCtaClick] are supplied.
 *
 * @param title prominent title; rendered in `TitleLg`.
 * @param subtitle supporting text; rendered in `BodyBase onSurfaceMuted`,
 * centred.
 * @param modifier optional layout modifier applied to the empty-state root.
 * @param ctaLabel optional CTA button label; pair with [onCtaClick] to
 * render the button.
 * @param onCtaClick optional CTA click handler; ignored when [ctaLabel] is
 * `null`.
 * @param secondaryCtaLabel optional lower-emphasis second action, rendered as
 * a text button under the primary CTA; pair with [onSecondaryCtaClick].
 * @param onSecondaryCtaClick optional secondary click handler; ignored when
 * [secondaryCtaLabel] is `null`.
 * @param illustrationSize size of the default striped illustration slot.
 * @param illustration custom illustration slot; defaults to a
 * [StripedPlaceholder] sized to [illustrationSize].
 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    ctaLabel: String? = null,
    onCtaClick: (() -> Unit)? = null,
    secondaryCtaLabel: String? = null,
    onSecondaryCtaClick: (() -> Unit)? = null,
    illustrationSize: Dp = DefaultIllustrationSize,
    illustration: @Composable () -> Unit = {
        StripedPlaceholder(modifier = Modifier.size(illustrationSize))
    },
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier
            .fillMaxWidth()
            .padding(KnotworkTheme.spacing.sp6),
    ) {
        Box(contentAlignment = Alignment.Center) { illustration() }
        Text(
            text = title,
            style = KnotworkTextStyles.TitleLg,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        if (ctaLabel != null && onCtaClick != null) {
            KnotworkPrimaryButton(
                text = ctaLabel,
                onClick = onCtaClick,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2),
            )
        }
        // Lower-emphasis second way out, for empty states where two verbs are
        // both reasonable first moves (import a prompt someone sent you, or
        // write one). Rendered as a text button so the pair reads as
        // "this, or that" rather than as two equal buttons.
        if (secondaryCtaLabel != null && onSecondaryCtaClick != null) {
            KnotworkTextButton(text = secondaryCtaLabel, onClick = onSecondaryCtaClick)
        }
    }
}
