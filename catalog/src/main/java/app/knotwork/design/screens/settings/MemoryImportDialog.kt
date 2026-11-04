@file:Suppress("MatchingDeclarationName") // File hosts MemoryImportDialog + its MemoryImportDialogUi payload.

package app.knotwork.design.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text

/** Gap between the two commit actions. */
private val ImportButtonGap = 8.dp

/**
 * Everything [MemoryImportDialog] renders.
 *
 * Warnings arrive already resolved and already filtered — whether a schema or a
 * provider mismatch applies is a judgement about the imported document, and the
 * catalog has no business making it.
 *
 * @property title Dialog title.
 * @property body The sentence stating what was parsed and how much of it.
 * @property warnings Zero or more caveats, each a finished sentence, shown under
 *   the body.
 * @property mergeLabel Keep what is there and add what is new.
 * @property replaceLabel Wipe first, then load. Styled as destructive.
 * @property cancelLabel Dismiss CTA.
 */
data class MemoryImportDialogUi(
    val title: String,
    val body: String,
    val warnings: List<String>,
    val mergeLabel: String,
    val replaceLabel: String,
    val cancelLabel: String,
)

/**
 * Strategy choice raised after a memory-import file parses.
 *
 * Two commit actions sit side by side in the confirm slot, which is unusual and
 * deliberate: merge and replace are peers, not a primary and a secondary, and
 * putting one of them in the dismiss slot would make "cancel" and "replace
 * everything" adjacent. Replace carries the error colour instead, because it is
 * the one that destroys data.
 *
 * @param ui Resolved copy and warnings.
 * @param onMerge Keep existing entries, skip duplicate ids.
 * @param onReplace Wipe, then load.
 * @param onCancel Import nothing.
 * @param modifier Optional layout modifier applied to the dialog.
 */
@Composable
fun MemoryImportDialog(
    ui: MemoryImportDialogUi,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = modifier,
        title = { Text(ui.title) },
        text = {
            Text(
                buildString {
                    append(ui.body)
                    ui.warnings.forEach {
                        append("\n\n")
                        append(it)
                    }
                },
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(ImportButtonGap)) {
                TextButton(onClick = onMerge) { Text(ui.mergeLabel) }
                TextButton(
                    onClick = onReplace,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(ui.replaceLabel)
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(ui.cancelLabel) } },
    )
}
