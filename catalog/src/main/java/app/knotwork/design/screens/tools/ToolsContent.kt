@file:Suppress("MatchingDeclarationName") // Hosts ToolsContent, ToolDetailContent, McpServerConfigContent.

package app.knotwork.design.screens.tools

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.controls.KnotworkSegmentedControl
import app.knotwork.design.components.lists.KnotworkSectionHeader
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.components.misc.KnotworkWarningBanner
import app.knotwork.design.components.misc.StripedPlaceholder
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.settings.KnotworkHelpEntry
import app.knotwork.design.screens.settings.KnotworkHintPanel
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

private val LeadingTileSize = 40.dp
private val LeadingIconSize = 18.dp
private val RiskPillHeight = 22.dp
private val StatusDotSize = 10.dp
private val SchemaPreviewHeight = 240.dp

// Material3's default Switch (52×32 dp) dwarfs the Knotwork row title scale.
// `SettingsContent` shrinks it to ~78 % via `Modifier.scale` so the visual
// weight matches our 14 sp row titles; the parent Row's `clickable` still
// guarantees a 48 dp touch target. Keep this in sync with the Settings
// constant of the same name.
private const val SWITCH_SCALE = 0.78f

/** Number of section / row skeletons rendered while the surface is loading. */
private const val LOADING_SECTION_COUNT = 2
private const val LOADING_ROWS_PER_SECTION = 3

/**
 * Opacity applied to the server row and its nested tools when the server's
 * connection state is [McpConnectionState.Disconnected]. Pairs the colour-only
 * signal (warning dot) with a second visual cue for accessibility.
 */
private const val DISCONNECTED_ROW_ALPHA = 0.6f

/**
 * Composite key for the server-row subtitle's `AnimatedContent`. Re-keys the
 * crossfade on both connection-state transitions (Connecting → Connected →
 * Error) and label-only changes (Connected `42 ms` → `318 ms`) so the
 * mono-text re-flows through the same animation channel.
 */
private data class ServerSubtitle(val state: McpConnectionState, val label: String, val count: Int)

/**
 * Knotwork tools surface.
 *
 * Stateless except for one thing: which groups are folded. That is view state
 * with no consumer outside this surface, so it is held here in
 * `rememberSaveable` rather than hoisted into a bag every caller would have to
 * thread. Everything else — the rows, the counts, the connection states —
 * arrives in [ToolsViewState].
 *
 *  - TopAppBar with title + monospace "N built-in · M MCP" subtitle;
 *    trailing overflow icon.
 *  - Section 1 (`BUILT-IN (APPFUNCTIONS)`): per-tool row with leading
 *    edit-glyph tile, monospace title, an outline risk pill
 *    (Read only / Sensitive / Destructive) next to the title, a wrapping
 *    body subtitle, and a trailing Switch.
 *  - Section 2 (`MCP SERVERS` + `+ Add MCP` link): one row per server
 *    with a leading status dot, monospace URL, monospace
 *    "N tools · X ms" / "N tools · disabled" subtitle, trailing trash
 *    icon.
 *  - Inline add-server form rendered at the bottom of the list when
 *    [ToolsViewState.addServerForm] is non-null. The catalog ships the
 *    visuals; the host owns persistence + URL validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsContent(
    state: ToolsViewState,
    modifier: Modifier = Modifier,
    callbacks: ToolsCallbacks = noopToolsCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
                ToolsTopBar(state = state, callbacks = callbacks)
            }
        },
        // The outer `AppShellScaffold` already absorbs both the system
        // navigation bar and the in-app bottom-nav strip via its own
        // inner padding. Letting this Scaffold default to `safeDrawing`
        // would double-count the bottom inset and leave a visible gap
        // between the list and the bottom-nav strip.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when (state.visualState) {
            ToolsVisualState.Empty -> ToolsEmpty(callbacks = callbacks, padding = padding)
            ToolsVisualState.Loading -> ToolsLoading(padding = padding)
            ToolsVisualState.Error -> ToolsError(state = state, callbacks = callbacks, padding = padding)
            ToolsVisualState.Default -> ToolsList(state = state, callbacks = callbacks, padding = padding)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsTopBar(state: ToolsViewState, callbacks: ToolsCallbacks) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = stringResource(R.string.knotwork_tools_title),
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = androidx.compose.ui.res.pluralStringResource(
                        R.plurals.knotwork_tools_topbar_subtitle,
                        state.builtInTools.size,
                        state.builtInTools.size,
                        state.mcpServers.size,
                    ),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        },
        // The permanent door to adding a server. It sits here rather than on a
        // FAB or a stuck-bottom button because the bottom edge already carries
        // the system nav bar *and* the app's own bottom nav: a FAB would park a
        // 56 dp circle over the last row's switch and claim to be the screen's
        // primary action, which on Tools is switching tools on and off. Below
        // the whole built-in list — where the link used to be — is exactly
        // where the closed-test tester could not find it.
        //
        // Still no overflow menu: per-server actions live on each row, so it
        // would have nothing to host.
        actions = {
            // The documentation action sits beside Add, not inside a menu:
            // the tester who could not finish this screen never opened one.
            IconButton(onClick = callbacks.onOpenDocumentation) {
                Icon(
                    imageVector = AppIcons.Book,
                    contentDescription = stringResource(R.string.knotwork_tools_docs_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = callbacks.onAddServerOpen) {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = stringResource(R.string.knotwork_tools_add_mcp_cd),
                    tint = MaterialTheme.colorScheme.primary,
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
private fun ToolsEmpty(callbacks: ToolsCallbacks, padding: PaddingValues) {
    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        EmptyState(
            title = stringResource(R.string.knotwork_tools_empty_title),
            subtitle = stringResource(R.string.knotwork_tools_empty_subtitle),
            ctaLabel = stringResource(R.string.knotwork_tools_empty_cta),
            onCtaClick = callbacks.onAddServerOpen,
        )
    }
}

@Composable
private fun ToolsLoading(padding: PaddingValues) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxSize().padding(padding).padding(KnotworkTheme.spacing.sp4),
    ) {
        repeat(LOADING_SECTION_COUNT) {
            StripedPlaceholder(modifier = Modifier.fillMaxWidth().height(LeadingTileSize))
            repeat(LOADING_ROWS_PER_SECTION) {
                StripedPlaceholder(modifier = Modifier.fillMaxWidth().height(LeadingTileSize + LeadingTileSize))
            }
        }
    }
}

@Composable
private fun ToolsError(state: ToolsViewState, callbacks: ToolsCallbacks, padding: PaddingValues) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxSize().padding(padding).padding(KnotworkTheme.spacing.sp6),
    ) {
        Icon(
            imageVector = AppIcons.Warn,
            contentDescription = null,
            tint = KnotworkTheme.extended.signalError,
            modifier = Modifier.size(KnotworkTheme.spacing.sp16),
        )
        EmptyState(
            title = "Couldn't load tools",
            subtitle = state.errorMessage.orEmpty(),
            illustration = { /* icon above */ },
            ctaLabel = "Retry",
            onCtaClick = callbacks.onErrorRetry,
        )
    }
}

