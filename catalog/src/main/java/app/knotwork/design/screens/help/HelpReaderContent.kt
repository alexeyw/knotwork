package app.knotwork.design.screens.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkIconButton
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The reader's chrome, with the document itself supplied by the caller.
 *
 * The body is a slot on purpose: the catalog carries no Markdown dependency,
 * and the document's typography is not designed here at all — it is
 * `knotworkMarkdownTypography()`, shared with the chat bubble, so that a
 * paragraph reads identically wherever the app shows Markdown. What *is*
 * designed here is everything around it.
 *
 * @param state What to draw.
 * @param strings The screen's copy.
 * @param callbacks What the chrome raises.
 * @param modifier Layout modifier applied to the reader.
 * @param body The parsed document, drawn by the app.
 */
@Composable
fun HelpReaderContent(
    state: HelpReaderViewState,
    strings: HelpStrings,
    callbacks: HelpReaderCallbacks,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag(HELP_READER_TEST_TAG),
    ) {
        HelpReaderBar(state = state, strings = strings, callbacks = callbacks)
        Box(modifier = Modifier.weight(1f)) {
            when (state.visualState) {
                HelpReaderVisualState.LOADING -> HelpReaderSkeleton(strings = strings)
                HelpReaderVisualState.CONTENT -> body()
                HelpReaderVisualState.ERROR -> HelpReaderError(strings = strings, callbacks = callbacks)
            }
        }
        if (state.offlineBarVisible) {
            HelpOfflineBar(strings = strings, callbacks = callbacks)
        }
    }
}

/**
 * The reader's top bar: back, the document's name, and one action.
 *
 * The title is deliberately **not** marked as a heading. The document's own H1
 * is the first heading on the screen, and two competing headings at the top of
 * a heading-navigable document is the one thing that breaks a screen reader's
 * rotor. Traversal order is the declaration order — back, title, action — so
 * nothing needs re-ordering by index.
 *
 * @param state What to draw.
 * @param strings The screen's copy.
 * @param callbacks What the bar raises.
 */
@Composable
private fun HelpReaderBar(state: HelpReaderViewState, strings: HelpStrings, callbacks: HelpReaderCallbacks) {
    app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // See the note on the list's bar: the shell scaffold zeroes its
                // content insets, and this bar is hand-laid-out rather than an
                // M3 `TopAppBar`, so the status-bar inset is ours to apply.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            app.knotwork.design.components.buttons.KnotworkIconButton(
                icon = AppIcons.Back,
                contentDescription = strings.backDescription,
                onClick = callbacks.onBack,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = state.title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    // Two lines rather than an ellipsis: at 200 % font scale a
                    // document's name is the only thing telling the reader
                    // which document they are in.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.fileName,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            app.knotwork.design.components.buttons.KnotworkIconButton(
                icon = AppIcons.External,
                contentDescription = strings.readerAction,
                onClick = callbacks.onOpenInBrowser,
            )
        }
    }
}

/**
 * The loading frame: the document's own shape, being filled in.
 *
 * Not a centred spinner on an empty screen. The document's name is already
 * known when the parse starts, so the honest picture is the text arriving — and
 * because the skeleton uses the document's own metrics, nothing moves when the
 * real lines replace it.
 *
 * @param strings The screen's copy.
 */
@Composable
private fun HelpReaderSkeleton(strings: HelpStrings) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .testTag(HELP_READER_SKELETON_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        SkeletonBar(widthFraction = 0.62f, height = TitleBar)
        SkeletonBar(widthFraction = 1f, height = TextBar)
        SkeletonBar(widthFraction = 1f, height = TextBar)
        SkeletonBar(widthFraction = 0.74f, height = TextBar)
        SkeletonBar(widthFraction = 0.44f, height = HeadingBar)
        SkeletonBar(widthFraction = 1f, height = TextBar)
        SkeletonBar(widthFraction = 0.88f, height = TextBar)
        Text(
            text = strings.loadingNote,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.padding(top = KnotworkTheme.spacing.sp3),
        )
    }
}

/**
 * One placeholder line of the loading frame.
 *
 * @param widthFraction Share of the width this line occupies.
 * @param height The line's height, in the document's own metrics.
 */
@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(KnotworkTheme.shapes.sm)
            .background(KnotworkTheme.extended.surface2)
            // The note below the skeleton speaks for the whole frame.
            .clearAndSetSemantics { },
    )
}

/**
 * The unreadable-copy frame.
 *
 * The body names the fix before it offers the fallback, so the browser is not
 * presented as the answer to a broken install — it is the second-best answer,
 * and it needs a network the reader may not have.
 *
 * @param strings The screen's copy.
 * @param callbacks What its actions raise.
 */
