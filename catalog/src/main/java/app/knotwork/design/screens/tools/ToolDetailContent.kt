package app.knotwork.design.screens.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import app.knotwork.design.R
import app.knotwork.design.components.controls.KnotworkSegmentedControl
import app.knotwork.design.components.misc.StripedPlaceholder
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

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
                        color = KnotworkTheme.extended.signalError,
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