/**
 * The two-group tool list.
 *
 * Both groups are always present and both are collapsible — with one exception:
 * an **empty** group has no chevron, because a chevron that reveals emptiness
 * teaches the wrong thing. Splitting the two into separate screens is excluded:
 * built-in tools and MCP tools are the same thing to the model, and the tester
 * who asked for them to be "разнесены" wanted them *grouped*, not relocated.
 *
 * Collapse state is view state, held here rather than hoisted: it survives
 * rotation and process death through `rememberSaveable`, and nothing outside
 * this surface has an opinion about whether a list section is folded.
 */
@Composable
private fun ToolsList(state: ToolsViewState, callbacks: ToolsCallbacks, padding: PaddingValues) {
    var builtInCollapsed by rememberSaveable { mutableStateOf(false) }
    var mcpCollapsed by rememberSaveable { mutableStateOf(false) }
    val disconnectedCount = state.mcpServers.count { it.state == McpConnectionState.Disconnected }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = KnotworkTheme.spacing.sp2),
    ) {
        item(key = "built-in-header") {
            KnotworkSectionHeader(
                title = stringResource(R.string.knotwork_tools_section_built_in),
                // The count describes the rows this group contains: its rows
                // are tools, so it counts tools.
                countLabel = pluralStringResource(
                    R.plurals.knotwork_tools_group_count_tools,
                    state.builtInTools.size,
                    state.builtInTools.size,
                ),
                collapsible = state.builtInTools.isNotEmpty(),
                collapsed = builtInCollapsed,
                onToggleCollapsed = { builtInCollapsed = !builtInCollapsed },
                showDivider = true,
            )
        }
        if (!builtInCollapsed) {
            items(items = state.builtInTools, key = { "builtin-${it.id}" }) { tool ->
                BuiltInToolRowView(tool = tool, callbacks = callbacks)
                if (tool.allowedDomainsCount != null) {
                    AllowedDomainsEntryRow(
                        hostCount = tool.allowedDomainsCount,
                        onClick = callbacks.onOpenAllowedDomains,
                    )
                }
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        }
        item(key = "mcp-header") {
            KnotworkSectionHeader(
                title = stringResource(R.string.knotwork_tools_section_mcp),
                // Its rows are servers, so it counts servers; each server row
                // carries its own tool count. A header whose number matched no
                // row beneath it would be a number the reader cannot check.
                countLabel = pluralStringResource(
                    R.plurals.knotwork_tools_group_count_servers,
                    state.mcpServers.size,
                    state.mcpServers.size,
                ),
                // A collapsed group may not hide a problem. Expanded, the rows
                // say it themselves and the header drops it.
                warning = if (mcpCollapsed && disconnectedCount > 0) {
                    pluralStringResource(
                        R.plurals.knotwork_tools_group_warn_disconnected,
                        disconnectedCount,
                        disconnectedCount,
                    )
                } else {
                    null
                },
                collapsible = state.mcpServers.isNotEmpty(),
                collapsed = mcpCollapsed,
                onToggleCollapsed = { mcpCollapsed = !mcpCollapsed },
                showDivider = true,
            )
        }
        if (state.mcpServers.isEmpty()) {
            // The empty state lives *inside* the group that is actually empty.
            // A full-screen "no tools" would be a lie: the built-in group is
            // never empty.
            item(key = "mcp-empty") { McpEmptyGroupCard(onAddServer = callbacks.onAddServerOpen) }
        }
        if (!mcpCollapsed) {
            state.mcpServers.forEach { server ->
                // In the Disconnected state, the server row plus
                // every nested tool row renders at 60 % opacity so the disabled-by-
                // server-failure affordances read at a glance. The opacity stops at
                // the row level (it does NOT cascade into the catalog `EmptyState`
                // or `StripedPlaceholder` containers, which live outside this loop).
                val rowAlpha = if (server.state == McpConnectionState.Disconnected) DISCONNECTED_ROW_ALPHA else 1f
                item(key = "mcp-${server.id}") {
                    McpServerRowView(server = server, callbacks = callbacks, rowAlpha = rowAlpha)
                    HorizontalDivider(color = KnotworkTheme.extended.divider)
                }
                if (server.expanded) {
                    items(items = server.tools, key = { "mcp-tool-${it.id}" }) { entry ->
                        McpToolEntryRowView(entry = entry, callbacks = callbacks, rowAlpha = rowAlpha)
                        HorizontalDivider(color = KnotworkTheme.extended.divider)
                    }
                }
            }
        }
    }
}

