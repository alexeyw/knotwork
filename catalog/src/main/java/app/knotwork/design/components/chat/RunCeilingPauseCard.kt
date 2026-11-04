package app.knotwork.design.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Knotwork run-ceiling pause card — status surface rendered inline in the chat
 * stream when a run has spent one of the limits the user configured for it and
 * is waiting to be told whether it may carry on:
 *
 *  - **Container.** The shared [RunStatusTile] chrome, identical to the
 *    interrupted-run card beside it. Both are the app reporting on the run's
 *    lifecycle, and they must read as one family rather than as two unrelated
 *    interruptions.
 *  - **Header row.** Hourglass glyph and the "paused at a limit" label. The
 *    same glyph as the interrupted card carries, and deliberately: an hourglass
 *    means *this run is not moving*, which is exactly what both tiles report.
 *    Giving the pause its own icon would imply a difference in kind where the
 *    difference is only in cause.
 *  - **Body.** What continuing costs, then the numbers on their own muted line.
 *  - **Action row.** Primary CTA granting one more portion — labelled with the
 *    size of that portion — and a secondary CTA stopping the run.
 *
 * The affirmative CTA is primary even though it spends more of the user's
 * budget. The alternative reading, that stopping is the safe default and should
 * lead, gets the situation backwards: the run is already paused, so doing
 * nothing already *is* stopping, and the card exists precisely because the user
 * may want the other thing.
 *
 * Stateless: both CTAs only dispatch the supplied callbacks; the host owns the
 * grant / stop semantics and removes the card by dropping the row from the
 * message list.
 *
 * @param model immutable card payload (resolved copy, numbers and CTA labels).
 * @param onContinue invoked when the user taps the continue CTA.
 * @param onStop invoked when the user taps the stop CTA.
 * @param modifier optional layout modifier applied to the card root.
 */
@Composable
fun RunCeilingPauseCard(
    model: RunCeilingPauseCardModel,
    onContinue: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RunStatusTile(icon = AppIcons.Hourglass, header = model.title, modifier = modifier) {
        Text(
            text = model.body,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = model.meter,
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
            KnotworkPrimaryButton(text = model.continueLabel, onClick = onContinue)
            KnotworkSecondaryButton(text = model.stopLabel, onClick = onStop)
        }
    }
}
