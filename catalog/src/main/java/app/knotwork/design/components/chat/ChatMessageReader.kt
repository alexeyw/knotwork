package app.knotwork.design.components.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.KnotworkFullScreenDialog
import app.knotwork.design.components.KnotworkTableHeader
import app.knotwork.design.components.KnotworkTableRow
import app.knotwork.design.components.buttons.KnotworkIconButton
import app.knotwork.design.components.knotworkMarkdownColor
import app.knotwork.design.components.knotworkMarkdownComponents
import app.knotwork.design.components.knotworkMarkdownTypography
import app.knotwork.design.components.misc.KnotworkLoader
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.components.text.Text
import app.knotwork.design.components.text.WholeText
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * How long the reader may take to prepare the rest of a message before the end of the
 * list says so; a shorter wait draws nothing, so nothing flashes.
 */
private const val PREPARING_DELAY_MS = 300L

/** Key of the list's last item while the rest of the message is being prepared. */
private const val PREPARING_KEY = "preparing"

/** Vertical space around a code block, as the renderer's own code block draws it. */
private val CodeBlockVerticalPadding = 8.dp

/**
 * Where the reader parses a message and cuts it into items: off the main thread. Tests
 * provide a dispatcher that holds a step back, to see the reader before it is done.
 */
internal val LocalChatReaderWork = staticCompositionLocalOf<CoroutineDispatcher> { Dispatchers.Default }

/** Test tag of the reader's list. */
const val CHAT_MESSAGE_READER_LIST_TAG: String = "chat_message_reader_list"

/**
 * What the message reader shows.
 *
 * @property role who wrote the message: [ChatRole.User] titles the reader *Your
 *   message*; any other role *Agent’s message*.
 * @property subtitle the line under the title — the message's time and, when the
 *   message names one, its model, joined by the host; empty for none.
 * @property text the whole message, as its source.
 * @property markdown whether [text] is Markdown, as the bubble draws it; plain text
 *   otherwise.
 */
@Immutable
data class ChatMessageReaderState(val role: ChatRole, val subtitle: String, val text: String, val markdown: Boolean)

/**
 * The whole of one long chat message, full screen over the chat, in the app's theme.
 *
 * A bar with Back, the reader's title over [ChatMessageReaderState.subtitle], and Copy;
 * then the message in a lazy list, drawn as the help reader draws a document: one item
 * per block, at the chat bubble's typography. No item holds more than a bounded amount
 * of text ([ChatReaderItem]): a paragraph with a long run without whitespace is drawn
 * as its pieces, a long code block as chunks of [READER_CODE_CHUNK_LINES] lines on one
 * continuous surface, a long table as chunks of [READER_TABLE_CHUNK_ROWS] rows that
 * scroll sideways together, a plain-text message one item per line. Once the whole
 * message is ready, one more than five screens tall gets a scroll thumb
 * ([ChatReaderScrollThumb]) that names the section it is dragged to.
 *
 * The message is parsed and cut into items off the main thread. A message longer than
 * [READER_HEAD_CHARS] is prepared in two steps — its head, then the whole — so its
 * beginning is drawn first; when the reader reaches the end of what is ready before
 * the rest is, the end of the list says *Preparing the rest…* after
 * [PREPARING_DELAY_MS]. The rest arrives in place: the list keeps its position.
 *
 * It opens, closes and follows a predictive back gesture as a
 * [KnotworkFullScreenDialog] does, over the chat; the chat keeps its scroll.
 *
 * Copy calls [onCopy], which copies the whole source; when the host reports that it did
 * not fit the clipboard, the reader says so in its own snackbar.
 *
 * @param state the message the reader was opened on.
 * @param onClose called once the closing motion has ended.
 * @param onCopy copies the whole message; returns `false` when it was too large for
 *   the clipboard and nothing was copied.
 */
@Composable
fun ChatMessageReader(state: ChatMessageReaderState, onClose: () -> Unit, onCopy: () -> Boolean) {
    val darkBackground = MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE
    KnotworkFullScreenDialog(onClose = onClose, darkBackground = darkBackground) { close, modifier ->
        ReaderSurface(state = state, onBack = close, onCopy = onCopy, modifier = modifier)
    }
}

/** Below this luminance the surface counts as dark and the system bars draw light icons. */
private const val HALF_LUMINANCE = 0.5f

