package app.knotwork.design.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * Two-option voice-input source chooser shown as a [ModalBottomSheet]: **Record
 * voice** (mic) and **Choose audio file** (`audio/` document). Mirrors the image
 * [SourceChooserSheet] vocabulary — large 56 dp rows that survive font-scale
 * 200% and TalkBack — with a subtitle under each option.
 *
 * **Stateless** — the caller owns visibility (renders this only while open) and
 * the recorder / picker launches; this composable just surfaces the two choices.
 *
 * @param maxDurationSec the recording limit in seconds; rendered as a `m:ss`
 *  clock in the "Record voice" subtitle.
 * @param onDismiss invoked when the sheet is dismissed without a choice.
 * @param onPickRecord invoked when the user chooses to record.
 * @param onPickFile invoked when the user chooses to pick an audio file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSourceChooserSheet(
    maxDurationSec: Int,
    onDismiss: () -> Unit,
    onPickRecord: () -> Unit,
    onPickFile: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AudioSourceChooserSheetContent(
            maxDurationSec = maxDurationSec,
            onPickRecord = onPickRecord,
            onPickFile = onPickFile,
        )
    }
}

/**
 * The chooser body (rows) without the sheet chrome. Split out so it can be
 * previewed / snapshot-tested directly.
 *
 * @param maxDurationSec the recording limit in seconds, shown as `m:ss` in the
 *  record subtitle.
 * @param onPickRecord invoked when the user chooses to record.
 * @param onPickFile invoked when the user chooses to pick an audio file.
 * @param modifier layout modifier applied to the rows column.
 */
@Composable
fun AudioSourceChooserSheetContent(
    maxDurationSec: Int,
    onPickRecord: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = stringResource(R.string.knotwork_audio_source_title),
            style = KnotworkTextStyles.TitleMd,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp2,
            ),
        )
        AudioSourceRow(
            icon = AppIcons.Mic,
            label = stringResource(R.string.knotwork_audio_source_record),
            subtitle = stringResource(R.string.knotwork_audio_source_record_sub, formatClock(maxDurationSec)),
            onClick = onPickRecord,
        )
        AudioSourceRow(
            icon = AppIcons.FileAudio,
            label = stringResource(R.string.knotwork_audio_source_file),
            subtitle = stringResource(R.string.knotwork_audio_source_file_sub),
            onClick = onPickFile,
        )
    }
}

/** A single 56 dp source row: tonal icon tile + label over a muted subtitle. */
@Composable
private fun AudioSourceRow(icon: ImageVector, label: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SOURCE_ROW_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp2,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SOURCE_TILE_SIZE)
                .clip(KnotworkTheme.shapes.md)
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(SOURCE_TILE_ICON_SIZE),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            Text(
                text = label,
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

private val SOURCE_ROW_HEIGHT = 56.dp
private val SOURCE_TILE_SIZE = 44.dp
private val SOURCE_TILE_ICON_SIZE = 22.dp
