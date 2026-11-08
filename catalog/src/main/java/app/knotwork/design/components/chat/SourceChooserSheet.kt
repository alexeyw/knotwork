package app.knotwork.design.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Two-option image-source chooser shown as a [ModalBottomSheet]: **Photo
 * library** (gallery / screenshots) and **Camera**. The sheet form gives large
 * touch targets that survive font-scale 200% and TalkBack, matching the app's
 * existing bottom-sheet vocabulary.
 *
 * **Stateless** — the caller owns visibility (renders this only while open) and
 * the picker launches; this composable just surfaces the two choices and the
 * dismissal.
 *
 * @param onDismiss invoked when the sheet is dismissed without a choice.
 * @param onPickPhotoLibrary invoked when the user chooses the photo library.
 * @param onPickCamera invoked when the user chooses the camera.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceChooserSheet(onDismiss: () -> Unit, onPickPhotoLibrary: () -> Unit, onPickCamera: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        SourceChooserSheetContent(
            onPickPhotoLibrary = onPickPhotoLibrary,
            onPickCamera = onPickCamera,
        )
    }
}

/**
 * The chooser body (rows) without the sheet chrome. Split out so it can be
 * previewed / snapshot-tested directly (a [ModalBottomSheet] is awkward to
 * render in a static snapshot).
 *
 * @param onPickPhotoLibrary invoked when the user chooses the photo library.
 * @param onPickCamera invoked when the user chooses the camera.
 * @param modifier layout modifier applied to the rows column.
 */
@Composable
fun SourceChooserSheetContent(
    onPickPhotoLibrary: () -> Unit,
    onPickCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = stringResource(R.string.knotwork_source_title),
            style = KnotworkTextStyles.TitleMd,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp2,
            ),
        )
        SourceRow(
            icon = AppIcons.Image,
            label = stringResource(R.string.knotwork_source_photo_library),
            onClick = onPickPhotoLibrary,
        )
        SourceRow(
            icon = AppIcons.Camera,
            label = stringResource(R.string.knotwork_source_camera),
            onClick = onPickCamera,
        )
    }
}

/** A single 56 dp source row: tonal icon tile + label. */
@Composable
private fun SourceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .height(SOURCE_ROW_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SOURCE_TILE_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(SOURCE_TILE_ICON_SIZE),
            )
        }
        Text(
            text = label,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private val SOURCE_ROW_HEIGHT = 56.dp
private val SOURCE_TILE_SIZE = 40.dp
private val SOURCE_TILE_ICON_SIZE = 22.dp