/** The reader's opaque surface: bar and body, with the snackbar host over its foot. */
@Composable
private fun ReaderSurface(
    state: ChatMessageReaderState,
    onBack: () -> Unit,
    onCopy: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tooLong = stringResource(R.string.knotwork_copy_too_long)
    val title = when (state.role) {
        ChatRole.User -> stringResource(R.string.knotwork_chat_long_reader_user)
        else -> stringResource(R.string.knotwork_chat_long_reader_agent)
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .semantics { paneTitle = title },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReaderBar(
                title = title,
                subtitle = state.subtitle,
                onBack = onBack,
                onCopy = {
                    if (!onCopy()) scope.launch { snackbarHostState.showSnackbar(tooLong) }
                },
            )
            if (state.markdown) MarkdownBody(state.text) else PlainBody(state.text)
        }
        KnotworkSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

/**
 * Back, the title over the subtitle, and Copy — the help reader's bar. The title and
 * subtitle are one stop for TalkBack, between Back and Copy.
 */
@Composable
private fun ReaderBar(title: String, subtitle: String, onBack: () -> Unit, onCopy: () -> Unit) {
    KnotworkTopAppBarShell {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KnotworkIconButton(
                icon = AppIcons.Back,
                contentDescription = stringResource(R.string.knotwork_common_back),
                onClick = onBack,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = KnotworkTheme.spacing.sp2)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    text = title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            KnotworkIconButton(
                icon = AppIcons.Copy,
                contentDescription = stringResource(R.string.knotwork_chat_message_action_copy),
                onClick = onCopy,
            )
        }
    }
}

/**
 * A Markdown message: parsed off the main thread — its head first when it is longer
 * than [READER_HEAD_CHARS], then the whole — and drawn inside the renderer's
 * composition locals, which the items need.
 */
@Composable
private fun MarkdownBody(text: String) {
    val listState = rememberLazyListState()
    val links = remember(text) { ReferenceLinkHandlerImpl() }
    val work = LocalChatReaderWork.current
    val parse by produceState(initialValue = ReaderParse(State.Loading(links), whole = false), text) {
        if (text.length > READER_HEAD_CHARS) {
            val head = readerHead(text)
            value = ReaderParse(withContext(work) { parseMarkdown(head, referenceLinkHandler = links) })
        }
        value = ReaderParse(withContext(work) { parseMarkdown(text, referenceLinkHandler = links) }, whole = true)
    }
    Markdown(
        state = parse.state,
        colors = knotworkMarkdownColor(),
        typography = knotworkMarkdownTypography(),
        components = remember { knotworkMarkdownComponents() },
        loading = { ReaderList(listState = listState, items = emptyList(), complete = false) },
        success = { success, components, _ ->
            MarkdownItems(listState = listState, parsed = success, whole = parse.whole, components = components)
        },
        // The parser does not fail on any text; should it, the message still reads as plain text.
        error = { PlainBody(text) },
    )
}

/**
 * One step of a Markdown message's parse.
 *
 * @property state the parse of the head or of the whole message.
 * @property whole whether [state] is the parse of the whole message.
 */
private class ReaderParse(val state: State, val whole: Boolean = false)

/**
 * The items of one parse step, cut off the main thread. Until the items of a new step
 * are ready, those of the previous one stay on screen.
 */
@Composable
private fun MarkdownItems(
    listState: LazyListState,
    parsed: State.Success,
    whole: Boolean,
    components: MarkdownComponents,
) {
    val settings by rememberUpdatedState(annotatorSettings())
    val paragraphStyle = LocalMarkdownTypography.current.paragraph
    val work = LocalChatReaderWork.current
    val cut by produceState<ReaderCut?>(initialValue = null, parsed) {
        value = withContext(work) {
            val items = markdownReaderItems(parsed, settings, paragraphStyle, head = !whole)
            ReaderCut(parsed, items, readerHeadings(items, parsed.content))
        }
    }
    val current = cut
    ReaderList(
        listState = listState,
        items = current?.items.orEmpty(),
        complete = whole && current?.parsed === parsed,
        content = current?.parsed?.content.orEmpty(),
        components = components,
        headings = current?.headings.orEmpty(),
    )
}

/**
 * The items cut from one parse.
 *
 * @property parsed the parse they were cut from; their nodes index into its content.
 * @property items the items.
 * @property headings the heading each item stands under, for the scroll thumb.
 */
private class ReaderCut(val parsed: State.Success, val items: List<ChatReaderItem>, val headings: List<String?>)

