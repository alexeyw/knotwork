package app.knotwork.design.components.pipelineeditor

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.lists.KnotworkSectionHeader
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import kotlinx.coroutines.delay

/** Test tag on the body's root. */
const val NODE_TYPE_PICKER_TEST_TAG: String = "node_type_picker"

/** Test tag on the search field's text input. */
const val NODE_TYPE_PICKER_SEARCH_TEST_TAG: String = "node_type_picker_search"

/** Test tag prefix of a type's row; the [NodeType] name follows it. */
const val NODE_TYPE_PICKER_ROW_TEST_TAG_PREFIX: String = "node_type_picker_row_"

/** How long typing must pause before TalkBack is told how many types match. */
private const val RESULTS_ANNOUNCE_DELAY_MS = 1_000L

// Filtering motion, as the design specifies it; none at all under reduced motion.
private const val ROW_FADE_OUT_MS = 120
private const val ROW_MOVE_MS = 240
private const val ROW_FADE_IN_MS = 160

private val TILE_SIZE = 40.dp
private val GLYPH_SIZE = 22.dp
private val ROW_MIN_HEIGHT = 64.dp
private val ROW_GAP = 14.dp
private val SEARCH_ICON_SIZE = 20.dp
private val SEARCH_BORDER = 1.dp
private val SEARCH_BORDER_FOCUSED = 2.dp
private val NAME_TO_DESCRIPTION = 2.dp

/**
 * The body of an "Add node" sheet for the pipeline editor: every node type, grouped
 * by what it is for, each with its name and one-line description, and a
 * search over them.
 *
 * **Body, not sheet.** The host owns the `ModalBottomSheet` around it — scrim,
 * drag handle, back and dismiss — because a sheet does not lay out under
 * Robolectric and the body is what the baselines photograph. The body fills
 * the height it is given; the host shows it full height.
 *
 * **State is hoisted.** [query] belongs to the host so a baseline can pin any
 * search without typing; the host keeps it only while the sheet is open, so
 * the sheet opens empty and at the top every time.
 *
 * What it guarantees:
 * - **Nothing is cut.** Names and descriptions wrap at any font scale; the
 *   list scrolls under a header and search field that stay put.
 * - **One TalkBack stop per row** — name and description read together, the
 *   tile decorative, the action announced as [R.string.knotwork_node_picker_a11y_row_action].
 *   Group titles are headings, so heading navigation jumps group to group.
 * - **The count of matches is told once typing pauses**, as the search
 *   field's state description, not as a hidden node: a hidden live region
 *   would be a TalkBack stop nobody can see.
 * - **The keyboard never picks.** The search action only hides it; a type is
 *   added by tapping its row.
 *
 * @param query The search field's text.
 * @param onQueryChange The text was edited, or cleared.
 * @param onPick A row was tapped: add a node of this type.
 * @param onDismiss The header's close icon was tapped.
 * @param modifier Layout modifier applied to the body's root.
 */
@Composable
fun NodeTypePickerSheetBody(
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (NodeType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = NodeType.entries.associateWith { type ->
        NodeTypeSearch.Entry(
            type = type,
            name = stringResource(type.text.name),
            description = stringResource(type.text.description),
        )
    }
    val sections = remember(query, entries) { NodeTypeSearch.filter(query) { entries.getValue(it) } }
    val title = stringResource(R.string.knotwork_node_picker_title)
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val clear: () -> Unit = {
        onQueryChange("")
        // Clearing keeps the user in the field, ready to type the next query.
        focusRequester.requestFocus()
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { paneTitle = title }
            .testTag(NODE_TYPE_PICKER_TEST_TAG),
    ) {
        PickerHeader(title = title, onDismiss = onDismiss)
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            onClear = clear,
            resultsDescription = resultsDescription(query, sections.sumOf { it.entries.size }),
            focusRequester = focusRequester,
            modifier = Modifier.padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp1,
                bottom = KnotworkTheme.spacing.sp2,
            ),
        )
        // The hairline appears only once rows have scrolled under the search field.
        HorizontalDivider(
            thickness = SEARCH_BORDER,
            color = if (listState.canScrollBackward) KnotworkTheme.extended.divider else Color.Transparent,
        )
        if (sections.isEmpty()) {
            NoMatch(query = query, onClear = clear)
        } else {
            TypeList(sections = sections, listState = listState, onPick = onPick)
        }
    }
}

/**
 * What TalkBack is told about the matches once typing pauses: the count, or
 * the no-match line. `null` while the field is empty or typing has not paused.
 */
@Composable
private fun resultsDescription(query: String, count: Int): String? {
    var settled by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(query, count) {
        settled = null
        if (query.isBlank()) return@LaunchedEffect
        delay(RESULTS_ANNOUNCE_DELAY_MS)
        settled = query to count
    }
    val (settledQuery, settledCount) = settled ?: return null
    return if (settledCount == 0) {
        stringResource(R.string.knotwork_node_picker_no_match, settledQuery)
    } else {
        pluralStringResource(R.plurals.knotwork_node_picker_a11y_results, settledCount, settledCount)
    }
}