/**
 * The one-time CTA shown inside an empty MCP group.
 *
 * It coexists with the top bar's permanent `+` without being the duplicate the
 * closed test found elsewhere: `#8`'s dupes were two *permanent* controls for
 * one verb. This one is loud, labelled, and gone the moment a server exists —
 * which is why nobody has to discover the top-bar slot on day one.
 */
@Composable
private fun McpEmptyGroupCard(onAddServer: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3)
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Text(
            text = stringResource(R.string.knotwork_tools_empty_mcp_title),
            style = KnotworkTextStyles.LabelLg.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.knotwork_tools_empty_mcp_body),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_tools_empty_mcp_cta),
            onClick = onAddServer,
            leadingIcon = AppIcons.Add,
        )
    }
}

@Composable
private fun BuiltInToolRowView(tool: BuiltInToolRow, callbacks: ToolsCallbacks) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { callbacks.onToolClick(tool.id) }
            .padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp3,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(LeadingTileSize)
                .clip(KnotworkTheme.shapes.sm)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(
                imageVector = AppIcons.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(LeadingIconSize),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = tool.name,
                    style = KnotworkTextStyles.MonoBase.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RiskOutlinePill(risk = tool.risk)
            }
            if (tool.description.isNotBlank()) {
                Text(
                    text = tool.description,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        Switch(
            checked = tool.enabled,
            onCheckedChange = { callbacks.onToolToggle(tool.id, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.scale(SWITCH_SCALE),
        )
    }
}

/**
 * Deep-link sub-row rendered beneath a built-in tool that owns an allowlist
 * (today only `http_request`). Mirrors the visual language of a settings row —
 * leading shield, title + "N hosts" subtitle, trailing forward arrow — and
 * opens the standalone `AllowedDomainsContent` editor on tap.
 */
@Composable
private fun AllowedDomainsEntryRow(hostCount: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2)
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface2)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outline, shape = KnotworkTheme.shapes.md)
            .clickable(
                onClickLabel = stringResource(R.string.knotwork_allowed_domains_entry_cd),
                onClick = onClick,
            )
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = AppIcons.Shield,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(LeadingIconSize),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.knotwork_allowed_domains_entry_title),
                style = KnotworkTextStyles.LabelLg.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.knotwork_allowed_domains_entry_count,
                    hostCount,
                    hostCount,
                ),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        Icon(
            imageVector = AppIcons.ArrowR,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(LeadingIconSize),
        )
    }
}