/** A plain-text message: cut into lines off the main thread. */
@Composable
private fun PlainBody(text: String) {
    val work = LocalChatReaderWork.current
    val items by produceState<List<ChatReaderItem>?>(initialValue = null, text) {
        value = withContext(work) { plainReaderItems(text) }
    }
    val ready = items
    ReaderList(
        listState = rememberLazyListState(),
        items = ready.orEmpty(),
        complete = ready != null,
        // Plain text has no headings: its thumb names the share.
        headings = remember(ready) { ready?.let { List(it.size) { null } }.orEmpty() },
    )
}

/**
 * The reader's list: the items, then — while the rest of the message is being prepared
 * — the row that says so.
 *
 * @param listState the list's scroll state, kept across parse steps.
 * @param items the items ready so far.
 * @param complete whether [items] are the whole message.
 * @param content the source a [ChatReaderItem.Block]'s node indexes into.
 * @param components the renderer's components, for blocks; unused by a plain message.
 * @param headings the heading each item stands under, for the scroll thumb, which shows
 *   once [complete].
 */
@Composable
private fun ReaderList(
    listState: LazyListState,
    items: List<ChatReaderItem>,
    complete: Boolean,
    content: String = "",
    components: MarkdownComponents? = null,
    headings: List<String?> = emptyList(),
) {
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // One sideways scroll per long table, shared by its chunks: they are the same width,
    // so their columns stay in line however far it is scrolled.
    val tableScrolls = remember { HashMap<Int, ScrollState>() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        ReaderLazyList(
            listState = listState,
            items = items,
            complete = complete,
            content = content,
            components = components,
            navigationBar = navigationBar,
            tableScrolls = tableScrolls,
        )
        if (complete && headings.size == items.size) {
            ChatReaderScrollThumb(
                listState = listState,
                items = items,
                headings = headings,
                viewportPx = constraints.maxHeight,
            )
        }
    }
}

