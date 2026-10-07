package app.knotwork.android.presentation.ui.pipeline.editor.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeTypeMapper
import app.knotwork.design.components.pipelineeditor.NodeTypePickerSheetBody
import app.knotwork.design.theme.KnotworkTheme
import kotlinx.coroutines.launch

private val HANDLE_WIDTH = 36.dp
private val HANDLE_HEIGHT = 4.dp

/**
 * The pipeline editor's "Add node" sheet: the catalog's [NodeTypePickerSheetBody]
 * inside a full-height [ModalBottomSheet].
 *
 * It replaces the radial menu the editor used to open, whose overlapping
 * labels a tester could not read. Both ways in stay as they were — the `+`
 * button and a long-press on empty canvas — and the caller decides where the
 * picked node lands.
 *
 * What the host owns, and the body cannot:
 * - **Full height, no half state** (`skipPartiallyExpanded`): a search field,
 *   fourteen rows and a keyboard do not fit a partial sheet.
 * - **The search text**, kept only while the sheet is open, so it opens empty
 *   and at the top every time; it survives a rotation while open.
 * - **One sheet at a time.** A pick first hides this sheet and only then
 *   reports the type, so the node's configuration sheet, which the caller
 *   opens next, never rises over a picker still on screen. Close does the same.
 * - **One answer per opening.** Only a hide that finishes reports its
 *   answer. A newer pick or Close cancels the hide under way, and so does a
 *   touch that catches the moving sheet; the cancelled answer is dropped,
 *   because the sheet is still visible when its hide ends.
 * - **A drag handle that is not a TalkBack stop.** The Material handle is a
 *   focusable control; Close and Back already dismiss, so the handle here is
 *   drawn as a mark only. The sheet still drags by its whole surface.
 *
 * Under reduced motion ("Remove animations"), Compose runs the sheet's own
 * slide at zero duration: it appears and leaves at once.
 *
 * @param onPick Invoked with the picked type once the sheet has hidden.
 * @param onDismiss Invoked when the sheet closes without a pick — Close,
 *   Back, a scrim tap or a drag down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeTypePickerSheet(onPick: (NodeType) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val hideThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) action()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null,
    ) {
        DragHandleMark()
        NodeTypePickerSheetBody(
            query = query,
            onQueryChange = { query = it },
            onPick = { type -> hideThen { onPick(NodeTypeMapper.toDomain(type)) } },
            onDismiss = { hideThen(onDismiss) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The sheet's drag handle as a visual mark only: no semantics, no click. */
@Composable
private fun ColumnScope.DragHandleMark() {
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .padding(top = KnotworkTheme.spacing.sp2)
            .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
            .background(
                color = KnotworkTheme.extended.outlineStrong,
                shape = RoundedCornerShape(HANDLE_HEIGHT / 2),
            ),
    )
}