@Composable
private fun HelpReaderError(strings: HelpStrings, callbacks: HelpReaderCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(KnotworkTheme.spacing.sp5)
            .testTag(HELP_READER_ERROR_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = AppIcons.AlertCircle,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(ErrorGlyph),
        )
        Text(
            text = strings.errorTitle,
            style = KnotworkTextStyles.TitleLg,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = strings.errorBody,
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurface2,
        )
        // A FlowRow, not a Row: at 200 % font scale the two labels do not fit
        // one line, and a Row gives the whole overflow to the second button —
        // "Try again" rendered as "T...". The same fix the run-limits rows use
        // for the same reason.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            KnotworkPrimaryButton(
                text = strings.errorPrimary,
                onClick = callbacks.onOpenInBrowser,
                size = KnotworkButtonSize.Md,
            )
            KnotworkSecondaryButton(
                text = strings.errorSecondary,
                onClick = callbacks.onRetry,
                size = KnotworkButtonSize.Md,
            )
        }
    }
}

/**
 * The in-body refusal: a bar pinned to the bottom of the reader.
 *
 * Bottom-anchored because the one thing a reader eleven screens into the
 * troubleshooting guide cannot afford to lose is their scroll position, and
 * this takes none of it. Not a snackbar either — it persists until dismissed,
 * because the person who hit it is reading, not watching.
 *
 * @param strings The screen's copy.
 * @param callbacks What its actions raise.
 * @param modifier Layout modifier applied to the bar.
 */
@Composable
fun HelpOfflineBar(strings: HelpStrings, callbacks: HelpReaderCallbacks, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(KnotworkTheme.spacing.sp3)
            .clip(KnotworkTheme.shapes.sm)
            .background(KnotworkTheme.extended.surface3)
            .padding(KnotworkTheme.spacing.sp3)
            .testTag(HELP_OFFLINE_BAR_TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Text(
            text = strings.offlineBarText,
            style = KnotworkTextStyles.BodySm,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = strings.offlineBarCopy,
            style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(KnotworkTheme.shapes.sm)
                .clickable(onClick = callbacks.onCopyLink)
                .padding(KnotworkTheme.spacing.sp1),
        )
        // The bar persists until dismissed — it is not a snackbar, because the
        // reader who hit it is reading rather than watching. That makes an
        // explicit dismissal necessary: without one the only way to clear it
        // would be to copy a link you may not want.
        KnotworkIconButton(
            icon = AppIcons.X,
            contentDescription = strings.dismissDescription,
            onClick = callbacks.onDismissOfflineBar,
        )
    }
}

/**
 * The mark left on the heading an anchor landed on.
 *
 * It persists until the first scroll rather than pulsing, because a pulse is
 * over before the eye arrives and, under reduced motion, is no signal at all —
 * which would leave the one reader who most needs orientation with none.
 *
 * @param note The mark's one word.
 * @param modifier Layout modifier applied to the mark.
 * @param content The heading being marked.
 */
@Composable
fun HelpAnchorMark(note: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier.testTag(HELP_ANCHOR_MARK_TEST_TAG)) {
        Text(
            text = note,
            style = KnotworkTextStyles.MonoSm,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = KnotworkTheme.spacing.sp2),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(width = AnchorEdge, height = AnchorEdgeHeight)
                    .background(MaterialTheme.colorScheme.primary)
                    // Decoration: the note above it carries the meaning.
                    .clearAndSetSemantics { },
            )
            Box(modifier = Modifier.padding(start = KnotworkTheme.spacing.sp2)) { content() }
        }
    }
}

/** Width of the accent edge beside an arrived-at heading. */
private val AnchorEdge = 2.dp

/** Height of that edge — a heading's line, not the block's. */
private val AnchorEdgeHeight = 28.dp

/** Test tag of the in-body offline bar. */
const val HELP_OFFLINE_BAR_TEST_TAG: String = "help_offline_bar"

/** Test tag of the arrival mark. */
const val HELP_ANCHOR_MARK_TEST_TAG: String = "help_anchor_mark"

/** Height of the skeleton's title line. */
private val TitleBar = 26.dp

/** Height of a skeleton body line. */
private val TextBar = 13.dp

/** Height of a skeleton section heading. */
private val HeadingBar = 18.dp

/** The error frame's glyph. */
private val ErrorGlyph = 26.dp

/** Test tag of the reader. */
const val HELP_READER_TEST_TAG: String = "help_reader"

/** Test tag of the loading frame. */
const val HELP_READER_SKELETON_TEST_TAG: String = "help_reader_skeleton"

/** Test tag of the unreadable-copy frame. */
const val HELP_READER_ERROR_TEST_TAG: String = "help_reader_error"
