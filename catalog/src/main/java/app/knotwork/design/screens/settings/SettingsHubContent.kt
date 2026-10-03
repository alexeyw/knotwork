package app.knotwork.design.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.controls.KnotworkSegmentedControl
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.components.misc.StripedPlaceholder
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Static hub metadata for one category navigation row.
 *
 * @property id Stable category id (routed on tap).
 * @property icon Leading glyph (reuses an existing `AppIcons.*`).
 * @property titleRes Localised category title.
 * @property summaryRes Localised one-line summary. This is the category-level
 *   explanation, which is why no category row carries a help glyph of its own:
 *   a glyph there would explain a door.
 */
private data class HubCategoryMeta(
    val id: SettingsCategoryId,
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
)

private val HUB_CATEGORIES: List<HubCategoryMeta> = listOf(
    HubCategoryMeta(
        SettingsCategoryId.Generation,
        AppIcons.Spark,
        R.string.knotwork_settings_cat_generation_title,
        R.string.knotwork_settings_cat_generation_summary,
    ),
    HubCategoryMeta(
        SettingsCategoryId.Models,
        AppIcons.Chip,
        R.string.knotwork_settings_cat_models_title,
        R.string.knotwork_settings_cat_models_summary,
    ),
    HubCategoryMeta(
        SettingsCategoryId.Memory,
        AppIcons.Brain,
        R.string.knotwork_settings_cat_memory_title,
        R.string.knotwork_settings_cat_memory_summary,
    ),
    HubCategoryMeta(
        SettingsCategoryId.Pipelines,
        AppIcons.Flow,
        R.string.knotwork_settings_cat_pipelines_title,
        R.string.knotwork_settings_cat_pipelines_summary,
    ),
    HubCategoryMeta(
        SettingsCategoryId.Tools,
        AppIcons.Tool,
        R.string.knotwork_settings_cat_tools_title,
        R.string.knotwork_settings_cat_tools_summary,
    ),
    HubCategoryMeta(
        SettingsCategoryId.Background,
        AppIcons.History,
        R.string.knotwork_settings_cat_background_title,
        R.string.knotwork_settings_cat_background_summary,
    ),
    HubCategoryMeta(
        SettingsCategoryId.Privacy,
        AppIcons.Shield,
        R.string.knotwork_settings_cat_privacy_title,
        R.string.knotwork_settings_cat_privacy_summary,
    ),
    HubCategoryMeta(
        SettingsCategoryId.About,
        AppIcons.Info,
        R.string.knotwork_settings_cat_about_title,
        R.string.knotwork_settings_cat_about_summary,
    ),
)

/**
 * Stateless settings hub: a top-bar subtitle, an inline "Basic" block of the
 * five most-touched cross-category controls, and the eight category navigation
 * rows.
 *
 * The search field that the design reserves at the top of the hub is wired in a
 * later task (settings search); it is intentionally omitted here so the hub ships
 * without a non-functional control.
 *
 * @param state Immutable hub state.
 * @param modifier Layout modifier applied to the outer Box.
 * @param callbacks Interaction callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubContent(
    state: SettingsHubViewState,
    modifier: Modifier = Modifier,
    callbacks: SettingsCallbacks = noopSettingsCallbacks(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                KnotworkTopAppBarShell { HubTopBar(state = state, onBack = callbacks.onBack) }
            },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                HubSearchField(
                    query = state.searchQuery,
                    callbacks = callbacks,
                    modifier = Modifier.padding(
                        horizontal = KnotworkTheme.spacing.sp4,
                        vertical = KnotworkTheme.spacing.sp3,
                    ),
                )
                when {
                    state.searchQuery.isBlank() -> HubDefaultBody(state = state, callbacks = callbacks)
                    state.searchResults.isEmpty() -> HubSearchEmpty(query = state.searchQuery, callbacks = callbacks)
                    else -> HubSearchResults(
                        results = state.searchResults,
                        onResultClick = callbacks.onSearchResultClick,
                    )
                }
            }
        }
        if (state.restartRequiredMessage != null) {
            RestartBanner(message = state.restartRequiredMessage, onRestart = callbacks.onRestartClick)
        }
    }
}

/** The default (non-search) hub body: the inline Basic block and category rows. */
@Composable
private fun HubDefaultBody(state: SettingsHubViewState, callbacks: SettingsCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KnotworkTheme.spacing.sp4)
            .padding(bottom = KnotworkTheme.spacing.sp3)
            .testTag(SETTINGS_HUB_BODY_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
    ) {
        HubBasicBlock(state = state, callbacks = callbacks)
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        HubCategoriesBlock(loading = state.loading, onOpenCategory = callbacks.onOpenCategory)
    }
}

