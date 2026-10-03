@file:Suppress("MatchingDeclarationName") // File hosts the picker fun + supporting helpers.

package app.knotwork.android.presentation.ui.orchestrator.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.presentation.ui.pipeline.editor.config.NodeTypeMapper
import app.knotwork.design.screens.prompts.PromptPresetPickerCallbacks
import app.knotwork.design.screens.prompts.PromptPresetPickerRow
import app.knotwork.design.screens.prompts.PromptPresetPickerSheet
import app.knotwork.design.screens.prompts.PromptPresetPickerStrings
import app.knotwork.design.screens.prompts.PromptPresetPickerTab
import app.knotwork.design.screens.prompts.PromptPresetPickerViewState
import app.knotwork.design.screens.prompts.PromptPresetTagChip

/**
 * Tab identifiers backing the picker's segmented header (app-side mirror of
 * the catalog [PromptPresetPickerTab]). Kept here so the editor screen
 * doesn't need to depend on a catalog enum just to declare local state.
 */
internal enum class PromptPresetTab { BUNDLED, MINE }

/**
 * Bottom-sheet picker for prompt presets.
 *
 * Hosts the catalog [PromptPresetPickerSheet] body inside a
 * [ModalBottomSheet] and owns the screen-local interaction state: tab
 * selection, search input (with 200 ms debounce), tag filter, currently
 * selected row. The actual filter logic is delegated to [filterPresets] so
 * the rule can be unit-tested without spinning up Compose.
 *
 * @param nodeType the [NodeType] the active node lives under; rendered as a
 *  coloured pill in the sheet subtitle.
 * @param bundled bundled presets (read-only, ship inside the APK) already
 *  filtered to [nodeType].
 * @param mine user-saved presets already filtered to [nodeType].
 * @param currentPrompt the systemPrompt currently bound to the field that
 *  opened the picker — used to mark the corresponding row with a
 *  `● CURRENT` pill so the user can see what the field already holds.
 * @param onApply invoked with the picked preset's `systemPrompt` when the
 *  user taps the primary "Use prompt" action. The sheet dismisses itself
 *  afterwards.
 * @param onPreview invoked with a preset's `systemPrompt` when the user taps
 *  the per-row preview icon; the screen renders its own
 *  `PromptPreviewBottomSheet`.
 * @param onDismiss invoked when the user dismisses the sheet without
 *  applying.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptPresetPickerDialog(
    nodeType: NodeType,
    bundled: List<PromptPreset>,
    mine: List<PromptPreset>,
    currentPrompt: String,
    onApply: (systemPrompt: String) -> Unit,
    onPreview: (systemPrompt: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(PromptPresetTab.BUNDLED) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var selectedRowId by remember { mutableStateOf<String?>(null) }

    // Reset tag chip and selection when tabs flip — the union of tags is
    // tab-specific (a tag in Bundled may not exist in Mine), and a stale
    // selectedRowId would refer to a row not in the new list.
    LaunchedEffect(selectedTab) {
        selectedTag = null
        selectedRowId = null
    }

    val sourceList = when (selectedTab) {
        PromptPresetTab.BUNDLED -> bundled
        PromptPresetTab.MINE -> mine
    }
    val tagCounts = remember(sourceList) {
        sourceList.flatMap { it.tags }.groupingBy { it }.eachCount()
    }
    val filtered = remember(sourceList, selectedTag) {
        filterPresetsByTags(
            presets = sourceList,
            selectedTags = selectedTag?.let { setOf(it) } ?: emptySet(),
        )
    }
    // A selection is only applicable while its row survives the current tag
    // filter. Changing the tag can hide the picked row; collapsing the id to
    // null when it is absent from `filtered` disables the catalog "Use prompt"
    // action and guards against applying a hidden preset from the previous
    // filter.
    val visibleSelectedRowId = selectedRowId?.takeIf { id -> filtered.any { it.id == id } }

    val strings = pickerStrings()
    val tagChips = buildTagChips(
        sourceListCount = sourceList.size,
        tagCounts = tagCounts,
        allLabel = strings.allChip,
    )
    val rows = filtered.map { preset ->
        PromptPresetPickerRow(
            id = preset.id,
            name = preset.name,
            description = preset.description,
            tags = preset.tags,
            tokens = estimateTokens(preset.systemPrompt),
            isCurrent = preset.systemPrompt == currentPrompt,
        )
    }
    val state = PromptPresetPickerViewState(
        nodeType = NodeTypeMapper.toCatalog(nodeType),
        selectedTab = when (selectedTab) {
            PromptPresetTab.BUNDLED -> PromptPresetPickerTab.BUNDLED
            PromptPresetTab.MINE -> PromptPresetPickerTab.MINE
        },
        bundledCount = bundled.size,
        mineCount = mine.size,
        tagChips = tagChips,
        selectedTagFilter = selectedTag,
        rows = rows,
        selectedRowId = visibleSelectedRowId,
        emptyMessage = when {
            sourceList.isEmpty() && selectedTab == PromptPresetTab.BUNDLED ->
                stringResource(R.string.prompt_preset_picker_empty_bundled)
            sourceList.isEmpty() ->
                stringResource(R.string.prompt_preset_picker_empty_mine)
            else -> stringResource(R.string.prompt_preset_picker_empty_filtered)
        },
    )
    val callbacks = PromptPresetPickerCallbacks(
        onTabSelected = { catalogTab ->
            selectedTab = when (catalogTab) {
                PromptPresetPickerTab.BUNDLED -> PromptPresetTab.BUNDLED
                PromptPresetPickerTab.MINE -> PromptPresetTab.MINE
            }
        },
        onTagSelected = { tag -> selectedTag = tag },
        onRowSelected = { rowId -> selectedRowId = rowId },
        onPreviewRow = { rowId ->
            val preset = sourceList.firstOrNull { it.id == rowId } ?: return@PromptPresetPickerCallbacks
            onPreview(preset.systemPrompt)
        },
        onUsePrompt = {
            val pickedId = visibleSelectedRowId ?: return@PromptPresetPickerCallbacks
            val preset = filtered.firstOrNull { it.id == pickedId } ?: return@PromptPresetPickerCallbacks
            onApply(preset.systemPrompt)
            onDismiss()
        },
        onCancel = onDismiss,
        onClose = onDismiss,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        PromptPresetPickerSheet(state = state, strings = strings, callbacks = callbacks)
    }
}

/**
 * Composes the tag-chip row consumed by [PromptPresetPickerSheet], starting
 * with a leading "All" chip whose count equals the unfiltered source list
 * size, followed by every distinct tag sorted alphabetically.
 */
