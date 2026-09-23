package app.knotwork.design.components.console

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.a11y.alwaysDarkSurface
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/** Height of the console header — tab strip + trailing actions row. */
private val ConsoleHeaderHeight = 56.dp

/** Selected-tab underline thickness (used by Partial / Full headers only — Peek tabs are label-only). */
private val TabIndicatorHeight = 2.dp

/** Width of the leading accent strip on every log row. */
private val LogAccentStripWidth = 2.dp

/** Maximum length of the bar in a trace row, used to derive the per-row width. */
private val TraceBarMaxWidth = 160.dp

/** Opacity of inactive tab labels relative to `consoleFg`. */
private const val INACTIVE_TAB_ALPHA = 0.55f

/** Alpha applied to the source-filter chip when active. */
private const val SOURCE_CHIP_ON_ALPHA = 0.45f

/** Alpha applied to the source-filter chip when inactive. */
private const val SOURCE_CHIP_OFF_ALPHA = 0.10f

/** Height of one trace bar — thin sub-row beneath the row text. */
private val TraceBarHeight = 4.dp

/**
 * Leading indent applied per pipeline-nesting level across the Logs, Vars and
 * Traces tabs, so a sub-pipeline's rows read as nested under the row that
 * spawned them.
 */
private val ConsoleNestingIndent = 14.dp

/**
 * Knotwork agent console — bottom-sheet container with three snap points
 * (`Peek` / `Partial` / `Full`) and three tabs (`Logs` / `Vars` / `Traces`).
 *
 * The console is **always dark** — same `extended.consoleBg` /
 * `extended.consoleFg` in light and dark theme, so developer ergonomics
 * (terminal-like contrast) survive the system theme.
 *
 * Catalog API takes plain `List<…>` collections instead of `LazyPagingItems`
 * — paging is a screen-level concern that the catalog deliberately avoids
 * depending on. The screen layer wraps the catalog component with its own
 * pager / pull-loader machinery.
 *
 * Console pane surface.
 *
 * **Peek layout (44 dp budget).** The full Partial/Full header is 56 dp,
 * so Peek renders a *separate* compact header (8 dp drag handle + 18 dp
 * label-only tab strip + 16 dp ticker = 42 dp) that fits the 44 dp snap
 * and preserves the spec's "drag handle + tab strip + last-line ticker"
 * promise. The Search / Copy all / Clear / Close actions only appear in
 * Partial / Full where there is room for 48 dp icon buttons.
 *
 * **Header actions.** Every action button in the Partial/Full header
 * fires the matching caller-supplied callback ([onSearch], [onCopyAll],
 * [onClear], plus the close affordance which calls [onSnapChange] with
 * `ConsoleSnap.Peek`). A button is rendered only where it can act: Search
 * appears on the Logs tab alone, because the field it opens lives there.
 * Nothing is shown disabled and nothing is shown inert.
 *
 * @param snap current snap point. Drives the pane height and the
 * Peek-vs-full header switch.
 * @param onSnapChange invoked when the user requests a new snap (drag
 * handle tap cycles through snaps, Close header action collapses to Peek).
 * @param tab currently-selected tab.
 * @param onTabChange invoked when the user taps a different tab.
 * @param logs Logs-tab data.
 * @param vars Vars-tab data.
 * @param traces Traces-tab data.
 * @param filter source filter applied to [logs].
 * @param onFilterChange invoked when the user toggles a source filter.
 * @param onSearch invoked when the user taps the header Search action
 * (Partial / Full, **Logs tab only** — the inline field this opens exists
 * only there). Screens typically toggle an inline search bar via
 * [searchQuery].
 * @param onCopyAll invoked when the user taps the header Copy-all action.
 * @param onClear invoked when the user taps the header Clear action.
 * Screens typically confirm via a dialog before clearing the log.
 * @param searchQuery when non-null, an inline search field is rendered
 * above the Logs list and the query is applied as a case-insensitive
 * substring match on [ConsoleLine.text] (in addition to [filter]). `null`
 * hides the search bar entirely. Passing the empty string keeps the bar
 * visible but matches every line.
 * @param onSearchQueryChange invoked when the user edits the search query;
 * the host owns the value so it can persist across rotations and tabs.
 * @param onCopyVar invoked when the user picks `Copy value` from a Vars row's
 *   long-press menu. The Vars tab is where full node I/O is inspected, so the
 *   payload the host builds is the whole value — the alternative for anyone
 *   reporting a problem is a screenshot of a screenful of text.
 * @param onCopySpan invoked when the user picks `Copy span` from a Traces row's
 *   long-press menu.
 * @param onCopyLine invoked when the user picks `Copy line` from the
 * long-press menu on a [ConsoleLine] row.
 * @param onFilterByLineSource invoked when the user picks `Only show this
 * source` from the long-press menu — the host should narrow [filter] to a
 * single-source set.
 * @param modifier optional layout modifier applied to the pane root.
 */
