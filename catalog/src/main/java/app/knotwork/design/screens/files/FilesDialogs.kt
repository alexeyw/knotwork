package app.knotwork.design.screens.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The Files screen's confirmation dialogs — the destructive delete confirmation
 * (single or bulk) and the import name-collision chooser. Split out of
 * `FilesContent` to keep each file's composable count manageable; both entry
 * points are `internal` so `FilesContent` can render them.
 */

/** Delete-confirmation dialog for one file or a bulk selection. */
@Composable
internal fun FilesDeleteDialog(view: DeleteDialogView, callbacks: FilesCallbacks) {
    val bulk = view.count > 1
    AlertDialog(
        onDismissRequest = callbacks.onDeleteCancel,
        icon = {
            Icon(
                imageVector = AppIcons.Trash,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalError,
            )
        },
        title = {
            Text(
                text = if (bulk) {
                    pluralStringResource(R.plurals.knotwork_files_delete_title_many, view.count, view.count)
                } else {
                    stringResource(R.string.knotwork_files_delete_title_one)
                },
                style = KnotworkTextStyles.TitleMd,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
                Text(
                    text = if (bulk) {
                        stringResource(R.string.knotwork_files_delete_body_many)
                    } else {
                        stringResource(R.string.knotwork_files_delete_body_one, view.names.firstOrNull().orEmpty())
                    },
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurface2,
                )
                if (bulk) {
                    DeleteNameList(names = view.names)
                }
            }
        },
        confirmButton = {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_files_delete_confirm),
                onClick = callbacks.onDeleteConfirm,
                destructive = true,
                leadingIcon = AppIcons.Trash,
            )
        },
        dismissButton = {
            KnotworkTextButton(
                text = stringResource(R.string.knotwork_files_delete_cancel),
                onClick = callbacks.onDeleteCancel,
            )
        },
    )
}

@Composable
private fun DeleteNameList(names: List<String>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = DELETE_LIST_MAX_HEIGHT)
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(width = 1.dp, color = KnotworkTheme.extended.divider, shape = KnotworkTheme.shapes.md)
            .verticalScroll(rememberScrollState())
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        names.forEach { name ->
            Text(
                text = name,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurface2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Import name-collision chooser: keep both / replace / cancel. */
@Composable
internal fun FilesCollisionDialog(view: CollisionView, callbacks: FilesCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onCollisionCancel,
        icon = {
            Icon(
                imageVector = AppIcons.File,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalWarn,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.knotwork_files_collision_title),
                style = KnotworkTextStyles.TitleMd,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
                Text(
                    text = stringResource(R.string.knotwork_files_collision_body, view.name),
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurface2,
                )
                CollisionOption(
                    icon = AppIcons.Copy,
                    title = stringResource(R.string.knotwork_files_collision_keep_both),
                    subtitle = stringResource(R.string.knotwork_files_collision_keep_both_sub, view.keepBothName),
                    primary = true,
                    onClick = callbacks.onCollisionKeepBoth,
                )
                CollisionOption(
                    icon = AppIcons.Refresh,
                    title = stringResource(R.string.knotwork_files_collision_replace),
                    subtitle = stringResource(R.string.knotwork_files_collision_replace_sub),
                    primary = false,
                    onClick = callbacks.onCollisionReplace,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            KnotworkTextButton(
                text = stringResource(R.string.knotwork_files_collision_cancel),
                onClick = callbacks.onCollisionCancel,
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollisionOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val container = if (primary) MaterialTheme.colorScheme.primaryContainer else KnotworkTheme.extended.surface1
    val titleColor = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val subColor = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else KnotworkTheme.extended.onSurfaceMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(container)
            .border(
                width = 1.dp,
                color = if (primary) Color.Transparent else KnotworkTheme.extended.divider,
                shape = KnotworkTheme.shapes.md,
            )
            .combinedClickable(onClick = onClick)
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = subColor, modifier = Modifier.size(ROW_ICON_SIZE))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = titleColor,
            )
            Text(text = subtitle, style = KnotworkTextStyles.MonoSm, color = subColor)
        }
    }
}

private val DELETE_LIST_MAX_HEIGHT = 120.dp
