package app.knotwork.design.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.delay

/**
 * The list of a server's model ids, full height over the provider form.
 *
 * Opened from the model field's *Choose* once a test has brought the list; closing it without a
 * pick leaves the field as it was. The field stays the value and stays free text — this only
 * fills it.
 *
 * @param state The ids and the current one.
 * @param onPick An id was picked; the sheet is closed by the caller.
 * @param onDismiss The sheet was closed without a pick.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(state: ModelSheetUi, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ModelPickerSheetContent(state = state, onPick = onPick, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The inside of [ModelPickerSheet]: title and count, a search field that takes the focus, the
 * number of matches, and one id per row.
 *
 * - A long list is searched, not scrolled: 412 OpenRouter ids are not swiped through.
 * - Each id shows its vendor prefix muted, so the model name is easy to find after a line break,
 *   and wraps rather than being cut — the end (`:free`, `-instruct`) is often the only difference.
 * - The number of matches is a polite live region, updated half a second after typing stops.
 *
 * @param state The ids and the current one.
 * @param onPick An id was picked.
 * @param modifier Layout modifier from the caller.
 * @param initialQuery The search text to start from; a snapshot sets it, a user starts empty.
 * @param focusSearch Whether the search field takes the focus on open.
 */
@Composable
fun ModelPickerSheetContent(
    state: ModelSheetUi,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
    focusSearch: Boolean = true,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val matches = remember(state.ids, query) {
        // A server may list an id twice; each id is one row and one list key.
        val ids = state.ids.distinct()
        val needle = query.trim()
        if (needle.isEmpty()) ids else ids.filter { it.contains(needle, ignoreCase = true) }
    }
    var announcedCount by remember { mutableIntStateOf(matches.size) }
    LaunchedEffect(matches.size) {
        delay(COUNT_ANNOUNCE_DELAY_MS)
        announcedCount = matches.size
    }
    Column(modifier = modifier.testTag(MODEL_SHEET_TEST_TAG)) {
        Column(modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp4)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.knotwork_model_sheet_title),
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Text(
                    text = stringResource(R.string.knotwork_model_sheet_count, state.ids.size, state.source),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            SearchField(
                query = query,
                onQueryChange = { query = it },
                focus = focusSearch,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp3),
            )
            Text(
                text = pluralStringResource(R.plurals.knotwork_model_sheet_matches, announcedCount, announcedCount),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier
                    .padding(vertical = KnotworkTheme.spacing.sp2)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            items(matches, key = { it }) { id ->
                ModelIdRow(id = id, selected = id == state.selected, onClick = { onPick(id) })
            }
        }
    }
}

/** The pill-shaped search field, focused on open so the keyboard is up at once. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, focus: Boolean, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    if (focus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        shape = KnotworkTheme.shapes.full,
        color = KnotworkTheme.extended.surface1,
        border = BorderStroke(SEARCH_BORDER, MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier
                .heightIn(min = MIN_TOUCH)
                .padding(horizontal = KnotworkTheme.spacing.sp3),
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(ICON_SIZE),
            )
            val hint = stringResource(R.string.knotwork_model_sheet_search)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = KnotworkTextStyles.MonoBase.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .testTag(MODEL_SHEET_SEARCH_TEST_TAG),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = hint,
                            style = KnotworkTextStyles.MonoBase,
                            color = KnotworkTheme.extended.onSurfaceMuted,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/** One id: vendor prefix muted, the rest in full ink, the current one ticked. */
@Composable
private fun ModelIdRow(id: String, selected: Boolean, onClick: () -> Unit) {
    val muted = KnotworkTheme.extended.onSurfaceMuted
    val ink = MaterialTheme.colorScheme.onSurface
    val slash = id.indexOf('/')
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp2)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(
                        alpha = SELECTED_ALPHA,
                    )
                } else {
                    Color.Transparent
                },
                shape = KnotworkTheme.shapes.md,
            )
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .heightIn(min = MIN_TOUCH)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = buildAnnotatedString {
                if (slash > 0) {
                    withStyle(SpanStyle(color = muted)) { append(id.take(slash + 1)) }
                    withStyle(SpanStyle(color = ink)) { append(id.drop(slash + 1)) }
                } else {
                    withStyle(SpanStyle(color = ink)) { append(id) }
                }
            },
            style = KnotworkTextStyles.MonoBase,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
    }
}

/** How long typing must pause before the number of matches is announced. */
private const val COUNT_ANNOUNCE_DELAY_MS = 500L

/** The share of the primary colour behind the current id. */
private const val SELECTED_ALPHA = 0.08f

private val SEARCH_BORDER = 2.dp
private val ICON_SIZE = 18.dp
private val MIN_TOUCH = 48.dp

/** Test tag of the model list. */
const val MODEL_SHEET_TEST_TAG: String = "model_sheet"

/** Test tag of the model list's search field. */
const val MODEL_SHEET_SEARCH_TEST_TAG: String = "model_sheet_search"