/**
 * The tool's risk level: a segmented control when the approval gate will
 * actually read the user's choice, a stated pill when it will not.
 *
 * Both branches say the level out loud. The screen used to say nothing about
 * risk at all, which left the one number the approval prompt turns on invisible
 * on the very screen dedicated to the tool.
 *
 * @param risk Whether the level is the user's to set, and its current value.
 * @param onRiskChange Invoked with the newly chosen level (editable branch only).
 */
@Composable
private fun ToolRiskSection(risk: ToolRiskUi, onRiskChange: (BuiltInToolRisk) -> Unit) {
    Text(
        text = stringResource(R.string.knotwork_tools_detail_risk),
        style = KnotworkTextStyles.TitleMd,
        color = MaterialTheme.colorScheme.onSurface,
    )
    when (risk) {
        is ToolRiskUi.Fixed -> {
            RiskOutlinePill(risk = risk.risk)
            Text(
                text = stringResource(R.string.knotwork_tools_detail_risk_fixed_note),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }

        is ToolRiskUi.Editable -> {
            KnotworkSegmentedControl(
                options = RISK_ORDER.map { stringResource(riskLabelRes(it)) },
                selectedIndex = RISK_ORDER.indexOf(risk.risk),
                onSelect = { index -> onRiskChange(RISK_ORDER[index]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.knotwork_tools_detail_risk_editable_note),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

/**
 * Risk levels in increasing severity — the order the segmented control renders
 * and the order the index round-trips through.
 */
private val RISK_ORDER = listOf(BuiltInToolRisk.ReadOnly, BuiltInToolRisk.Sensitive, BuiltInToolRisk.Destructive)

/** The localized label for one risk level, shared by the pill and the control. */
private fun riskLabelRes(risk: BuiltInToolRisk): Int = when (risk) {
    BuiltInToolRisk.ReadOnly -> R.string.knotwork_tools_pill_readonly
    BuiltInToolRisk.Sensitive -> R.string.knotwork_tools_pill_sensitive
    BuiltInToolRisk.Destructive -> R.string.knotwork_tools_pill_destructive
}

@Composable
private fun RiskOutlinePill(risk: BuiltInToolRisk) {
    val accent = riskAccent(risk)
    val label = stringResource(riskLabelRes(risk))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .height(RiskPillHeight)
            .clip(KnotworkTheme.shapes.full)
            .border(width = 1.dp, color = accent, shape = KnotworkTheme.shapes.full)
            .padding(horizontal = KnotworkTheme.spacing.sp2)
            .semantics { contentDescription = "Risk level: $label" },
    ) {
        Box(
            modifier = Modifier
                .size(KnotworkTheme.spacing.sp2)
                .background(color = accent, shape = CircleShape),
        )
        Text(
            text = label,
            style = KnotworkTextStyles.LabelSm,
            // The word takes the text-safe tone of [riskAccent]: the accents
            // themselves are 2.3–3.9:1 as text on the light surface.
            color = when (risk) {
                BuiltInToolRisk.ReadOnly -> KnotworkTheme.extended.riskReadonlyText
                BuiltInToolRisk.Sensitive -> KnotworkTheme.extended.riskSensitiveText
                BuiltInToolRisk.Destructive -> KnotworkTheme.extended.riskDestructiveText
            },
        )
    }
}

@Composable
private fun riskAccent(risk: BuiltInToolRisk): Color = when (risk) {
    BuiltInToolRisk.ReadOnly -> KnotworkTheme.extended.riskReadonly
    BuiltInToolRisk.Sensitive -> KnotworkTheme.extended.riskSensitive
    BuiltInToolRisk.Destructive -> KnotworkTheme.extended.riskDestructive
}

/**
 * Resolves the leading status-dot colour for one server row.
 *
 * Kept as a small helper so the dot colour can be passed through
 * `animateColorAsState` for the connection-state transition animation
 * without duplicating the `when` table at every call site.
 */
@Composable
private fun serverDotColor(state: McpConnectionState): Color = when (state) {
    McpConnectionState.Connected -> KnotworkTheme.extended.signalSuccess
    McpConnectionState.Disconnected -> KnotworkTheme.extended.signalWarn
    McpConnectionState.Syncing -> MaterialTheme.colorScheme.primary
    McpConnectionState.Error -> KnotworkTheme.extended.signalError
    McpConnectionState.Disabled -> KnotworkTheme.extended.onSurfaceMuted
}

@Composable
private fun McpServerRowView(server: McpServerRow, callbacks: ToolsCallbacks, rowAlpha: Float = 1f) {
    val targetDotColor = serverDotColor(state = server.state)
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    // Connection-state transitions animate the dot colour (target colour
    // tween) and the subtitle (`AnimatedContent` with `SizeTransform` so the
    // monospace `<N tools · <label>` line re-flows when the label widens —
    // e.g. `Connecting → Connected` shrinks, `Connected → Error("reason")`
    // grows). Under reduced motion both animations collapse to an instant
    // snap.
    val animationDurationMs = if (reducedMotion) 0 else KnotworkTheme.motion.dur3
    val dotColor by animateColorAsState(
        targetValue = targetDotColor,
        animationSpec = tween(durationMillis = animationDurationMs, easing = KnotworkTheme.motion.easeStd),
        label = "mcpServerDotColor",
    )
    val expandable = server.tools.isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .then(
                if (expandable) Modifier.clickable { callbacks.onServerExpandToggle(server.id) } else Modifier,
            )
            .padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp3,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(StatusDotSize)
                .background(color = dotColor, shape = CircleShape),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = server.url,
                style = KnotworkTextStyles.MonoBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedContent(
                targetState = ServerSubtitle(
                    state = server.state,
                    label = server.latencyLabel,
                    count = server.toolCount,
                ),
                transitionSpec = {
                    val enter = fadeIn(animationSpec = tween(durationMillis = animationDurationMs))
                    val exit = fadeOut(animationSpec = tween(durationMillis = animationDurationMs))
                    enter.togetherWith(exit).using(
                        SizeTransform(clip = false) { _, _ -> tween(durationMillis = animationDurationMs) },
                    )
                },
                label = "mcpServerSubtitle",
            ) { subtitle ->
                if (subtitle.state == McpConnectionState.Disconnected) {
                    // Opacity alone is a colour-only signal. The state gets a
                    // glyph and a word where the tool count usually sits, so it
                    // survives both a screen reader and a 60 % dimmed row.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                    ) {
                        Icon(
                            imageVector = AppIcons.Warn,
                            contentDescription = null,
                            tint = KnotworkTheme.extended.signalWarn,
                            modifier = Modifier.size(StatusDotSize),
                        )
                        // The amber glyph carries the state; the word is `onSurface2`
                        // (amber text is 2.3:1, and the whole row is dimmed besides).
                        Text(
                            text = stringResource(R.string.knotwork_tools_mcp_disconnected),
                            style = KnotworkTextStyles.MonoSm,
                            color = KnotworkTheme.extended.onSurface2,
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        text = "${subtitle.count} tools · ${subtitle.label}",
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
        }
        if (server.state == McpConnectionState.Disconnected) {
            // The action the state calls for, promoted out of the row's
            // overflow: a row that reports a problem should offer the fix.
            //
            // A *labelled* button was drawn in the handoff and measured here as
            // unaffordable: at 360 dp, `Reconnect` + expand chevron + overflow
            // left ~120 dp for the URL, which truncated to `mcp://a…` and wrapped
            // `Disconnected` onto two lines. A disconnected server keeps its
            // cached tool list (the status flow changes, `tools` does not), so
            // the chevron is genuinely there — this is not a fixture artefact.
            // The word survives where it carries the a11y weight: in the
            // subtitle, beside the glyph.
            IconButton(onClick = { callbacks.onServerRefresh(server.id) }) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = stringResource(R.string.knotwork_tools_mcp_reconnect),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (expandable) {
            IconButton(onClick = { callbacks.onServerExpandToggle(server.id) }) {
                Icon(
                    imageVector = if (server.expanded) {
                        AppIcons.ArrowUp
                    } else {
                        AppIcons.ArrowDown
                    },
                    contentDescription = stringResource(R.string.knotwork_tools_expand_server_cd),
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        ServerRowOverflowMenu(server = server, callbacks = callbacks)
    }
}

@Composable
private fun ServerRowOverflowMenu(server: McpServerRow, callbacks: ToolsCallbacks) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = AppIcons.More,
                contentDescription = stringResource(R.string.knotwork_tools_row_overflow_cd),
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.knotwork_tools_row_action_refresh)) },
                onClick = {
                    menuOpen = false
                    callbacks.onServerRefresh(server.id)
                },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.knotwork_tools_row_action_edit)) },
                onClick = {
                    menuOpen = false
                    callbacks.onServerEdit(server.id)
                },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Edit,
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.knotwork_tools_row_action_delete),
                        color = KnotworkTheme.extended.signalErrorText,
                    )
                },
                onClick = {
                    menuOpen = false
                    callbacks.onServerRemove(server.id)
                },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Trash,
                        contentDescription = null,
                        tint = KnotworkTheme.extended.signalError,
                    )
                },
            )
        }
    }
}