@Composable
@Suppress("LongParameterList") // Stable public API.
fun ConsolePane(
    tab: ConsoleTab,
    onTabChange: (ConsoleTab) -> Unit,
    logs: List<ConsoleLine>,
    vars: List<ConsoleVarRow>,
    traces: List<ConsoleTraceSpan>,
    filter: ConsoleFilter,
    onFilterChange: (ConsoleFilter) -> Unit,
    onSearch: () -> Unit,
    onCopyAll: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
    onSearchQueryChange: (String) -> Unit = {},
    onCopyLine: (ConsoleLine) -> Unit = {},
    onCopyVar: (ConsoleVarRow) -> Unit = {},
    onCopySpan: (ConsoleTraceSpan) -> Unit = {},
    onFilterByLineSource: (ConsoleSource) -> Unit = {},
) {
    // Stateless content — the surrounding `ModalBottomSheet` owns the
    // sheet container (drag handle, anchored-draggable physics, snap
    // animations, dismiss). This composable just renders the
    // tab-strip-and-actions header + the active tab's body.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = KnotworkTheme.extended.consoleBg)
            .alwaysDarkSurface(),
    ) {
        ConsolePaneHeader(
            tab = tab,
            onTabChange = onTabChange,
            onSearch = onSearch,
            onCopyAll = onCopyAll,
            onClear = onClear,
        )
        when (tab) {
            ConsoleTab.Logs -> ConsoleLogsBody(
                logs = logs,
                filter = filter,
                onFilterChange = onFilterChange,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onCopyLine = onCopyLine,
                onFilterByLineSource = onFilterByLineSource,
            )
            ConsoleTab.Vars -> ConsoleVarsBody(rows = vars, onCopyVar = onCopyVar)
            ConsoleTab.Traces -> ConsoleTracesBody(spans = traces, onCopySpan = onCopySpan)
        }
    }
}

/** Tab strip + trailing actions row. */
@Composable
@Suppress("LongParameterList")
private fun ConsolePaneHeader(
    tab: ConsoleTab,
    onTabChange: (ConsoleTab) -> Unit,
    onSearch: () -> Unit,
    onCopyAll: () -> Unit,
    onClear: () -> Unit,
) {
    // The search field lives in `ConsoleLogsBody` and nowhere else, so the
    // header offers Search on the Logs tab only. Rendering it on Vars/Traces
    // put a magnifier on screen whose tap had no observable effect.
    val searchable = tab == ConsoleTab.Logs
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ConsoleHeaderHeight)
            .background(color = KnotworkTheme.extended.consoleBg),
    ) {
        FullTabStrip(tab = tab, onTabChange = onTabChange, modifier = Modifier.weight(1f))
        ConsoleActions(
            onSearch = onSearch.takeIf { searchable },
            onCopyAll = onCopyAll,
            onClear = onClear,
        )
    }
}

/**
 * Three-tab strip with a 2 dp accent underline on the selected tab. Each
 * tab takes an equal weighted share of the strip's allocated width so the
 * trailing [ConsoleActions] icons (at most 4 × 48 dp ≈ 192 dp on typical
 * phones — Search is Logs-only) always fit on the same row without pushing
 * TRACES off-screen.
 */
@Composable
private fun FullTabStrip(tab: ConsoleTab, onTabChange: (ConsoleTab) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxHeight()) {
        ConsoleTab.entries.forEach { entry ->
            val selected = entry == tab
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onTabChange(entry) },
            ) {
                Text(
                    text = entry.name,
                    style = KnotworkTextStyles.MonoBase,
                    color = if (selected) {
                        KnotworkTheme.extended.consoleFg
                    } else {
                        KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp1))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TabIndicatorHeight)
                        .background(
                            color = if (selected) KnotworkPalette.Accent400 else Color.Transparent,
                        ),
                )
            }
        }
    }
}

/**
 * Trailing action row: Search / Copy all / Clear / Close.
 *
 * A `null` [onSearch] drops the Search button rather than disabling it — the
 * Vars and Traces tabs have no search field to open.
 */
