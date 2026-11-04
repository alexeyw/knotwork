package app.knotwork.design.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.knotwork.design.R
import app.knotwork.design.components.controls.KnotworkSegmentedControl
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Tools-&-workspace category sub-screen. Basic tier surfaces the tool-approval
 * policy, the two safety guardrail toggles and the "Manage tools / MCP" link;
 * the Advanced disclosure links to the allowed-HTTP-domains surface and holds
 * the tool / workspace ceilings.
 *
 * @param state Immutable Tools state.
 * @param modifier Layout modifier.
 * @param callbacks Interaction callbacks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsSettingsContent(
    state: ToolsSettingsViewState,
    modifier: Modifier = Modifier,
    callbacks: SettingsCallbacks = noopSettingsCallbacks(),
    advancedExpanded: Boolean = false,
) {
    CategoryScaffold(
        title = stringResource(R.string.knotwork_settings_cat_tools_title),
        subtitle = stringResource(
            R.string.knotwork_settings_count,
            TOOLS_BASIC_ROW_COUNT + state.advancedSliders.size,
        ),
        onBack = callbacks.onBack,
        modifier = modifier,
    ) {
        SettingsAnchor(anchorKey = SettingsRowAnchors.TOOL_APPROVAL_POLICY) {
            val approveTitle = stringResource(R.string.knotwork_settings_restrictions_approve)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // FlowRow, like every other glyph site: a plain Row measures the
                // unweighted label against the whole 1f column and leaves the
                // 28 dp glyph nothing — and this label already fills both lines
                // at 1x, so the glyph would have been squeezed to zero width.
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
        SettingsAnchor(anchorKey = SettingsRowAnchors.BLOCK_NETWORK_FROM_LOCAL_MODEL) {
            IconToggleRow(
                icon = AppIcons.Block,
                title = stringResource(R.string.knotwork_settings_restrictions_block_network),
                state = "",
                checked = state.blockNetwork,
                onCheckedChange = callbacks.onBlockNetworkChange,
            )
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.LINK_TOOLS_SCREEN) {
            NavLinkRow(
                icon = AppIcons.Tool,
                title = stringResource(R.string.knotwork_settings_tools_manage_title),
                state = stringResource(R.string.knotwork_settings_tools_manage_subtitle),
                onClick = callbacks.onOpenManageTools,
            )
        }
        AdvancedDisclosure(initiallyExpanded = advancedExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            ) {
                SettingsAnchor(anchorKey = SettingsRowAnchors.LINK_FILES_DOMAINS) {
                    NavLinkRow(
                        icon = AppIcons.Block,
                        title = stringResource(R.string.knotwork_settings_tools_domains_title),
                        state = stringResource(R.string.knotwork_settings_tools_domains_subtitle),
                        onClick = callbacks.onOpenAllowedDomains,
                    )
                }
                SettingSliderList(
                    sliders = state.advancedSliders,
                    tagPrefix = TOOLS_SLIDER_TAG_PREFIX,
                    onChange = callbacks.onToolsSliderChange,
                )
            }
        }
    }
}

private const val TOOLS_BASIC_ROW_COUNT = 3