/** Title and close icon. TalkBack reads the title first, then Close. */
@Composable
private fun PickerHeader(title: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KnotworkTheme.spacing.sp5, end = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = title,
            style = KnotworkTextStyles.TitleLg,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = stringResource(R.string.knotwork_node_picker_close),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The pill search field: outlined, primary when focused, a labelled 48 dp
 * Clear only while there is text. Not focused on open — the keyboard would
 * cover the list most people only need to read.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    resultsDescription: String?,
    focusRequester: FocusRequester,
    modifier: Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val hint = stringResource(R.string.knotwork_node_picker_search_hint)
    Surface(
        shape = KnotworkTheme.shapes.full,
        color = KnotworkTheme.extended.surface2,
        border = if (focused) {
            BorderStroke(SEARCH_BORDER_FOCUSED, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(SEARCH_BORDER, MaterialTheme.colorScheme.outline)
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier
                .heightIn(min = KnotworkTheme.spacing.sp12)
                // With text, the 48 dp Clear button supplies the end inset itself.
                .padding(
                    start = KnotworkTheme.spacing.sp3,
                    end = if (query.isEmpty()) KnotworkTheme.spacing.sp3 else 0.dp,
                ),
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.size(SEARCH_ICON_SIZE),
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = KnotworkTextStyles.BodyBase.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                interactionSource = interactionSource,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .semantics {
                        contentDescription = hint
                        if (resultsDescription != null) stateDescription = resultsDescription
                    }
                    .testTag(NODE_TYPE_PICKER_SEARCH_TEST_TAG),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = hint,
                            style = KnotworkTextStyles.BodyBase,
                            color = KnotworkTheme.extended.onSurfaceMuted,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = AppIcons.X,
                        contentDescription = stringResource(R.string.knotwork_node_picker_search_clear),
                        tint = KnotworkTheme.extended.onSurface2,
                        modifier = Modifier.size(SEARCH_ICON_SIZE),
                    )
                }
            }
        }
    }
}

/** The grouped rows. Rows fade and slide as the query changes, unless motion is reduced. */
@Composable
private fun TypeList(sections: List<NodeTypeSearch.Section>, listState: LazyListState, onPick: (NodeType) -> Unit) {
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val easing = KnotworkTheme.motion.easeStd
    val actionLabel = stringResource(R.string.knotwork_node_picker_a11y_row_action)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp6),
        modifier = Modifier.fillMaxSize(),
    ) {
        sections.forEach { section ->
            item(key = "group:${section.group.name}", contentType = "heading") {
                KnotworkSectionHeader(
                    // Capitals are the section-header convention (the Tools screen's
                    // "BUILT-IN TOOLS"); the resource keeps sentence case for translation.
                    title = stringResource(section.group.title).uppercase(),
                    modifier = if (reducedMotion) {
                        Modifier
                    } else {
                        Modifier.animateItem(
                            fadeInSpec = tween(ROW_FADE_IN_MS),
                            placementSpec = tween(ROW_MOVE_MS, easing = easing),
                            fadeOutSpec = tween(ROW_FADE_OUT_MS),
                        )
                    },
                )
            }
            items(section.entries, key = { it.type.name }, contentType = { "row" }) { entry ->
                TypeRow(
                    entry = entry,
                    actionLabel = actionLabel,
                    onPick = onPick,
                    modifier = if (reducedMotion) {
                        Modifier
                    } else {
                        Modifier.animateItem(
                            fadeInSpec = tween(ROW_FADE_IN_MS),
                            placementSpec = tween(ROW_MOVE_MS, easing = easing),
                            fadeOutSpec = tween(ROW_FADE_OUT_MS),
                        )
                    },
                )
            }
        }
    }
}

/**
 * One node type: its tinted tile with the card's glyph, its name and its
 * description. The whole row is the target; nothing trails it.
 */
@Composable
private fun TypeRow(entry: NodeTypeSearch.Entry, actionLabel: String, onPick: (NodeType) -> Unit, modifier: Modifier) {
    val tint = entry.type.headerTint()
    val onTint = headerOnColor(tint)
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ROW_GAP),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            // `clickable` merges the row's texts into one TalkBack stop; the
            // label tells what double-tapping does.
            .clickable(onClickLabel = actionLabel, role = Role.Button) { onPick(entry.type) }
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .testTag(NODE_TYPE_PICKER_ROW_TEST_TAG_PREFIX + entry.type.name),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TILE_SIZE)
                .background(color = tint, shape = KnotworkTheme.shapes.sm),
        ) {
            Icon(
                imageVector = entry.type.glyph(),
                contentDescription = null,
                tint = onTint,
                modifier = Modifier.size(GLYPH_SIZE),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = entry.description,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.padding(top = NAME_TO_DESCRIPTION),
            )
        }
    }
}

/** Nothing matched: the query, quoted, and a way back to every type. */
@Composable
private fun NoMatch(query: String, onClear: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = KnotworkTheme.spacing.sp6,
                end = KnotworkTheme.spacing.sp6,
                top = KnotworkTheme.spacing.sp8,
            ),
    ) {
        Text(
            text = stringResource(R.string.knotwork_node_picker_no_match, query),
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurface2,
            textAlign = TextAlign.Center,
        )
        KnotworkTextButton(
            text = stringResource(R.string.knotwork_node_picker_search_clear),
            onClick = onClear,
            modifier = Modifier.heightIn(min = KnotworkTheme.spacing.sp12),
        )
    }
}
