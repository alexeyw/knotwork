package app.knotwork.design.screens.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
    // The surface is painted here rather than left to whatever hosts the
    // screen. A transparent root looked correct in the app — the navigation
    // host happens to paint behind it — and rendered light-on-light in the dark
    // baseline, which is what caught it.
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        HelpListBar(state = state)
        LazyColumn(modifier = Modifier.fillMaxSize().testTag(HELP_LIST_TEST_TAG)) {
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
}

/**
 * The list's top bar: the screen's name, and what is in it.
 *
 * The subtitle is the mono slot at screen level, and it says the same two
 * numbers the About card does — total documents, and how many read without a
 * network — because both are generated from one registry and a reader who sees
 * them disagree has no way to tell which is right.
 *
 * @param state The list's state.
 */
@Composable
private fun HelpListBar(state: HelpListViewState) {
    app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The shell scaffold zeroes its own content insets, so every
                // screen owns its status-bar inset. The sibling screens get it
                // for free from M3's `TopAppBar`; this bar is hand-laid-out for
                // its two-line title, so it has to apply the same padding
                // explicitly — without it the title draws under the clock.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    start = KnotworkTheme.spacing.sp4,
                    end = KnotworkTheme.spacing.sp4,
                    top = KnotworkTheme.spacing.sp3,
                    bottom = KnotworkTheme.spacing.sp2,
                ),
        ) {
            Text(
                text = state.title,
                style = KnotworkTextStyles.TitleLg,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = state.subtitle,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            )
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

/** The inline glyph marking a row that leaves the app. */
private val DeliveryGlyph = 14.dp

/** The trailing chevron. */
private val ChevronGlyph = 18.dp

/** Test tag of the Help list. */
const val HELP_LIST_TEST_TAG: String = "help_list"

/** Test tag of the open offline refusal panel. */
const val HELP_REFUSAL_TEST_TAG: String = "help_refusal"

/**
 * Test tag of one document row.
 *
 * @param id The document's registry id.
 * @return The row's tag.
 */
fun helpRowTestTag(id: String): String = "help_row_$id"