@Composable
private fun ConsoleActions(onSearch: (() -> Unit)?, onCopyAll: () -> Unit, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (onSearch != null) {
            ConsoleHeaderIcon(
                icon = AppIcons.Search,
                contentDescription = stringResource(R.string.knotwork_console_action_search),
                onClick = onSearch,
            )
        }
        ConsoleHeaderIcon(
            icon = AppIcons.Copy,
            contentDescription = stringResource(R.string.knotwork_console_action_copy_all),
            onClick = onCopyAll,
        )
        ConsoleHeaderIcon(
            icon = AppIcons.Trash,
            contentDescription = stringResource(R.string.knotwork_console_action_clear),
            onClick = onClear,
        )
        // No ✕ here. The strip above this row IS the console's header when the
        // sheet is open, and its chevron already closes it — two controls a
        // finger apart doing one thing is the duplication already removed
        // elsewhere ("one door, one verb"), reintroduced by proximity.
    }
}

/** One header trailing icon — uses console-foreground tint. */
@Composable
private fun ConsoleHeaderIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = KnotworkTheme.extended.consoleFg,
        )
    }
}

/**
 * Logs-tab body — optional inline search bar + filter chips + LazyColumn of
 * [ConsoleLineRow]s. The list is filtered first by [filter] (source set)
 * and then by [searchQuery] as a case-insensitive substring match on
 * [ConsoleLine.text]. An empty filtered result inside an active search
 * renders the localised "no matches" empty-state row.
 */
@Composable
@Suppress("LongParameterList") // Six knobs — collapsing into a config object would just shuffle the params.
private fun ConsoleLogsBody(
    logs: List<ConsoleLine>,
    filter: ConsoleFilter,
    onFilterChange: (ConsoleFilter) -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String) -> Unit,
    onCopyLine: (ConsoleLine) -> Unit,
    onFilterByLineSource: (ConsoleSource) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        if (searchQuery != null) {
            ConsoleSearchField(query = searchQuery, onQueryChange = onSearchQueryChange)
        }
        ConsoleSourceFilterRow(filter = filter, onFilterChange = onFilterChange)
        val needle = searchQuery?.trim().orEmpty()
        val visible = logs
            .asSequence()
            .filter(filter::matches)
            .filter { needle.isEmpty() || it.text.contains(needle, ignoreCase = true) }
            .toList()
        if (visible.isEmpty() && needle.isNotEmpty()) {
            ConsoleLogsEmptySearchRow()
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(items = visible) { line ->
                    ConsoleLineRow(
                        line = line,
                        onCopyLine = { onCopyLine(line) },
                        onFilterByLineSource = { onFilterByLineSource(line.source) },
                    )
                }
            }
        }
    }
}

/**
 * Single-line "no matches" placeholder shown when the search query filters
 * every otherwise-visible row out of the Logs list. Rendered with monospace
 * type so it sits comfortably amongst the log lines.
 */
@Composable
private fun ConsoleLogsEmptySearchRow() {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = stringResource(R.string.knotwork_console_empty_search),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA),
        )
    }
}

/**
 * Inline search field rendered above the source-filter row in
 * Partial / Full snaps. Uses [BasicTextField] so we can paint the text and
 * cursor directly with the console palette — the standard Material
 * `TextField` styling clashes with the always-dark surface.
 */
@Composable
private fun ConsoleSearchField(query: String, onQueryChange: (String) -> Unit) {
    val fieldCd = stringResource(R.string.knotwork_console_search_field_cd)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1)
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.consoleFg.copy(alpha = SOURCE_CHIP_OFF_ALPHA))
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Icon(
            imageVector = AppIcons.Search,
            contentDescription = null,
            tint = KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA),
            modifier = Modifier.size(SEARCH_ICON_SIZE),
        )
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = KnotworkTextStyles.MonoBase.copy(color = KnotworkTheme.extended.consoleFg),
                cursorBrush = SolidColor(KnotworkPalette.Accent400),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = fieldCd },
            )
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.knotwork_console_search_placeholder),
                    style = KnotworkTextStyles.MonoBase,
                    color = KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA),
                    maxLines = 1,
                )
            }
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(SEARCH_CLEAR_SIZE),
            ) {
                Icon(
                    imageVector = AppIcons.X,
                    contentDescription = stringResource(R.string.knotwork_console_search_clear_cd),
                    tint = KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA),
                    modifier = Modifier.size(SEARCH_ICON_SIZE),
                )
            }
        }
    }
}

