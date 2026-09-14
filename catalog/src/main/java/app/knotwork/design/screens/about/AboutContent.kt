@file:Suppress("MatchingDeclarationName") // Hosts AboutContent + helpers.

package app.knotwork.design.screens.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.knotwork.design.components.brand.KnotworkAppIconTile
import app.knotwork.design.components.brand.KnotworkLogoSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Stateless Knotwork About surface — hero brand mark + version / license /
 * acknowledgments / privacy cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutContent(
    state: AboutViewState,
    modifier: Modifier = Modifier,
    strings: AboutStrings = AboutStrings(),
    callbacks: AboutCallbacks = noopAboutCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            androidx.compose.foundation.layout.Column {
                TopBar(strings = strings, callbacks = callbacks)
                androidx.compose.material3.HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp4,
                bottom = KnotworkTheme.spacing.sp6,
            ),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        ) {
            item(key = "hero") { Hero(state = state) }
            item(key = "version") { VersionCard(state = state, strings = strings) }
            // Above the licence and the credits on purpose. For a user who
            // arrived from a store listing with no room for a URL, this card is
            // the only place the documentation is named at all — putting it
            // below twenty acknowledgment rows would hide it behind a scroll
            // nobody performs.
            if (state.documentationSummary.isNotEmpty()) {
                item(key = "documentation") {
                    DocumentationCard(state = state, strings = strings, onOpen = callbacks.onOpenDocumentation)
                }
            }
            item(key = "license") {
                LicenseCard(state = state, strings = strings, onOpen = callbacks.onOpenLicense)
            }
            item(key = "ack-header") {
                SectionHeader(label = strings.sectionAcknowledgments)
            }
            items(items = state.acknowledgments, key = { it.name }) { entry ->
                AcknowledgmentRow(entry = entry)
            }
            item(key = "privacy") {
                PrivacyCard(state = state, strings = strings, onOpen = callbacks.onOpenPrivacyPolicy)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(strings: AboutStrings, callbacks: AboutCallbacks) {
    TopAppBar(
        title = {
            Text(text = strings.title, style = KnotworkTextStyles.TitleLg, color = MaterialTheme.colorScheme.onSurface)
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = strings.backCd,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun Hero(state: AboutViewState) {
    // Consistent typography hierarchy across the whole About surface:
    //  - hero name uses `TitleXl` (24 sp SemiBold) — Display2xl was
    //    overpowering on a phone-width screen,
    //  - tagline uses `BodySm` muted (13 sp) — secondary metadata.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth().padding(vertical = KnotworkTheme.spacing.sp3),
    ) {
        KnotworkAppIconTile(size = KnotworkLogoSize.Md)
        Text(
            text = state.appName,
            style = KnotworkTextStyles.TitleXl,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = state.tagline,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun VersionCard(state: AboutViewState, strings: AboutStrings) {
    // All About cards share the same template:
    //  - `LabelSm` section header (uppercase, muted)
    //  - `BodyBase` primary value
    //  - `Caption` secondary lines (build / commit / etc.)
    // The previous mix of `TitleMd` + `MonoBase` per card is what the
    // user flagged as "mismatched fonts".
    AboutCard {
        SectionLabel(text = strings.sectionVersion)
        Text(
            text = state.versionLine,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = state.buildLine,
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        Text(
            text = state.commitSha,
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun LicenseCard(state: AboutViewState, strings: AboutStrings, onOpen: () -> Unit) {
    AboutCard {
        SectionLabel(text = strings.sectionLicense)
        Text(
            text = state.licenseName,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
        KnotworkSecondaryButton(
            text = strings.licenseCta,
            onClick = onOpen,
            size = app.knotwork.design.components.buttons.KnotworkButtonSize.Sm,
        )
    }
}

@Composable
private fun PrivacyCard(state: AboutViewState, strings: AboutStrings, onOpen: () -> Unit) {
    AboutCard {
        SectionLabel(text = strings.sectionPrivacy)
        Text(
            text = state.privacyBody,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
        KnotworkSecondaryButton(
            text = strings.privacyCta,
            onClick = onOpen,
            size = app.knotwork.design.components.buttons.KnotworkButtonSize.Sm,
        )
    }
}

/**
 * The list of documents the app can open.
 *
 * Built from the same section-label plus secondary-button pattern the licence
 * and privacy cards already use, because for a user arriving from the store
 * this screen is the only surface that can tell them the documentation exists
 * at all — the store listing has no room for a URL. Each row carries a summary
 * so the card routes rather than presenting five interchangeable buttons.
 */
@Composable
private fun DocumentationCard(state: AboutViewState, strings: AboutStrings, onOpen: () -> Unit) {
    AboutCard {
        SectionLabel(text = strings.sectionDocumentation)
        Text(
            text = strings.documentationBody,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // One row, not a list of five. The Help screen owns the list, so a
        // document that changes side changes one screen instead of two — and a
        // card that enumerated the documents was a second list of the same
        // thing, drifting from the first the day either was edited.
        KnotworkSecondaryButton(
            text = strings.documentationCta,
            onClick = onOpen,
            size = app.knotwork.design.components.buttons.KnotworkButtonSize.Sm,
        )
        Text(
            text = state.documentationSummary,
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun AboutCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = KnotworkTextStyles.LabelSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp2),
    )
}

@Composable
private fun AcknowledgmentRow(entry: AcknowledgmentEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name,
            style = KnotworkTextStyles.BodySm,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.license,
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}
