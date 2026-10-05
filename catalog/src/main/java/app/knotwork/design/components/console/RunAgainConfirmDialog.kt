package app.knotwork.design.components.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.dialogs.ConfirmDialog
import app.knotwork.design.components.dialogs.ConfirmDialogUi
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Size of the info glyph of the run-again inset. */
private val InsetGlyph = 16.dp

/**
 * The confirmation before starting a run again with its seed. It says this is a new
 * run on today's inputs — memory, chat history and tool results read again, tools
 * run again with the usual approvals — and sets the promise apart in an inset,
 * with the sampler in mono under it.
 *
 * @param ui What to say.
 * @param onConfirm Starts the run.
 * @param onDismiss Cancel, or the scrim.
 */
@Composable
fun RunAgainConfirmDialog(ui: RunAgainConfirmUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        ui = ConfirmDialogUi(
            title = stringResource(R.string.knotwork_run_seed_confirm_title, ui.seed),
            body = "",
            confirmLabel = stringResource(R.string.knotwork_run_seed_confirm_ok),
            cancelLabel = stringResource(R.string.knotwork_run_dialog_cancel),
        ),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        Text(
            text = stringResource(R.string.knotwork_run_seed_confirm_body, ui.pipeline),
            style = KnotworkTextStyles.BodyBase,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .clip(KnotworkTheme.shapes.sm)
                .background(KnotworkTheme.extended.surface3)
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        ) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.size(InsetGlyph),
            )
            Text(text = stringResource(R.string.knotwork_run_action_seed_line), style = KnotworkTextStyles.MonoSm)
        }
        Text(
            text = stringResource(R.string.knotwork_run_sampler_value, ui.temperature, ui.topK, ui.topP),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}
