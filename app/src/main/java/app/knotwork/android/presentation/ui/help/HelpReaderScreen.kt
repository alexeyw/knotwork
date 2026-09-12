package app.knotwork.android.presentation.ui.help

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.presentation.ui.common.openDocumentationInBrowser
import app.knotwork.android.presentation.ui.common.openDocumentationUrl
import app.knotwork.android.presentation.ui.common.repositoryDocumentUrl
import app.knotwork.design.components.knotworkMarkdownColor
import app.knotwork.design.components.knotworkMarkdownTypography
import app.knotwork.design.screens.help.HelpReaderCallbacks
import app.knotwork.design.screens.help.HelpReaderContent
import app.knotwork.design.screens.help.HelpReaderViewState
import app.knotwork.design.screens.help.HelpReaderVisualState
import app.knotwork.design.theme.KnotworkTheme
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Reads one bundled document.
 *
 * Three things here are worth knowing before changing them.
 *
 * **Links are intercepted through `LocalUriHandler`.** The renderer has no
 * link callback of its own; it resolves a tap to
 * `LocalUriHandler.current.openUri(target)` with the destination *exactly as
 * the Markdown wrote it*, which is the form the generated index is keyed by.
 * Overriding the composition local is the interception point the library's
 * maintainer points to, and it needs no fork and no custom components.
 *
 * **The document is rendered into our own `LazyColumn`.** The library ships a
 * lazy renderer, but without a `LazyListState` parameter — and the state is
 * exactly what anchor navigation needs. `MarkdownElement` is public, so
 * rendering the parsed tree's top-level children ourselves costs a dozen lines
 * and buys scroll control. Virtualisation matters: the FAQ is 470 lines.
 *
 * **An anchor is a character offset, not a heading.** The build shipped
 * `anchor -> offset into the source`, and each rendered block knows its own
 * `startOffset`, so arriving at a heading is a search for the first block at or
 * past the offset. Nothing here knows what a heading is or how GitHub builds a
 * slug — which is the point, since both already have one owner in the build.
 *
 * @param onOpenDocument Navigates to another bundled document.
 * @param onBack Leaves the reader.
 * @param modifier Layout modifier applied to the screen.
 * @param viewModel Screen's view model.
 */
@Composable
fun HelpReaderScreen(
    onOpenDocument: (String, String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HelpReaderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val entry = DocumentationLinks.byId(viewModel.documentId)
    val listState = rememberLazyListState()
    val markdownState = rememberMarkdownState(content = uiState.markdown)
    val parseState by markdownState.state.collectAsStateWithLifecycle()
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()

    // The first scroll gesture dismisses the arrival mark. Read from the list's
    // own interaction rather than from a scroll callback so a fling counts once.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) viewModel.onScrolled() }
    }

    // Arrive at the anchor once the document has actually been parsed: before
    // that the list has no items to scroll to.
    LaunchedEffect(parseState, uiState.anchorOffset) {
        val success = parseState as? State.Success ?: return@LaunchedEffect
        val offset = uiState.anchorOffset ?: return@LaunchedEffect
        val index = success.node.children.indexOfFirst { it.startOffset >= offset }
        if (index >= 0) {
            if (reducedMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
        }
        viewModel.onAnchorConsumed()
    }

    val uriHandler = remember(viewModel, context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                when (val effect = viewModel.onLinkClick(uri, ::repositoryDocumentUrl)) {
                    is HelpReaderEffect.OpenDocument -> onOpenDocument(effect.id, effect.anchor)
                    is HelpReaderEffect.OpenBrowser -> openDocumentationUrl(context, effect.url)
                    is HelpReaderEffect.CopyLink -> context.copyDocumentationLink(effect.url)
                    null -> Unit
                }
            }
        }
    }

    HelpReaderContent(
        state = HelpReaderViewState(
            title = helpDocumentTitle(viewModel.documentId),
            fileName = entry?.path?.substringAfterLast('/').orEmpty(),
            markdown = uiState.markdown,
            visualState = when {
                uiState.failed -> HelpReaderVisualState.ERROR
                uiState.loading -> HelpReaderVisualState.LOADING
                else -> HelpReaderVisualState.CONTENT
            },
            anchorOffset = uiState.anchorOffset,
            anchorMarked = uiState.anchorMarked,
            offlineBarVisible = uiState.offlineUrl != null,
        ),
        strings = helpStrings(),
        callbacks = HelpReaderCallbacks(
            onBack = onBack,
            onOpenInBrowser = { openDocumentationInBrowser(context, viewModel.documentId) },
            onLinkClick = uriHandler::openUri,
            onRetry = viewModel::load,
            onScrolled = viewModel::onScrolled,
            onCopyLink = {
                uiState.offlineUrl?.let { context.copyDocumentationLink(it) }
                viewModel.dismissOfflineBar()
            },
            onDismissOfflineBar = viewModel::dismissOfflineBar,
        ),
        modifier = modifier,
    ) {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            Markdown(
                state = parseState,
                colors = knotworkMarkdownColor(),
                typography = knotworkMarkdownTypography(),
                success = { success, components, contentModifier ->
                    LazyColumn(
                        state = listState,
                        modifier = contentModifier,
                        contentPadding = PaddingValues(
                            horizontal = KnotworkTheme.spacing.sp4,
                            vertical = KnotworkTheme.spacing.sp3,
                        ),
                    ) {
                        items(
                            items = success.node.children,
                            // The offset is stable across recomposition and
                            // unique within a document, which is what the
                            // library's own lazy renderer keys by too.
                            key = { node -> node.startOffset },
                            contentType = { node -> node.type },
                        ) { node ->
                            MarkdownElement(node, components, success.content)
                        }
                    }
                },
            )
        }
    }
}
