package app.knotwork.design.screens.help

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Fixtures for the Help snapshots.
 *
 * The counts below are the real ones the build generates, so a baseline shows
 * the density a user actually sees rather than a rounder number that happens to
 * fit. They are fixed here rather than read from the registry because a
 * snapshot has to be stable: a document gaining a section should change the
 * screen under test, not silently re-record every baseline.
 */
object HelpPreview {

    /**
     * The Help list.
     *
     * @param refusedId Id of the row whose offline refusal is open, or `null`.
     * @return The list's state.
     */
    fun list(refusedId: String? = null): HelpListViewState = HelpListViewState(
        title = "Help",
        subtitle = "5 documents · 2 on this device",
        documents = listOf(
            HelpDocument(
                id = "troubleshooting",
                label = "Troubleshooting",
                description = "Fixes for the failures that have names: the model will not load, a background " +
                    "run dies, your data cannot be unlocked at startup.",
                state = "on this device · 11 problems",
                delivery = HelpDelivery.ON_DEVICE,
            ),
            HelpDocument(
                id = "faq",
                label = "FAQ",
                description = "Short answers, each one pointing at the screen where you actually do the thing.",
                state = "on this device · 9 sections",
                delivery = HelpDelivery.ON_DEVICE,
            ),
            HelpDocument(
                id = "user-guide",
                label = "User guide",
                description = "The long form: every screen and every flow, with a search box that works.",
                state = "opens in browser · 2,986 lines",
                delivery = HelpDelivery.IN_BROWSER,
            ),
            HelpDocument(
                id = "cookbook",
                label = "Cookbook",
                description = "Worked pipelines to copy, and the generated reference for every node type.",
                state = "opens in browser · 579 lines",
                delivery = HelpDelivery.IN_BROWSER,
            ),
            HelpDocument(
                id = "external-automation",
                label = "External automation",
                description = "Driving the agent from Tasker, adb or another app on the device.",
                state = "opens in browser · 290 lines",
                delivery = HelpDelivery.IN_BROWSER,
            ),
        ),
        refusedDocumentId = refusedId,
        refusal = refusedId?.let {
            HelpRefusal(
                title = "No network.",
                body = "User guide is on the web and needs one. Troubleshooting and FAQ are on this device " +
                    "and open without it.",
                copyLabel = "Copy link",
                alternativeLabel = "Read Troubleshooting",
            )
        },
    )

    /**
     * The reader.
     *
     * @param visualState Which frame to draw.
     * @param offlineBar Whether the in-body refusal is showing.
     * @return The reader's state.
     */
    fun reader(visualState: HelpReaderVisualState, offlineBar: Boolean = false): HelpReaderViewState =
        HelpReaderViewState(
            title = "Troubleshooting",
            fileName = "troubleshooting.md",
            markdown = "",
            visualState = visualState,
            offlineBarVisible = offlineBar,
        )

    /**
     * Every string the Help surfaces draw.
     *
     * @return The strings.
     */
    fun strings(): HelpStrings = HelpStrings(
        readerAction = "Open in browser",
        loadingNote = "reading from this device",
        anchorNote = "arrived here",
        errorTitle = "This copy did not open.",
        errorBody = "The file shipped with the app is unreadable. Reinstalling replaces it. The same document " +
            "is on the web, unchanged.",
        errorPrimary = "Open in browser",
        errorSecondary = "Try again",
        offlineBarText = "That link is on the web. No network right now.",
        offlineBarCopy = "Copy link",
        opensInBrowser = "Opens in browser",
        backDescription = "Back",
        dismissDescription = "Dismiss",
    )

    /**
     * Stands in for the app's Markdown renderer in a snapshot.
     *
     * Deliberately plain: the document's typography belongs to
     * `knotworkMarkdownTypography()` and is exercised by the chat's own
     * baselines, so reproducing it here would create a second place for it to
     * drift.
     */
    @Composable
    fun BodyPlaceholder() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
        ) {
            Text(
                text = "Troubleshooting",
                style = KnotworkTextStyles.TitleXl,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Each section is one failure, written the way it appears on screen.",
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2),
            )
        }
    }
}
