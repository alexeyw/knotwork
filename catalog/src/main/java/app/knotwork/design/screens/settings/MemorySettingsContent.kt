package app.knotwork.design.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.misc.KnotworkStatCell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Memory category sub-screen. Basic tier surfaces the stat cells and the three
 * lifecycle toggles (auto-extract, compaction, chat-history compression); the
 * Advanced disclosure holds the tuning sliders, the embedding selector, verbose
 * logging and the export / import / re-embed / clear data actions.
 *
 * @param state Immutable Memory state.
 * @param modifier Layout modifier.
 * @param callbacks Interaction callbacks.
 * @param advancedExpanded Seeds the Advanced disclosure open (snapshot fixtures).
 */
@Composable
fun MemorySettingsContent(
    state: MemorySettingsViewState,
    modifier: Modifier = Modifier,
    callbacks: SettingsCallbacks = noopSettingsCallbacks(),
    advancedExpanded: Boolean = false,
) {
    val count = MEMORY_BASIC_TOGGLES + state.advancedSliders.size + MEMORY_ADVANCED_EXTRAS
    CategoryScaffold(
        title = stringResource(R.string.knotwork_settings_cat_memory_title),
        subtitle = stringResource(R.string.knotwork_settings_count, count),
        onBack = callbacks.onBack,
        modifier = modifier,
        overlay = {
            if (state.destructiveAction != null) {
                DestructiveDialogHost(payload = state.destructiveAction, callbacks = callbacks)
            }
        },
    ) {
        MemoryStatsCard(state.stats)
        SettingsAnchor(anchorKey = SettingsRowAnchors.AUTO_EXTRACT_ENABLED) {
            IconToggleRow(
                icon = AppIcons.Bolt,
                title = state.autoExtractLabel,
                state = "",
                checked = state.autoExtractEnabled,
                onCheckedChange = callbacks.onAutoExtractToggle,
            )
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.MEMORY_COMPACTION_ENABLED) {
            IconToggleRow(
                icon = AppIcons.Refresh,
                title = state.compactionLabel,
                state = "",
                checked = state.compactionEnabled,
                onCheckedChange = callbacks.onMemoryCompactionToggle,
            )
        }
        SettingsAnchor(anchorKey = SettingsRowAnchors.CHAT_HISTORY_COMPRESSION_ENABLED) {
            IconToggleRow(
                icon = AppIcons.History,
                title = state.chatHistoryCompressionLabel,
                state = "",
                checked = state.chatHistoryCompressionEnabled,
                onCheckedChange = callbacks.onChatHistoryCompressionToggle,
            )
        }
        AdvancedDisclosure(initiallyExpanded = advancedExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            ) {
                SettingSliderList(
                    sliders = state.advancedSliders,
                    tagPrefix = MEMORY_PARAM_ROW_TAG_PREFIX,
                    onChange = callbacks.onMemorySliderChange,
                )
                SettingsAnchor(anchorKey = SettingsRowAnchors.ACTIVE_EMBEDDING_PROVIDER_ID) {
                    EmbeddingProviderDropdownRow(
                        title = state.embeddingTitle,
                        selectedLabel = state.selectedEmbeddingLabel,
                        options = state.embeddingOptions,
                        onSelected = callbacks.onEmbeddingProviderSelected,
                    )
                }
                SettingsAnchor(anchorKey = SettingsRowAnchors.VERBOSE_MEMORY_LOGGING_ENABLED) {
                    IconToggleRow(
                        icon = AppIcons.Ram,
                        title = stringResource(R.string.knotwork_settings_verbose_memory_logging_label),
                        state = "",
                        checked = state.verboseLoggingEnabled,
                        onCheckedChange = callbacks.onVerboseMemoryLoggingToggle,
                    )
                }
                if (state.reembedBanner != null) {
                    ReembedBanner(
                        text = state.reembedBanner,
                        buttonLabel = state.reembedLabel,
                        onReembedClick = callbacks.onReembedClick,
                    )
                }
                if (state.validationError != null) {
                    Text(
                        text = state.validationError,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.signalError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(MEMORY_VALIDATION_ERROR_TAG),
                    )
                }
                SettingsAnchor(anchorKey = SettingsRowAnchors.MEMORY_ACTIONS) {
                    // The row is a button strip with no label of its own, so the
                    // header carries both the name and the help glyph — without
                    // it, Re-embed (the one action here with a consequence you
                    // cannot see) would have nowhere to explain itself.
                    SettingsFieldHeader(
                        title = stringResource(R.string.knotwork_settings_memory_actions_title),
                    )
                    MemoryActionButtons(state, callbacks)
                    SettingsHintBody()
                }
                if (state.reembedProgressPercent != null) {
                    ReembedProgress(progressPercent = state.reembedProgressPercent)
                }
            }
        }
    }
}

@Composable
private fun MemoryStatsCard(stats: List<MemoryStatCell>) {
    Surface(
        shape = KnotworkTheme.shapes.md,
        color = KnotworkTheme.extended.surface1,
        border = BorderStroke(SectionCardBorder, KnotworkTheme.extended.divider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp3),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        ) {
            stats.forEach { cell ->
                KnotworkStatCell(value = cell.value, label = cell.label, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemoryActionButtons(state: MemorySettingsViewState, callbacks: SettingsCallbacks) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        KnotworkSecondaryButton(
            text = state.exportLabel,
            onClick = callbacks.onExportMemoryClick,
            size = KnotworkButtonSize.Sm,
            modifier = Modifier.weight(1f),
        )
        KnotworkSecondaryButton(
            text = state.importLabel,
            onClick = callbacks.onImportMemoryClick,
            size = KnotworkButtonSize.Sm,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        KnotworkSecondaryButton(
            text = state.reembedLabel,
            onClick = callbacks.onReembedClick,
            size = KnotworkButtonSize.Sm,
            modifier = Modifier.weight(1f),
        )
        KnotworkSecondaryButton(
            text = state.clearLabel,
            onClick = callbacks.onClearMemoryClick,
            size = KnotworkButtonSize.Sm,
            destructive = true,
            modifier = Modifier.weight(1f),
        )
    }
}

private const val MEMORY_BASIC_TOGGLES = 3
private const val MEMORY_ADVANCED_EXTRAS = 2
