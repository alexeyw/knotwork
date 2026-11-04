package app.knotwork.design.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Knotwork interrupted-run card — status surface rendered inline in the chat
 * stream when the session's most recent pipeline run died with the process
 * (Doze, OOM kill, swipe from recents) instead of finishing:
 *
 *  - **Container.** The shared [RunStatusTile] chrome — rounded `surface1` tile
 *    with a muted outline, so the card reads as a system status, not an agent
 *    message. Unlike the cream clarification card or the risk-tinted HITL card,
 *    an interruption is neither a question nor a permission gate.
 *  - **Header row.** Hourglass glyph, followed by the bold "Run interrupted"
 *    label.
 *  - **Body.** "Execution was interrupted at node …" with the host-resolved
 *    node label.
 *  - **Action row.** Primary **Resume** CTA (continue from the last completed
 *    node) and a secondary **Discard** CTA (dismiss the run for good). When
 *    [InterruptedRunCardModel.resumable] is `false` the Resume CTA is hidden,
 *    an explanatory "can no longer be resumed" note is appended to the body,
 *    and Discard remains the only action.
 *
 * Stateless: both CTAs only dispatch the supplied callbacks; the host owns
 * the resume/discard semantics and removes the card by dropping the row from
 * the message list.
 *
 * @param model immutable card payload (resolved node label + resumability).
 * @param onResume invoked when the user taps the Resume CTA.
 * @param onDiscard invoked when the user taps the Discard CTA.
 * @param modifier optional layout modifier applied to the card root.
 */
@Composable
fun InterruptedRunCard(
    model: InterruptedRunCardModel,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RunStatusTile(
        icon = AppIcons.Hourglass,
        header = stringResource(R.string.knotwork_interrupted_run_header),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.knotwork_interrupted_run_body, model.nodeLabel),
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!model.resumable) {
            Text(
                text = stringResource(R.string.knotwork_interrupted_run_expired_note),
                style = KnotworkTextStyles.BodyBase,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
            if (model.resumable) {
                KnotworkPrimaryButton(
                    text = stringResource(R.string.knotwork_interrupted_run_resume),
                    onClick = onResume,
                )
            }
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_interrupted_run_discard),
                onClick = onDiscard,
            )
        }
    }
}
