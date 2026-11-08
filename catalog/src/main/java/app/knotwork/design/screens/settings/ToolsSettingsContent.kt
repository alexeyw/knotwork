package app.knotwork.design.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme

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
            ToolApprovalRow(
                options = listOf(state.approveAllLabel, state.approveSensitiveLabel, state.approveNeverLabel),
                selectedIndex = state.approveSelection.toIndex(),
                onSelect = { callbacks.onApproveSelectionChange(approveOptionFromIndex(it)) },
            )
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
