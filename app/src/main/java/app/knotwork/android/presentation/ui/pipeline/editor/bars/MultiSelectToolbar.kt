package app.knotwork.android.presentation.ui.pipeline.editor.bars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.knotwork.android.R
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

private val BAR_HEIGHT = 48.dp

/**
 * Top toolbar that swaps in over the [app.knotwork.design.components.pipelineeditor.EditorToolbar]
 * while the user is in multi-select mode. Shows the selection count, a cancel
 * action, a Copy action, and a destructive Delete action.
 *
 * @param count number of selected node ids.
 * @param onCancel exits multi-select mode without acting.
 * @param onCopy snapshots the selected nodes into `EditorState.clipboard` and
 *   exits multi-select. Paste then becomes available from the overflow menu.
 * @param onDelete removes every selected node + their connections.
 * @param modifier optional layout modifier applied to the bar root.
 */
@Composable
internal fun MultiSelectToolbar(
    count: Int,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .background(KnotworkTheme.extended.surface2)
            .padding(horizontal = KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = AppIcons.X,
                    contentDescription = stringResource(R.string.pipeline_editor_multi_select_cancel),
                )
            }
            Text(
                text = pluralStringResource(
                    id = R.plurals.pipeline_editor_multi_select_count,
                    count = count,
                    count,
                ),
                style = KnotworkTextStyles.LabelMd,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = KnotworkTheme.spacing.sp2),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = AppIcons.Copy,
                    contentDescription = stringResource(R.string.pipeline_editor_multi_select_copy),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = AppIcons.Trash,
                    contentDescription = stringResource(R.string.pipeline_editor_multi_select_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