/** Leading / trailing search-field icon size. */
private val SEARCH_ICON_SIZE = 16.dp

/** Touch target for the trailing clear-search button. */
private val SEARCH_CLEAR_SIZE = 24.dp

/** One filterable source chip. Toggles inclusion of `source` in [ConsoleFilter.sources]. */
@Composable
private fun ConsoleSourceFilterRow(filter: ConsoleFilter, onFilterChange: (ConsoleFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1),
    ) {
        ConsoleSource.entries.forEach { source ->
            val on = source in filter.sources
            Box(
                modifier = Modifier
                    .clip(KnotworkTheme.shapes.full)
                    .background(
                        color = if (on) {
                            KnotworkPalette.Accent400.copy(alpha = SOURCE_CHIP_ON_ALPHA)
                        } else {
                            KnotworkTheme.extended.consoleFg.copy(alpha = SOURCE_CHIP_OFF_ALPHA)
                        },
                    )
                    .clickable {
                        val next = filter.sources.toMutableSet().apply {
                            if (on) remove(source) else add(source)
                        }
                        onFilterChange(filter.copy(sources = next))
                    }
                    .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
            ) {
                Text(
                    text = source.name,
                    style = KnotworkTextStyles.LabelSm,
                    color = KnotworkTheme.extended.consoleFg,
                )
            }
        }
    }
}

/**
 * Single log row: leading accent strip + timestamp + source + text.
 *
 * Long-press offers two items — "Copy line" and "Only show this source" — that
 * delegate to the host via the supplied callbacks (the catalog never touches
 * the clipboard or mutates [ConsoleFilter] directly). The gesture itself lives
 * in [ConsoleRowWithMenu], shared with the Vars and Traces rows.
 */
@Composable
private fun ConsoleLineRow(line: ConsoleLine, onCopyLine: () -> Unit, onFilterByLineSource: () -> Unit) {
    ConsoleRowWithMenu(
        menu = { dismiss ->
            ConsoleMenuItem(
                label = stringResource(R.string.knotwork_console_line_copy),
                icon = AppIcons.Copy,
            ) {
                dismiss()
                onCopyLine()
            }
            ConsoleMenuItem(
                label = stringResource(R.string.knotwork_console_line_filter_only),
                icon = AppIcons.Filter,
            ) {
                dismiss()
                onFilterByLineSource()
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(
                modifier = Modifier
                    .width(LogAccentStripWidth)
                    .height(KnotworkTheme.spacing.sp6)
                    .background(color = levelAccent(line.level)),
            )
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = KnotworkTheme.spacing.sp3 + ConsoleNestingIndent * line.depth,
                        end = KnotworkTheme.spacing.sp3,
                        top = KnotworkTheme.spacing.sp1,
                        bottom = KnotworkTheme.spacing.sp1,
                    ),
            ) {
                Text(
                    text = line.timestamp,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA),
                )
                Text(
                    text = "[${line.source}]",
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkPalette.Accent300,
                )
                Text(
                    text = line.text,
                    style = KnotworkTextStyles.MonoBase,
                    color = KnotworkTheme.extended.consoleFg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Wraps [content] in the console's long-press menu affordance.
 *
 * One gesture for all three tabs: long-press opens an anchored [DropdownMenu],
 * a short tap does nothing — so a row cannot fire an action while the user is
 * scrolling past it. Every tab's rows go through here, which is what keeps the
 * gesture from being three near-copies that drift.
 *
 * @param menu The menu body, handed a `dismiss` callback to close the menu
 *   before running its action. The catalog never performs the action itself.
 * @param content The row to make actionable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleRowWithMenu(menu: @Composable (dismiss: () -> Unit) -> Unit, content: @Composable () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { /* No short-tap action on console rows. */ },
                    onLongClick = { menuExpanded = true },
                ),
        ) {
            content()
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            menu { menuExpanded = false }
        }
    }
}

/**
 * One item of a console row's long-press menu.
 *
 * @param label Item text.
 * @param icon Leading icon.
 * @param onClick Invoked after the menu has been dismissed.
 */
@Composable
private fun ConsoleMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Maps a [ConsoleLevel] to its leading-strip accent. */
@Composable
private fun levelAccent(level: ConsoleLevel): Color = when (level) {
    ConsoleLevel.Trace, ConsoleLevel.Info -> KnotworkTheme.extended.consoleFg.copy(alpha = SOURCE_CHIP_OFF_ALPHA)
    ConsoleLevel.Warn -> KnotworkTheme.extended.signalWarn
    ConsoleLevel.Error -> KnotworkTheme.extended.signalError
}

