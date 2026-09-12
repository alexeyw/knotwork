package app.knotwork.design.screens.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
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
    Column(modifier = modifier.fillMaxSize().testTag(HELP_READER_TEST_TAG)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
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
