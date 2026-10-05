package app.knotwork.design.components.console

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.components.dialogs.ConfirmDialog
import app.knotwork.design.components.dialogs.ConfirmDialogUi
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The confirmation before a long check: how many re-runs, from what, with which
 * seed, compared how, and that tools are not run again; then the estimate in mono
 * and that the model is busy until it finishes.
 *
 * @param ui What to say.
 * @param onConfirm Starts the check.
 * @param onDismiss Cancel, or the scrim.
 */
@Composable
fun VerifyConfirmDialog(ui: VerifyConfirmUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        ui = ConfirmDialogUi(
            title = stringResource(R.string.knotwork_run_verify_confirm_title),
            body = "",
            confirmLabel = stringResource(R.string.knotwork_run_verify_confirm_ok),
            cancelLabel = stringResource(R.string.knotwork_run_dialog_cancel),
        ),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    ) {
        val body = pluralStringResource(R.plurals.knotwork_run_verify_confirm_body, ui.calls, ui.calls, ui.seed)
        val cloud = if (ui.cloudCalls > 0) {
            " " + pluralStringResource(R.plurals.knotwork_run_verify_confirm_cloud, ui.cloudCalls, ui.cloudCalls)
        } else {
            ""
        }
        Text(text = body + cloud, style = KnotworkTextStyles.BodyBase)
        ui.estimate?.let {
            Text(
                text = stringResource(R.string.knotwork_run_verify_confirm_time, it),
                style = KnotworkTextStyles.MonoBase,
            )
        }
        Text(
            text = stringResource(R.string.knotwork_run_verify_confirm_busy),
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}