internal fun buildTagChips(
    sourceListCount: Int,
    tagCounts: Map<String, Int>,
    allLabel: String,
): List<PromptPresetTagChip> = buildList {
    add(PromptPresetTagChip(tag = null, label = allLabel, count = sourceListCount))
    tagCounts.entries
        .sortedBy { it.key }
        .forEach { (tag, count) ->
            add(PromptPresetTagChip(tag = tag, label = tag, count = count))
        }
}

/**
 * Filters [presets] by tag-intersection: a preset matches when every selected
 * tag is present on the preset (AND semantics, mirrors how filter chips usually
 * compose). Pure function so it can be unit-tested without spinning up Compose.
 */
internal fun filterPresetsByTags(presets: List<PromptPreset>, selectedTags: Set<String>): List<PromptPreset> {
    if (selectedTags.isEmpty()) return presets
    return presets.filter { preset ->
        selectedTags.all { tag -> preset.tags.any { it.equals(tag, ignoreCase = true) } }
    }
}

/**
 * Approximates the token count of a prompt by `length / 4` — the canonical
 * GPT-style rule of thumb. Good enough to surface as a hint in the picker
 * row; exact tokenisation depends on the runtime model and is not worth
 * the round-trip cost.
 */
internal fun estimateTokens(prompt: String): Int = prompt.length / CHARS_PER_TOKEN

@Composable
private fun pickerStrings(): PromptPresetPickerStrings = PromptPresetPickerStrings(
    title = stringResource(R.string.prompt_preset_picker_title),
    subtitleFormat = stringResource(R.string.prompt_preset_picker_subtitle),
    tabBundled = stringResource(R.string.prompt_preset_picker_tab_bundled),
    tabMine = stringResource(R.string.prompt_preset_picker_tab_mine),
    allChip = stringResource(R.string.prompt_preset_picker_all_chip),
    currentBadge = stringResource(R.string.prompt_preset_picker_current_badge),
    tokensSuffix = stringResource(R.string.prompt_preset_picker_tokens_suffix),
    cancel = stringResource(R.string.prompt_preset_picker_action_cancel),
    usePrompt = stringResource(R.string.prompt_preset_picker_action_use_prompt),
    previewCd = stringResource(R.string.prompt_preset_picker_action_preview),
    closeCd = stringResource(R.string.common_close),
)

/** GPT-style rule-of-thumb: ~4 characters per token. */
private const val CHARS_PER_TOKEN: Int = 4
