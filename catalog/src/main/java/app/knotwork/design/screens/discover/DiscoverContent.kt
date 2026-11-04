@file:Suppress("MatchingDeclarationName") // File hosts DiscoverContent and its helper composables.

package app.knotwork.design.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.components.misc.StripedPlaceholder
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Number of skeleton rows rendered while the first page loads. */
private const val LOADING_SKELETON_ROWS = 4

/** Height of a loading skeleton row. */
private val SkeletonRowHeight = 76.dp

/** Diameter of a row's leading icon tile. */
private val RowLeadingSize = 44.dp

/** Inner glyph size inside the leading tile. */
private val RowLeadingIconSize = 22.dp

/**
 * Stateless model-discovery surface: a search field, a pull-to-refresh list of
 * compatible Hugging Face repositories, and the documented loading / empty /
 * error states.
 *
 *  - `TopAppBar` with the screen title and a back arrow.
 *  - Sticky search field (`KnotworkTextField` in search mode) that submits the
 *    query on the IME action.
 *  - One row per repository: leading hub glyph, mono name, host-formatted stats
 *    line, optional "N files" hint and a gated lock badge; tapping opens the
 *    detail screen.
 *
 * @param state immutable view state driving the rendering branch.
 * @param callbacks user-action sink.
 * @param modifier optional layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverContent(
    state: DiscoverViewState,
    modifier: Modifier = Modifier,
    callbacks: DiscoverCallbacks = noopDiscoverCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
                DiscoverTopBar(callbacks = callbacks)
            }
        },
        // The host `AppShellScaffold` already absorbs the system bars and the
        // in-app bottom-nav strip via its inner padding; letting this Scaffold
        // default to `safeDrawing` would mis-inset the body relative to the nav.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DiscoverSearchField(query = state.query, callbacks = callbacks)
            when (state.visualState) {
                DiscoverVisualState.Loading -> DiscoverLoadingState()
                DiscoverVisualState.Empty -> DiscoverEmptyState()
                DiscoverVisualState.Error -> DiscoverErrorState(state = state, callbacks = callbacks)
                DiscoverVisualState.Populated -> DiscoverList(state = state, callbacks = callbacks)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverTopBar(callbacks: DiscoverCallbacks) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.knotwork_discover_title),
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = stringResource(R.string.knotwork_discover_back_cd),
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
private fun DiscoverSearchField(query: String, callbacks: DiscoverCallbacks) {
    KnotworkTextField(
        value = query,
        onValueChange = callbacks.onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
        placeholder = stringResource(R.string.knotwork_discover_search_placeholder),
        leadingIcon = AppIcons.Search,
        search = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { callbacks.onSubmitSearch() }),
        contentDescription = stringResource(R.string.knotwork_discover_search_placeholder),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverList(state: DiscoverViewState, callbacks: DiscoverCallbacks) {
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = callbacks.onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp16),
        ) {
            items(items = state.rows, key = { it.repoId }) { row ->
                DiscoverListRow(row = row, onClick = { callbacks.onModelClick(row.repoId) })
                HorizontalDivider(
                    modifier = Modifier.padding(start = KnotworkTheme.spacing.sp4),
                    color = KnotworkTheme.extended.divider,
                )
            }
        }
    }
}

@Composable
private fun DiscoverListRow(row: DiscoverModelRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RowLeadingSize)
                .clip(KnotworkTheme.shapes.md)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(
                imageVector = AppIcons.Hub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(RowLeadingIconSize),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = row.name,
                    style = KnotworkTextStyles.MonoBase.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.gated) GatedBadge()
            }
            Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp1))
            Text(
                text = row.meta,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.fileCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.knotwork_discover_file_count, row.fileCount, row.fileCount),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        Icon(
            imageVector = AppIcons.ArrowR,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

/** Small access-gated badge (lock glyph + label) rendered next to a row title. */
@Composable
private fun GatedBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface2)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Icon(
            imageVector = AppIcons.Lock,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(KnotworkTheme.spacing.sp3),
        )
        Text(
            text = stringResource(R.string.knotwork_discover_gated_badge),
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun DiscoverLoadingState() {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
    ) {
        repeat(LOADING_SKELETON_ROWS) {
            StripedPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SkeletonRowHeight),
            )
        }
    }
}

@Composable
private fun DiscoverEmptyState() {
    Box(modifier = Modifier.fillMaxSize().padding(KnotworkTheme.spacing.sp6), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.knotwork_discover_empty_title),
            subtitle = stringResource(R.string.knotwork_discover_empty_subtitle),
        )
    }
}

@Composable
private fun DiscoverErrorState(state: DiscoverViewState, callbacks: DiscoverCallbacks) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxSize()
            .padding(KnotworkTheme.spacing.sp6),
    ) {
        Icon(
            imageVector = AppIcons.Warn,
            contentDescription = null,
            tint = KnotworkTheme.extended.signalError,
            modifier = Modifier.size(KnotworkTheme.spacing.sp16),
        )
        EmptyState(
            title = stringResource(R.string.knotwork_discover_error_title),
            subtitle = state.errorMessage.orEmpty(),
            illustration = { /* Icon already drawn above. */ },
            ctaLabel = stringResource(R.string.knotwork_discover_error_retry),
            onCtaClick = callbacks.onRetry,
        )
    }
}
