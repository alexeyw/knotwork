package app.knotwork.design.screens.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.settings.KnotworkHintLink
import app.knotwork.design.screens.settings.KnotworkHintPanel
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The Help list: every document the app can open, marked by where it is read
 * from.
 *
 * **One list, and the mark is on the row.** Sections titled "opens in browser"
 * would sort the documents by where their bytes live — the one property nobody
 * opens this screen to compare — and would split "I broke something" from "how
 * do I start" on a screen whose entire job is routing. The distinction still
 * has to be shown, so it is shown twice over on the row itself: a glyph inline
 * after the label, and the delivery word first in the mono slot. The same mark
 * means the same thing inside a document body, where no section header reaches.
 *
 * @param state What to draw.
 * @param strings The screen's copy.
 * @param callbacks What the rows raise.
 * @param modifier Layout modifier applied to the list.
 */
@Composable
fun HelpListContent(
    state: HelpListViewState,
    strings: HelpStrings,
    callbacks: HelpListCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize().testTag(HELP_LIST_TEST_TAG)) {
        items(items = state.documents, key = { it.id }) { document ->
            if (document != state.documents.first()) {
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
            HelpDocumentRow(
                document = document,
                strings = strings,
                onClick = { callbacks.onOpen(document.id) },
            )
            if (state.refusedDocumentId == document.id && state.refusal != null) {
                HelpRefusalPanel(refusal = state.refusal, callbacks = callbacks)
            }
        }
    }
}

/**
 * One document row.
 *
 * The trailing column holds a chevron, never the delivery glyph: a trailing
 * column of five identical glyphs is exactly the stripe the navigation work
 * removed, and it would put the meaning furthest from the label it qualifies.
 *
 * Accessibility collapses the row to a **single** focusable target announcing
 * name → what happens on tap → size → what is inside. Three stops would triple
 * the swipes needed to reach the second document and none of them would do
 * anything different.
 *
 * @param document The row's document.
 * @param strings The screen's copy.
 * @param onClick Raised when the row is tapped.
 */
@Composable
private fun HelpDocumentRow(document: HelpDocument, strings: HelpStrings, onClick: () -> Unit) {
    val remote = document.delivery == HelpDelivery.IN_BROWSER
    val announcement = buildString {
        append(document.label)
        append(". ")
        if (remote) {
            append(strings.opensInBrowser)
            append(". ")
        }
        append(document.state)
        append(". ")
        append(document.description)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .semantics(mergeDescendants = true) { contentDescription = announcement }
            .testTag(helpRowTestTag(document.id)),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            ) {
                Text(
                    text = document.label,
                    style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (remote) {
                    Icon(
                        imageVector = AppIcons.External,
                        // The row's own announcement already says it, and a
                        // second description would make the screen reader say
                        // it twice.
                        contentDescription = null,
                        tint = KnotworkTheme.extended.onSurfaceMuted,
                        modifier = Modifier.size(DeliveryGlyph),
                    )
                }
            }
            Text(
                text = document.description,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            )
            Text(
                text = document.state,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            )
        }
        Icon(
            imageVector = AppIcons.ArrowR,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier
                .padding(top = KnotworkTheme.spacing.sp1)
                .size(ChevronGlyph)
                .clearAndSetSemantics { },
        )
    }
}

/**
 * The offline refusal, opened under the row it belongs to.
 *
 * Not a dialog. A dialog would be a second thing to dismiss before the reader
 * can reach the two documents that *do* open — and those stay visible and
 * tappable underneath this panel, which is the whole argument for putting the
 * refusal here rather than over the screen.
 *
 * @param refusal The refusal's copy.
 * @param callbacks What its actions raise.
 */
@Composable
private fun HelpRefusalPanel(refusal: HelpRefusal, callbacks: HelpListCallbacks) {
    KnotworkHintPanel(
        visible = true,
        title = refusal.title,
        text = refusal.body,
        actions = listOfNotNull(
            KnotworkHintLink(label = refusal.copyLabel, onClick = callbacks.onCopyLink),
            refusal.alternativeLabel?.let {
                KnotworkHintLink(label = it, onClick = callbacks.onOpenAlternative)
            },
        ),
        modifier = Modifier
            .padding(horizontal = KnotworkTheme.spacing.sp4)
            .padding(bottom = KnotworkTheme.spacing.sp2)
            .testTag(HELP_REFUSAL_TEST_TAG),
    )
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

/** The inline glyph marking a row that leaves the app. */
private val DeliveryGlyph = 14.dp

/** The trailing chevron. */
private val ChevronGlyph = 18.dp

/** Test tag of the Help list. */
const val HELP_LIST_TEST_TAG: String = "help_list"

/** Test tag of the open offline refusal panel. */
const val HELP_REFUSAL_TEST_TAG: String = "help_refusal"

/** Test tag of the in-body offline bar. */
const val HELP_OFFLINE_BAR_TEST_TAG: String = "help_offline_bar"

/** Test tag of the arrival mark. */
const val HELP_ANCHOR_MARK_TEST_TAG: String = "help_anchor_mark"

/**
 * Test tag of one document row.
 *
 * @param id The document's registry id.
 * @return The row's tag.
 */
fun helpRowTestTag(id: String): String = "help_row_$id"