/**
 * Vars-tab body — section headers per node, then key/value rows.
 *
 * Each row carries the same long-press menu as a log row, for the same reason:
 * this tab renders full node input and output, and the only way to get a value
 * out of it used to be a screenshot.
 */
@Composable
private fun ConsoleVarsBody(rows: List<ConsoleVarRow>, onCopyVar: (ConsoleVarRow) -> Unit) {
    val groups = rows.groupBy { it.node }
    LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        groups.forEach { (node, entries) ->
            // A node group shares one nesting depth; indent the header and its
            // rows so a sub-pipeline's vars sit under the spawning node.
            val groupIndent = ConsoleNestingIndent * (entries.firstOrNull()?.depth ?: 0)
            item {
                Text(
                    text = node,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = KnotworkTheme.spacing.sp3 + groupIndent,
                            end = KnotworkTheme.spacing.sp3,
                            top = KnotworkTheme.spacing.sp1,
                            bottom = KnotworkTheme.spacing.sp1,
                        ),
                )
            }
            items(items = entries) { row ->
                ConsoleRowWithMenu(
                    menu = { dismiss ->
                        ConsoleMenuItem(
                            label = stringResource(R.string.knotwork_console_var_copy),
                            icon = AppIcons.Copy,
                        ) {
                            dismiss()
                            onCopyVar(row)
                        }
                    },
                ) {
                    // Key + value laid out as a Column so the value text wraps
                    // across as many lines as it needs. The Vars tab is the
                    // debugging surface for full node I/O — truncating with
                    // ellipsis discards the very data the user opened the
                    // pane to inspect. The outer LazyColumn already gives us
                    // vertical scrolling for free.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = KnotworkTheme.spacing.sp4 + groupIndent,
                                end = KnotworkTheme.spacing.sp4,
                                top = KnotworkTheme.spacing.sp1,
                                bottom = KnotworkTheme.spacing.sp1,
                            ),
                    ) {
                        Text(
                            text = row.key,
                            style = KnotworkTextStyles.MonoBase,
                            color = KnotworkPalette.Accent400,
                        )
                        Text(
                            text = row.valueJson,
                            style = KnotworkTextStyles.MonoBase,
                            color = KnotworkTheme.extended.consoleFg,
                        )
                    }
                }
            }
        }
    }
}

/** Traces-tab body — flat list with relative-duration bars. */
@Composable
private fun ConsoleTracesBody(spans: List<ConsoleTraceSpan>, onCopySpan: (ConsoleTraceSpan) -> Unit) {
    val maxDuration = spans.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L
    LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        items(items = spans) { span ->
            val fraction = span.durationMs.toFloat() / maxDuration.toFloat()
            ConsoleRowWithMenu(
                menu = { dismiss ->
                    ConsoleMenuItem(
                        label = stringResource(R.string.knotwork_console_span_copy),
                        icon = AppIcons.Copy,
                    ) {
                        dismiss()
                        onCopySpan(span)
                    }
                },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = KnotworkTheme.spacing.sp3 + ConsoleNestingIndent * span.depth,
                            end = KnotworkTheme.spacing.sp3,
                            top = KnotworkTheme.spacing.sp1,
                            bottom = KnotworkTheme.spacing.sp1,
                        ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = span.startedAt,
                            style = KnotworkTextStyles.MonoSm,
                            color = KnotworkTheme.extended.consoleFg.copy(alpha = INACTIVE_TAB_ALPHA),
                        )
                        Text(
                            text = span.name,
                            style = KnotworkTextStyles.MonoBase,
                            color = KnotworkTheme.extended.consoleFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${span.durationMs} ms",
                            style = KnotworkTextStyles.MonoSm,
                            color = traceStatusColor(span.status),
                        )
                    }
                    Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp1))
                    Box(
                        modifier = Modifier
                            .height(TraceBarHeight)
                            .width(TraceBarMaxWidth * fraction)
                            .background(color = traceStatusColor(span.status)),
                    )
                }
            }
        }
    }
}

/** Maps a [SpanStatus] to a colour used by both the bar and the duration label. */
@Composable
private fun traceStatusColor(status: SpanStatus): Color = when (status) {
    SpanStatus.Ok -> KnotworkTheme.extended.signalSuccess
    SpanStatus.Error -> KnotworkTheme.extended.signalError
}