/** The lazy list itself: the items, then — while the rest is being prepared — the row that says so. */
@Composable
private fun ReaderLazyList(
    listState: LazyListState,
    items: List<ChatReaderItem>,
    complete: Boolean,
    content: String,
    components: MarkdownComponents?,
    navigationBar: Dp,
    tableScrolls: HashMap<Int, ScrollState>,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = KnotworkTheme.spacing.sp4,
            end = KnotworkTheme.spacing.sp4,
            top = KnotworkTheme.spacing.sp4,
            bottom = KnotworkTheme.spacing.sp6 + navigationBar,
        ),
        modifier = Modifier.fillMaxSize().testTag(CHAT_MESSAGE_READER_LIST_TAG),
    ) {
        items(items = items, key = { it.key }, contentType = { it.contentType }) { item ->
            when (item) {
                is ChatReaderItem.Block -> MarkdownElement(item.node, checkNotNull(components), content)
                is ChatReaderItem.ParagraphPiece -> ReaderParagraphPiece(item, content)
                is ChatReaderItem.CodeChunk -> ReaderCodeChunk(item)
                is ChatReaderItem.TableChunk -> ReaderTableChunk(
                    chunk = item,
                    content = content,
                    scroll = tableScrolls.getOrPut(item.table.startOffset) { ScrollState(0) },
                )
                // The reader shows a message whole: a plain-text line holds any length of prose.
                is ChatReaderItem.PlainText -> WholeText(
                    text = item.text,
                    style = KnotworkTextStyles.BodyBase,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!complete) item(key = PREPARING_KEY) { PreparingRow() }
    }
}

/** One piece of a paragraph; the first takes the space the renderer puts before a block. */
@Composable
private fun ReaderParagraphPiece(piece: ChatReaderItem.ParagraphPiece, content: String) {
    Column {
        if (piece.index == 0) Spacer(Modifier.height(LocalMarkdownPadding.current.block))
        MarkdownText(
            content = piece.text,
            node = piece.node,
            style = LocalMarkdownTypography.current.paragraph,
            sourceContent = content,
        )
    }
}

/**
 * One chunk of a long code block, drawn as the renderer draws a code block — its
 * background, padding and sideways scroll — with corners and vertical padding only
 * where the block begins or ends, so that its chunks read as one surface. Each chunk
 * scrolls sideways on its own. The first carries the block's description for TalkBack.
 */
@Composable
private fun ReaderCodeChunk(chunk: ChatReaderItem.CodeChunk) {
    val corner = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val top = if (chunk.first) corner else 0.dp
    val bottom = if (chunk.last) corner else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    val inner = LocalMarkdownPadding.current.codeBlock
    val direction = LocalLayoutDirection.current
    val labels = LocalMarkdownA11yLabels.current
    val code = remember(chunk.code) { AnnotatedString(chunk.code) }
    Column {
        if (chunk.first) Spacer(Modifier.height(LocalMarkdownPadding.current.block))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = if (chunk.first) CodeBlockVerticalPadding else 0.dp,
                    bottom = if (chunk.last) CodeBlockVerticalPadding else 0.dp,
                )
                .background(color = LocalMarkdownColors.current.codeBackground, shape = shape)
                .clip(shape)
                .then(
                    if (chunk.first) {
                        Modifier.semantics {
                            isTraversalGroup = true
                            contentDescription = chunk.language?.takeIf { it.isNotBlank() }
                                ?.let(labels.codeBlockWithLanguage) ?: labels.codeBlock
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            MarkdownBasicText(
                text = code,
                style = LocalMarkdownTypography.current.code.copy(lineHeightStyle = chunkLineHeight(chunk)),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = inner.calculateStartPadding(direction),
                        end = inner.calculateEndPadding(direction),
                        top = if (chunk.first) inner.calculateTopPadding() else 0.dp,
                        bottom = if (chunk.last) inner.calculateBottomPadding() else 0.dp,
                    ),
            )
        }
    }
}

/**
 * One chunk of a long table, drawn as the renderer draws a table — its background, the
 * header with its rule on the first chunk, rows of equal-width columns that scroll
 * sideways together when the table is wider than the screen — with corners only where
 * the table begins or ends, so its chunks read as one table.
 *
 * @param chunk the chunk.
 * @param content the source the table's node indexes into.
 * @param scroll the table's sideways scroll, shared by all its chunks.
 */
@Composable
private fun ReaderTableChunk(chunk: ChatReaderItem.TableChunk, content: String, scroll: ScrollState) {
    val dimens = LocalMarkdownDimens.current
    val top = if (chunk.first) dimens.tableCornerSize else 0.dp
    val bottom = if (chunk.last) dimens.tableCornerSize else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    val header = remember(chunk.table) { chunk.table.findChildOfType(GFMElementTypes.HEADER) }
    val columns = remember(header) { header?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0 }
    val rowCount = remember(chunk.table) { chunk.table.children.count { it.type == GFMElementTypes.ROW } + 1 }
    val tableWidth = dimens.tableCellWidth * columns
    val style = LocalMarkdownTypography.current.table
    Column {
        if (chunk.first) Spacer(Modifier.height(LocalMarkdownPadding.current.block))
        BoxWithConstraints(
            modifier = Modifier
                .background(LocalMarkdownColors.current.tableBackground, shape)
                .widthIn(max = dimens.tableMaxWidth)
                .semantics { collectionInfo = CollectionInfo(rowCount = rowCount, columnCount = columns) },
        ) {
            val scrollable = maxWidth <= tableWidth
            Column(
                modifier = if (scrollable) {
                    Modifier.horizontalScroll(scroll).requiredWidth(tableWidth)
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                if (chunk.first && header != null) {
                    KnotworkTableHeader(content = content, header = header, tableWidth = tableWidth, style = style)
                    MarkdownDivider()
                }
                chunk.rows.forEachIndexed { i, row ->
                    KnotworkTableRow(
                        content = content,
                        row = row,
                        tableWidth = tableWidth,
                        style = style,
                        rowIndex = chunk.firstRow + i,
                    )
                }
            }
        }
    }
}

/**
 * A text trims the space its line height adds above its first line and below its last.
 * Between two chunks of one block that would close the gap between their lines, so a
 * chunk trims only where the block begins or ends.
 */
private fun chunkLineHeight(chunk: ChatReaderItem.CodeChunk): LineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Proportional,
    trim = when {
        chunk.first -> LineHeightStyle.Trim.FirstLineTop
        chunk.last -> LineHeightStyle.Trim.LastLineBottom
        else -> LineHeightStyle.Trim.None
    },
)

/**
 * The end of the list while the rest is being prepared: its height from the start, so a
 * reader already at the end sees it fill in; nothing in it for [PREPARING_DELAY_MS], then
 * the loader and *Preparing the rest…*, said once to TalkBack.
 */
@Composable
private fun PreparingRow() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PREPARING_DELAY_MS)
        show = true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp3)
            .heightIn(min = KnotworkTheme.spacing.sp12),
    ) {
        if (show) {
            // The text says what is happening; the loader's own "Loading" would say it twice.
            KnotworkLoader(modifier = Modifier.clearAndSetSemantics { })
            Text(
                text = stringResource(R.string.knotwork_chat_long_preparing),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