/** Pinned settings-search field with a leading glyph and a clear affordance. */
@Composable
private fun HubSearchField(query: String, callbacks: SettingsCallbacks, modifier: Modifier = Modifier) {
    KnotworkTextField(
        value = query,
        onValueChange = callbacks.onSearchQueryChange,
        modifier = modifier.testTag(SETTINGS_SEARCH_FIELD_TAG),
        placeholder = stringResource(R.string.knotwork_settings_search_hint),
        leadingIcon = AppIcons.Search,
        trailingIcon = if (query.isNotEmpty()) AppIcons.X else null,
        onTrailingClick = callbacks.onClearSearch.takeIf { query.isNotEmpty() },
        search = true,
        contentDescription = stringResource(R.string.knotwork_settings_search_hint),
    )
}

/** Scrollable list of search hits: name (matched span bold) + breadcrumb + tier. */
@Composable
private fun HubSearchResults(results: List<HubSearchResultRow>, onResultClick: (HubSearchResultRow) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(SETTINGS_SEARCH_RESULTS_TAG),
    ) {
        Text(
            text = stringResource(R.string.knotwork_settings_search_count, results.size),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp2,
            ),
        )
        results.forEach { row -> HubSearchResultRowView(row = row, onClick = { onResultClick(row) }) }
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp4))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubSearchResultRowView(row: HubSearchResultRow, onClick: () -> Unit) {
    val meta = HUB_CATEGORIES.first { it.id == row.categoryId }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .testTag(SETTINGS_SEARCH_RESULT_TAG_PREFIX + row.anchorKey),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(HUB_CATEGORY_TILE_SIZE)
                .clip(KnotworkTheme.shapes.md)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(imageVector = meta.icon, contentDescription = null, tint = KnotworkTheme.extended.onSurfaceMuted)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightedName(row),
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            ) {
                Text(
                    text = stringResource(meta.titleRes),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
                Icon(
                    imageVector = AppIcons.ArrowR,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(SEARCH_BREADCRUMB_GLYPH),
                )
                Text(
                    text = stringResource(
                        if (row.isBasic) {
                            R.string.knotwork_settings_search_tier_basic
                        } else {
                            R.string.knotwork_settings_search_tier_advanced
                        },
                    ),
                    style = KnotworkTextStyles.MonoSm,
                    color = if (row.isBasic) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        KnotworkTheme.extended.onSurfaceMuted
                    },
                )
                if (row.synonymHit != null) {
                    Text(
                        text = stringResource(R.string.knotwork_settings_search_synonym, row.synonymHit),
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
        }
        Icon(imageVector = AppIcons.ArrowR, contentDescription = null, tint = KnotworkTheme.extended.onSurfaceMuted)
    }
}

/** Calm centred empty state shown when a non-blank query matches nothing. */
@Composable
private fun HubSearchEmpty(query: String, callbacks: SettingsCallbacks) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = KnotworkTheme.spacing.sp6)
            .testTag(SETTINGS_SEARCH_EMPTY_TAG),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SEARCH_EMPTY_TILE)
                .clip(KnotworkTheme.shapes.lg)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(imageVector = AppIcons.Search, contentDescription = null, tint = KnotworkTheme.extended.onSurfaceMuted)
        }
        Text(
            text = stringResource(R.string.knotwork_settings_search_empty_title, query),
            style = KnotworkTextStyles.TitleMd,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.knotwork_settings_search_empty_subtitle),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_settings_search_clear),
            onClick = callbacks.onClearSearch,
            size = KnotworkButtonSize.Md,
            leadingIcon = AppIcons.X,
        )
    }
}

/** Builds the result name with the matched substring bolded in the primary tint. */
@Composable
private fun highlightedName(row: HubSearchResultRow): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        if (row.nameMatchStart < 0 || row.nameMatchLength <= 0) {
            append(row.name)
            return@buildAnnotatedString
        }
        val end = (row.nameMatchStart + row.nameMatchLength).coerceAtMost(row.name.length)
        append(row.name.substring(0, row.nameMatchStart))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
            append(row.name.substring(row.nameMatchStart, end))
        }
        append(row.name.substring(end))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubTopBar(state: SettingsHubViewState, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.knotwork_settings_title),
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = listOf("v${state.subtitleVersion}", state.subtitleChannel, state.subtitleBuildDate)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = stringResource(R.string.knotwork_settings_back),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubBasicBlock(state: SettingsHubViewState, callbacks: SettingsCallbacks) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        SettingsSectionLabel(text = stringResource(R.string.knotwork_settings_hub_basic))
        // Every hub row is anchored, exactly like a category row. Without this
        // the controls most people ever touch were the ones with no explanation
        // available — the hub was the one screen where the whole feature was
        // invisible.
        SettingsAnchor(anchorKey = SettingsRowAnchors.SYSTEM_PROMPT_PREFIX) {
            NavLinkRow(
                icon = AppIcons.Spark,
                title = stringResource(R.string.knotwork_settings_section_system_instructions),
                state = state.systemInstructionsPreview.ifBlank {
                    stringResource(R.string.knotwork_settings_hub_system_instructions_empty)
                },
                onClick = callbacks.onOpenSystemInstructions,
            )
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.TOOL_APPROVAL_POLICY) {
            val approveTitle = stringResource(R.string.knotwork_settings_restrictions_approve)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Row, not FlowRow: the label already wraps to two lines here,
                // and a FlowRow put the glyph on a third of its own, reading as
                // a stray control rather than as part of the label.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = approveTitle,
                        style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SettingsHintGlyph(settingName = approveTitle)
                }
                KnotworkSegmentedControl(
                    options = listOf(state.approveAllLabel, state.approveSensitiveLabel, state.approveNeverLabel),
                    selectedIndex = state.approveSelection.toIndex(),
                    onSelect = { callbacks.onApproveSelectionChange(approveOptionFromIndex(it)) },
                    modifier = Modifier.weight(SEGMENTED_TRAILING_WEIGHT),
                )
            }
            SettingsHintBody()
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.BLOCK_DESTRUCTIVE_TOOLS) {
            IconToggleRow(
                icon = AppIcons.Shield,
                title = stringResource(R.string.knotwork_settings_restrictions_block_destructive),
                state = "",
                checked = state.blockDestructive,
                onCheckedChange = callbacks.onBlockDestructiveChange,
            )
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.LOCAL_MODEL_BACKEND) {
            BackendDropdownRow(
                title = stringResource(R.string.knotwork_settings_local_model_backend_title),
                backendLabel = state.backendLabel,
                selectedBackend = state.selectedBackend,
                options = state.backendOptions,
                onSelected = callbacks.onBackendSelected,
            )
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.CRASH_REPORTING_ENABLED) {
            IconToggleRow(
                icon = AppIcons.Shield,
                title = stringResource(R.string.knotwork_settings_crash_reporting_label),
                state = "",
                checked = state.crashReportingEnabled,
                onCheckedChange = callbacks.onCrashReportingToggle,
            )
        }
    }
}