@Composable
private fun McpToolEntryRowView(entry: McpToolEntry, callbacks: ToolsCallbacks, rowAlpha: Float = 1f) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .clickable { callbacks.onMcpToolClick(entry.id) }
            .padding(
                start = KnotworkTheme.spacing.sp8,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp3,
                bottom = KnotworkTheme.spacing.sp3,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(LeadingTileSize)
                .clip(KnotworkTheme.shapes.sm)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(
                imageVector = AppIcons.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(LeadingIconSize),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = entry.name,
                    style = KnotworkTextStyles.MonoBase.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    // Two lines, not one. MCP catalogues routinely advertise tools
                    // sharing a long prefix (`create_issue` / `create_pull_request`,
                    // `get-resource-a` / `get-resource-b`); ellipsised to one line
                    // they rendered identically, and every row here carries a
                    // toggle — which is how someone disables the wrong tool. Rows
                    // with names that already fit are unchanged.
                    maxLines = MCP_TOOL_NAME_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RiskOutlinePill(risk = entry.risk)
            }
            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            // The words carry the meaning; the warn glyph only draws the eye. The
            // text is not drawn in the warn colour, which reads at about 2.3:1 on the
            // light surface — below what body text needs.
            entry.shadowing?.let { shadowing ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                ) {
                    Icon(
                        imageVector = AppIcons.Warn,
                        contentDescription = null,
                        tint = KnotworkTheme.extended.signalWarn,
                        modifier = Modifier
                            .padding(top = KnotworkTheme.spacing.sp1)
                            .size(StatusDotSize),
                    )
                    Text(
                        text = when (shadowing) {
                            McpToolShadowing.DeviceTool ->
                                stringResource(R.string.knotwork_tools_mcp_shadowed_by_device)
                            is McpToolShadowing.EarlierServer ->
                                stringResource(R.string.knotwork_tools_mcp_shadowed_by_server, shadowing.serverName)
                        },
                        style = KnotworkTextStyles.BodySm,
                        color = KnotworkTheme.extended.onSurface2,
                    )
                }
            }
        }
        Switch(
            checked = entry.enabled,
            onCheckedChange = { callbacks.onMcpToolToggle(entry.id, it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.scale(SWITCH_SCALE),
        )
    }
}

