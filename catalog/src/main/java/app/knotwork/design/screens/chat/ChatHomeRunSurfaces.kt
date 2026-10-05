package app.knotwork.design.screens.chat

import androidx.compose.runtime.Composable
import app.knotwork.design.components.console.RunAgainConfirmDialog
import app.knotwork.design.components.console.RunExportSheet
import app.knotwork.design.components.console.VerificationSheet
import app.knotwork.design.components.console.VerifyConfirmDialog

/**
 * The surfaces of the run the console shows: the check's sheet, the export sheet and
 * the two confirmations, each drawn while its state is set. They sit over the
 * console rather than inside it — the console is the record and stays dark; these
 * are separate acts on the app's surface.
 */
@Composable
internal fun ChatHomeRunSurfaces(run: ChatHomeRunState, callbacks: ChatHomeRunCallbacks) {
    run.verification?.let { ui ->
        VerificationSheet(
            ui = ui,
            onCancel = callbacks.onCancelVerification,
            onVerifyAgain = callbacks.onVerifyAgain,
            onCopyHash = callbacks.onCopyVerificationHash,
            onDismiss = callbacks.onCloseVerification,
        )
    }
    run.export?.let { ui ->
        RunExportSheet(
            ui = ui,
            onShare = callbacks.onShareExport,
            onSave = callbacks.onSaveExport,
            onDismiss = callbacks.onDismissExport,
        )
    }
    run.verifyConfirm?.let { ui ->
        VerifyConfirmDialog(
            ui = ui,
            onConfirm = callbacks.onConfirmVerify,
            onDismiss = callbacks.onDismissVerifyConfirm,
        )
    }
    run.runAgainConfirm?.let { ui ->
        RunAgainConfirmDialog(
            ui = ui,
            onConfirm = callbacks.onConfirmRunAgain,
            onDismiss = callbacks.onDismissRunAgainConfirm,
        )
    }
}