@Composable
private fun HubCategoriesBlock(loading: Boolean, onOpenCategory: (SettingsCategoryId) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        SettingsSectionLabel(text = stringResource(R.string.knotwork_settings_hub_categories))
        HUB_CATEGORIES.forEach { meta ->
            HubCategoryRow(meta = meta, loading = loading, onClick = { onOpenCategory(meta.id) })
        }
    }
}

@Composable
private fun HubCategoryRow(meta: HubCategoryMeta, loading: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(HUB_CATEGORY_ROW_TAG_PREFIX + meta.id.name),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(HUB_CATEGORY_TILE_SIZE)
                .clip(KnotworkTheme.shapes.md)
                .background(color = KnotworkTheme.extended.surface2)
                .alpha(if (loading) LOADING_TILE_ALPHA else 1f),
        ) {
            Icon(imageVector = meta.icon, contentDescription = null, tint = KnotworkTheme.extended.onSurfaceMuted)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(meta.titleRes),
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (loading) {
                StripedPlaceholder(
                    modifier = Modifier
                        .padding(top = KnotworkTheme.spacing.sp1)
                        .fillMaxWidth(LOADING_SUMMARY_FRACTION)
                        .height(LOADING_SUMMARY_HEIGHT),
                )
            } else {
                // Body-size and near-full ink, because the summary explains the
                // category. Muted micro-type is reserved for machine state, and
                // a reader who learns that slot stops reading anything in it.
                Text(
                    text = stringResource(meta.summaryRes),
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurface2,
                )
            }
        }
        Icon(imageVector = AppIcons.ArrowR, contentDescription = null, tint = KnotworkTheme.extended.onSurfaceMuted)
    }
}

internal fun ApproveToolCallsOption.toIndex(): Int = when (this) {
    ApproveToolCallsOption.AllCalls -> 0
    ApproveToolCallsOption.Sensitive -> 1
    ApproveToolCallsOption.Never -> 2
}

internal fun approveOptionFromIndex(index: Int): ApproveToolCallsOption = when (index) {
    0 -> ApproveToolCallsOption.AllCalls
    1 -> ApproveToolCallsOption.Sensitive
    else -> ApproveToolCallsOption.Never
}

/** Test tag for the scrollable hub body. */
const val SETTINGS_HUB_BODY_TEST_TAG: String = "settings_hub_body"

/** Test tag for the hub search field. */
const val SETTINGS_SEARCH_FIELD_TAG: String = "settings_search_field"

/** Test tag for the scrollable search-results list. */
const val SETTINGS_SEARCH_RESULTS_TAG: String = "settings_search_results"

/** Test tag for the no-match empty state. */
const val SETTINGS_SEARCH_EMPTY_TAG: String = "settings_search_empty"

/** Prefix for a single search-result row's test tag (suffixed with the anchor). */
const val SETTINGS_SEARCH_RESULT_TAG_PREFIX: String = "settings_search_result_"

private val HUB_CATEGORY_TILE_SIZE = 40.dp
private val SEARCH_BREADCRUMB_GLYPH = 12.dp
private val SEARCH_EMPTY_TILE = 56.dp
private val LOADING_SUMMARY_HEIGHT = 9.dp
private const val LOADING_SUMMARY_FRACTION = 0.68f
private const val LOADING_TILE_ALPHA = 0.5f