@Composable
private fun FormSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = modifier,
    )
}

@Composable
private fun OutlinedFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.sm)
            .border(
                width = 1.dp,
                color = if (isError) KnotworkTheme.extended.signalError else KnotworkTheme.extended.outlineStrong,
                shape = KnotworkTheme.shapes.sm,
            )
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = KnotworkTextStyles.MonoBase.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = KnotworkTextStyles.MonoBase,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun AuthChip(option: McpAuthSelector, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.outlineStrong
    val labelColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurfaceMuted
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .border(width = 1.dp, color = borderColor, shape = KnotworkTheme.shapes.full)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = option.label,
            style = KnotworkTextStyles.LabelMd,
            color = labelColor,
        )
    }
}

@Composable
private fun AuthFields(form: AddMcpServerForm, callbacks: McpServerConfigCallbacks) {
    when (form.authType) {
        McpAuthSelector.NONE -> Unit
        McpAuthSelector.BEARER -> OutlinedFormTextField(
            value = form.bearerToken,
            onValueChange = callbacks.onBearerTokenChange,
            placeholder = stringResource(R.string.knotwork_tools_form_auth_bearer_placeholder),
            isError = false,
        )
        McpAuthSelector.BASIC -> {
            OutlinedFormTextField(
                value = form.basicUsername,
                onValueChange = callbacks.onBasicUsernameChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_basic_user_placeholder),
                isError = false,
            )
            OutlinedFormTextField(
                value = form.basicPassword,
                onValueChange = callbacks.onBasicPasswordChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_basic_pass_placeholder),
                isError = false,
            )
        }
        McpAuthSelector.API_KEY -> {
            OutlinedFormTextField(
                value = form.apiKeyHeaderName,
                onValueChange = callbacks.onApiKeyHeaderNameChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_apikey_name_placeholder),
                isError = false,
            )
            OutlinedFormTextField(
                value = form.apiKeyValue,
                onValueChange = callbacks.onApiKeyValueChange,
                placeholder = stringResource(R.string.knotwork_tools_form_auth_apikey_value_placeholder),
                isError = false,
            )
        }
    }
}

@Composable
private fun TransportChip(option: McpTransportOption, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.outlineStrong
    val labelColor = if (selected) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurfaceMuted
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .border(width = 1.dp, color = borderColor, shape = KnotworkTheme.shapes.full)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = option.label,
            style = KnotworkTextStyles.LabelMd,
            color = labelColor,
        )
    }
}

