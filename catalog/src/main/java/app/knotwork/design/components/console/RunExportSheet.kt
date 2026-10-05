package app.knotwork.design.components.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The trace export: the file's name and size in mono, what the file holds — every
 * prompt the model read, memory excerpts and chat history inside them — the
 * not-signed sentence, then Share and Save to file, as the journals offer them.
 * Nothing on this path reaches the network.
 *
 * @param ui The file.
 * @param onShare Hands the file to the share sheet.
 * @param onSave Saves it to a file the user picks.
 * @param onDismiss Closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunExportSheet(ui: RunExportUi, onShare: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        RunExportSheetContent(ui = ui, onShare = onShare, onSave = onSave, onClose = onDismiss)
    }
}

/**
 * The export sheet's body, apart from the sheet itself — what the snapshots capture.
 *
 * @param ui The file.
 * @param onShare Hands the file to the share sheet.
 * @param onSave Saves it to a file.
 * @param onClose Closes the sheet.
 * @param modifier Optional layout modifier.
 */
@Composable
fun RunExportSheetContent(
    ui: RunExportUi,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SheetHead(
            title = stringResource(R.string.knotwork_run_export_title),
            sub = stringResource(R.string.knotwork_run_export_file, ui.fileName, ui.size),
            onClose = onClose,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp5, vertical = KnotworkTheme.spacing.sp2),
        ) {
            Text(
                text = stringResource(R.string.knotwork_run_export_body),
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.knotwork_run_not_signed),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KnotworkTheme.spacing.sp5, vertical = KnotworkTheme.spacing.sp5),
        ) {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_run_export_share),
                onClick = onShare,
                leadingIcon = AppIcons.Share,
                modifier = Modifier.weight(1f),
            )
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_run_export_save),
                onClick = onSave,
                leadingIcon = AppIcons.Save,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
