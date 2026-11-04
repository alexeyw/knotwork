@file:Suppress("MatchingDeclarationName") // Hosts MoreContent + helpers.

package app.knotwork.design.screens.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.lists.KnotworkNavListRow
import app.knotwork.design.components.lists.KnotworkSectionHeader
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Diameter of the green status dot on the footer pill. */
private val StatusDotSize = 8.dp

/**
 * Stateless Knotwork More tab surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreContent(state: MoreViewState, modifier: Modifier = Modifier, strings: MoreStrings = MoreStrings()) {
    androidx.compose.material3.Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                TopBar(strings = strings)
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
        bottomBar = {
            if (!state.networkStatus.isNullOrBlank()) {
                Footer(text = state.networkStatus, ok = state.networkStatusOk)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = KnotworkTheme.spacing.sp2),
        ) {
            state.sections.forEach { section ->
                item(key = "section-${section.id}") {
                    KnotworkSectionHeader(title = section.title)
                }
                items(items = section.rows, key = { it.id }) { row ->
                    KnotworkNavListRow(
                        title = row.title,
                        leadingIcon = row.icon,
                        onClick = row.onClick,
                        subtitle = row.subtitle,
                        // A badge means "something is happening now" — only
                        // Tasks carries one, which is what keeps it meaningful.
                        // A stored quantity goes in the subtitle instead, so a
                        // zero-count row does not look broken.
                        trailing = if (row.badge > 0) {
                            { Badge(count = row.badge) }
                        } else {
                            null
                        },
                    )
                    HorizontalDivider(
                        color = KnotworkTheme.extended.divider,
                        modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp4),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(strings: MoreStrings) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = strings.title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = strings.subtitle,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        },
        // No search action — the More tab is a short navigation hub; the search
        // icon had no destination.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun Badge(count: Int) {
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .background(color = MaterialTheme.colorScheme.primary)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = count.toString(),
            style = KnotworkTextStyles.LabelSm,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun Footer(text: String, ok: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        val dotColour = if (ok) KnotworkTheme.extended.signalSuccess else KnotworkTheme.extended.signalWarn
        Box(
            modifier = Modifier
                .size(StatusDotSize)
                .background(color = dotColour, shape = KnotworkTheme.shapes.full),
        )
        Text(
            text = text,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}