@Composable
private fun HeaderRow(
    row: McpHeaderRow,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedFormTextField(
            value = row.key,
            onValueChange = onKeyChange,
            placeholder = stringResource(R.string.knotwork_tools_form_header_key_placeholder),
            isError = false,
            modifier = Modifier.weight(1f),
        )
        OutlinedFormTextField(
            value = row.value,
            onValueChange = onValueChange,
            placeholder = stringResource(R.string.knotwork_tools_form_header_value_placeholder),
            isError = false,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = AppIcons.Trash,
                contentDescription = stringResource(R.string.knotwork_tools_form_header_remove_cd),
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

// ------------------------- ToolDetailContent -------------------------

/**
 * Stateless tool-detail surface — schema preview + enable/disable toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailContent(
    state: ToolDetailViewState,
    modifier: Modifier = Modifier,
    callbacks: ToolDetailCallbacks = noopToolDetailCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        // The outer `AppShellScaffold` already absorbs the system navigation
        // bar (and the in-app bottom-nav strip). Letting this Scaffold default
        // to `safeDrawing` would double-count the bottom inset and leave a
        // visible gap under the schema box.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
                TopAppBar(
                    title = {
                        Text(
                            text = state.toolName,
                            style = KnotworkTextStyles.TitleMd,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = callbacks.onBack) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = stringResource(R.string.knotwork_tools_detail_back),
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
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(state = rememberScrollState())
                .padding(KnotworkTheme.spacing.sp4),
        ) {
            Text(
                text = state.serverDisplayName,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            Text(
                text = state.description,
                style = KnotworkTextStyles.BodySm,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (state.lastUsed != null) {
                Text(
                    text = state.lastUsed,
                    style = KnotworkTextStyles.Caption,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (state.enabled) "Enabled" else "Disabled",
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.enabled,
                    onCheckedChange = callbacks.onToggle,
                    modifier = Modifier.scale(SWITCH_SCALE),
                )
            }
            ToolRiskSection(risk = state.risk, onRiskChange = callbacks.onRiskChange)
            Text(
                text = stringResource(R.string.knotwork_tools_detail_schema),
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (state.visualState) {
                ToolDetailVisualState.Loading -> StripedPlaceholder(
                    modifier = Modifier.fillMaxWidth().height(SchemaPreviewHeight),
                )
                ToolDetailVisualState.SchemaError -> Surface(
                    shape = KnotworkTheme.shapes.md,
                    color = KnotworkTheme.extended.surface1,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.knotwork_tools_detail_schema_error),
                        style = KnotworkTextStyles.BodyBase,
                        color = KnotworkTheme.extended.signalErrorText,
                        modifier = Modifier.padding(KnotworkTheme.spacing.sp3),
                    )
                }
                ToolDetailVisualState.Default -> Surface(
                    shape = KnotworkTheme.shapes.md,
                    color = KnotworkTheme.extended.consoleBg,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Horizontal-scroll the monospace schema preview so long
                    // lines (deep JSON-Schema, MCP tool inputs) stay legible
                    // without wrapping — required at fontScale 2× so the
                    // schema-preview remains horizontally-scrollable.
                    Text(
                        text = state.schemaJson.orEmpty(),
                        style = KnotworkTextStyles.MonoBase,
                        color = KnotworkTheme.extended.consoleFg,
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(state = rememberScrollState())
                            .padding(KnotworkTheme.spacing.sp3),
                    )
                }
            }
        }
    }
}

// ------------------------- McpServerConfigContent -------------------------

/**
 * Full-screen MCP-server configuration surface. Hosts the rich form
 * (URL, optional display name, transport selector, repeating headers)
 * for both Add (`form.editingUrl == null`) and Edit
 * (`form.editingUrl == <original URL>`) flows.
 *
 * The host composable (app-layer screen) owns the [AddMcpServerForm]
 * state and translates submissions into persistence calls; this
 * composable renders the chrome and dispatches per-field callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun McpServerConfigContent(
    form: AddMcpServerForm,
    modifier: Modifier = Modifier,
    callbacks: McpServerConfigCallbacks = noopMcpServerConfigCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
                TopAppBar(
                    title = {
                        Text(
                            text = if (form.isEdit) {
                                stringResource(R.string.knotwork_tools_form_title_edit)
                            } else {
                                stringResource(R.string.knotwork_tools_form_title_add)
                            },
                            style = KnotworkTextStyles.TitleMd,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = callbacks.onCancel) {
                            Icon(
                                imageVector = AppIcons.Back,
                                contentDescription = stringResource(R.string.knotwork_tools_add_form_cancel),
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
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(state = rememberScrollState())
                .padding(KnotworkTheme.spacing.sp4),
        ) {
            // The placeholder used to read `https://… or mcp://host:port`, which
            // sent the first external tester looking for a port number he had no
            // way to know. It is now one real address, and the question it kept
            // raising is answered by the hint rather than by the field.
            var addressHintOpen by remember { mutableStateOf(false) }
            val addressLabel = stringResource(R.string.knotwork_tools_add_form_header)
            // FlowRow, not Row: an unweighted label measured against the full
            // width leaves the 28 dp glyph nothing, which is the same squeeze
            // the settings approval row had to be moved away from.
            // `Row` with both children centred on the same axis. The FlowRow
            // used here first left the glyph sitting below the label's baseline:
            // the label is 11 sp mono and the glyph's target is 28 dp, so
            // aligning the *line boxes* puts them visibly out of line. Weighting
            // the label instead keeps the glyph beside it and still lets it wrap.
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormSectionLabel(text = addressLabel, modifier = Modifier.weight(1f, fill = false))
                KnotworkHelpEntry(
                    settingName = addressLabel,
                    expanded = addressHintOpen,
                    onToggle = { addressHintOpen = !addressHintOpen },
                )
            }
            OutlinedFormTextField(
                value = form.url,
                onValueChange = callbacks.onUrlChange,
                placeholder = stringResource(R.string.knotwork_tools_add_form_placeholder),
                isError = form.urlError != null,
            )
            KnotworkHintPanel(
                visible = addressHintOpen,
                text = stringResource(R.string.knotwork_tools_add_form_address_hint),
            )
            if (form.urlError != null) {
                Text(
                    text = form.urlError,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.signalErrorText,
                )
            }
            // Unencrypted traffic to a private address is refused until the user
            // approves this exact origin. Shown inline rather than as a dialog so
            // it is visible while the address is still being typed.
            form.cleartextConsentOrigin?.let { origin ->
                KnotworkWarningBanner(
                    text = stringResource(R.string.knotwork_tools_cleartext_consent_body, origin),
                    actionLabel = stringResource(R.string.knotwork_tools_cleartext_consent_action),
                    onAction = callbacks.onApproveCleartext,
                    testTag = MCP_CLEARTEXT_CONSENT_TAG,
                )
            }

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_name_label))
            OutlinedFormTextField(
                value = form.name,
                onValueChange = callbacks.onNameChange,
                placeholder = stringResource(R.string.knotwork_tools_form_name_placeholder),
                isError = false,
            )

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_transport_label))
            Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                // Render the selectable options plus, if the form was loaded
                // with a non-selectable choice (e.g. an older "Streamable HTTP"
                // pick), surface that chip too so the user can see what's
                // persisted instead of being silently downgraded.
                val visible = McpTransportOption.entries.filter { it.selectable || it == form.transport }
                visible.forEach { option ->
                    TransportChip(
                        option = option,
                        selected = form.transport == option,
                        onClick = { callbacks.onTransportSelect(option) },
                    )
                }
            }

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_auth_label))
            Row(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(state = rememberScrollState()),
            ) {
                McpAuthSelector.entries.forEach { option ->
                    AuthChip(
                        option = option,
                        selected = form.authType == option,
                        onClick = { callbacks.onAuthTypeSelect(option) },
                    )
                }
            }
            AuthFields(form = form, callbacks = callbacks)

            FormSectionLabel(text = stringResource(R.string.knotwork_tools_form_headers_label))
            form.headers.forEachIndexed { index, row ->
                HeaderRow(
                    row = row,
                    onKeyChange = { newKey -> callbacks.onHeaderChange(index, newKey, row.value) },
                    onValueChange = { newValue -> callbacks.onHeaderChange(index, row.key, newValue) },
                    onRemove = { callbacks.onHeaderRemove(index) },
                )
            }
            KnotworkTextButton(
                text = stringResource(R.string.knotwork_tools_form_headers_add),
                onClick = callbacks.onHeaderAdd,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                KnotworkTextButton(
                    text = stringResource(R.string.knotwork_tools_add_form_cancel),
                    onClick = callbacks.onCancel,
                )
                Spacer(modifier = Modifier.size(KnotworkTheme.spacing.sp2))
                KnotworkPrimaryButton(
                    text = if (form.isEdit) {
                        stringResource(R.string.knotwork_tools_form_submit_save)
                    } else {
                        stringResource(R.string.knotwork_tools_add_form_submit)
                    },
                    onClick = callbacks.onSubmit,
                    enabled = form.canSubmit,
                )
            }
        }
    }
}

/**
 * Lines allowed for an MCP tool's name in the expanded server list. See the
 * comment at the call site: one line made same-prefix tools indistinguishable
 * in a list where each row has its own enable toggle.
 */
private const val MCP_TOOL_NAME_MAX_LINES = 2

/** Test tag for the unencrypted-connection consent banner in the MCP server form. */
const val MCP_CLEARTEXT_CONSENT_TAG: String = "mcp_cleartext_consent_banner"
