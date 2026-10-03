@file:Suppress("MatchingDeclarationName") // Hosts the sheet body + its supporting DTOs.

package app.knotwork.design.screens.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.pipelineeditor.NodeType
import app.knotwork.design.components.pipelineeditor.headerOnColor
import app.knotwork.design.components.pipelineeditor.headerTint
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * A single row in the [PromptPresetPickerSheet] body.
 *
 * @property id stable preset id (used for selection keying).
 * @property name display name (bold title).
 * @property description one-line subtitle rendered under the name.
 * @property tags lower-case kebab labels rendered as the trailing metadata.
 * @property tokens approximate token count rendered as the leading metadata
 *   (`84 tok` style). The caller computes this — typically chars / 4.
 * @property isCurrent `true` when this row's preset is the one currently
 *   bound to the field that opened the picker; surfaces a `● CURRENT` pill.
 */
data class PromptPresetPickerRow(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val tokens: Int,
    val isCurrent: Boolean = false,
)

/**
 * Single tag-filter chip rendered above the row list. The `All` entry is
 * modelled as `null` [tag] so the picker has a stable "clear filter" state.
 *
 * @property tag the tag string this chip selects, or `null` for the
 *   special leading "All" chip.
 * @property label visible chip label (e.g. `"All"`, `"concise"`).
 * @property count number rendered after the label as a small subscript.
 */
data class PromptPresetTagChip(val tag: String?, val label: String, val count: Int)

/** Tab identifier in the [PromptPresetPickerSheet]'s segmented header. */
enum class PromptPresetPickerTab { BUNDLED, MINE }

/**
 * Immutable view-state consumed by [PromptPresetPickerSheet].
 *
 * @property nodeType the target node type — rendered as a coloured pill in
 *   the subtitle so the user has an immediate visual scope.
 * @property selectedTab which tab is active (Bundled vs Mine).
 * @property bundledCount total bundled presets for [nodeType] — surfaced in
 *   the Bundled tab counter (e.g. `Bundled 3`).
 * @property mineCount total user presets for [nodeType] — surfaces in the
 *   Mine tab counter.
 * @property searchQuery current search-field value. Filtering / debouncing
 *   is the caller's responsibility; the sheet just renders [rows].
 * @property tagChips full chip row including the leading `All` entry.
 * @property selectedTagFilter currently-applied tag chip (or the special
 *   `null`-tag "All" sentinel — encoded as the chip with `tag == null`).
 * @property rows the rows to render in the body. Already filtered.
 * @property selectedRowId currently-selected row id, or `null` when nothing
 *   is selected (Use-prompt button disabled).
 * @property emptyMessage rendered when [rows] is empty (e.g. "No bundled
 *   presets for this node type" or "No matches for your search").
 */
data class PromptPresetPickerViewState(
    val nodeType: NodeType,
    val selectedTab: PromptPresetPickerTab = PromptPresetPickerTab.BUNDLED,
    val bundledCount: Int = 0,
    val mineCount: Int = 0,
    val tagChips: List<PromptPresetTagChip> = emptyList(),
    val selectedTagFilter: String? = null,
    val rows: List<PromptPresetPickerRow> = emptyList(),
    val selectedRowId: String? = null,
    val emptyMessage: String = "",
)

/** Localised display strings consumed by [PromptPresetPickerSheet]. */
data class PromptPresetPickerStrings(
    val title: String = "Prompt presets",
    val subtitleFormat: String = "%1\$s · pick a preset for this node",
    val tabBundled: String = "Bundled",
    val tabMine: String = "Mine",
    val allChip: String = "All",
    val currentBadge: String = "CURRENT",
    val tokensSuffix: String = "tok",
    val cancel: String = "Cancel",
    val usePrompt: String = "Use prompt",
    val previewCd: String = "Preview",
    val closeCd: String = "Close",
)

/** One-shot callbacks consumed by [PromptPresetPickerSheet]. */
class PromptPresetPickerCallbacks(
    val onTabSelected: (PromptPresetPickerTab) -> Unit = {},
    val onTagSelected: (String?) -> Unit = {},
    val onRowSelected: (String) -> Unit = {},
    val onPreviewRow: (String) -> Unit = {},
    val onUsePrompt: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * Stateless bottom-sheet body for the prompt-preset picker: title row with
 * search + close icons, coloured
 * node-type pill subtitle, Bundled / Mine tab bar with counters, search
 * field, leading `All N` chip + per-tag filter chips, radio-style row
 * list with optional `● CURRENT` pill and per-row preview icon, sticky
 * bottom Cancel + ✓ Use prompt bar.
 *
 * The composable does not own its `ModalBottomSheet` wrapper — the
 * caller hosts it so navigation / IME / scrim handling can be tuned at
 * the screen level.
 *
 * @param state immutable view state.
 * @param strings localised display strings.
 * @param callbacks one-shot user-action callbacks.
 * @param modifier optional layout modifier applied to the body root.
 */
@Composable
fun PromptPresetPickerSheet(
    state: PromptPresetPickerViewState,
    modifier: Modifier = Modifier,
    strings: PromptPresetPickerStrings = PromptPresetPickerStrings(),
    callbacks: PromptPresetPickerCallbacks = PromptPresetPickerCallbacks(),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        HeaderRow(state = state, strings = strings, callbacks = callbacks)
        TabRowSection(state = state, strings = strings, callbacks = callbacks)
        if (state.tagChips.isNotEmpty()) {
            TagFilterRow(state = state, callbacks = callbacks)
        }
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        BodyList(state = state, strings = strings, callbacks = callbacks)
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        ActionRow(state = state, strings = strings, callbacks = callbacks)
    }
}

@Composable
private fun HeaderRow(
    state: PromptPresetPickerViewState,
    strings: PromptPresetPickerStrings,
    callbacks: PromptPresetPickerCallbacks,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.title,
                style = KnotworkTextStyles.TitleLg,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            ) {
                NodeTypePill(nodeType = state.nodeType)
                Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp1))
                Text(
                    text = "· " + strings.subtitleFormat.substringAfter("·").trim(),
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        IconButton(onClick = callbacks.onClose) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = strings.closeCd,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Small pill rendered next to the title — coloured to the node-type hue. */
@Composable
private fun NodeTypePill(nodeType: NodeType) {
    val tint = nodeType.headerTint()
    val onTint = headerOnColor(tint)
    // JetBrains Mono 700, 11 px, +0.6 tracking, height 22, padding 0 9, radius xs.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(NODE_KIND_PILL_HEIGHT)
            .clip(KnotworkTheme.shapes.xs)
            .background(color = tint)
            .padding(horizontal = NODE_KIND_PILL_PADDING_H),
    ) {
        Text(
            text = nodeType.name.uppercase(),
            style = KnotworkTextStyles.MonoSm.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
            ),
            color = onTint,
        )
    }
}

private val NODE_KIND_PILL_HEIGHT = 22.dp
private val NODE_KIND_PILL_PADDING_H = 9.dp

@Composable
private fun TabRowSection(
    state: PromptPresetPickerViewState,
    strings: PromptPresetPickerStrings,
    callbacks: PromptPresetPickerCallbacks,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        PickerTab(
            label = strings.tabBundled,
            count = state.bundledCount,
            selected = state.selectedTab == PromptPresetPickerTab.BUNDLED,
            onClick = { callbacks.onTabSelected(PromptPresetPickerTab.BUNDLED) },
            modifier = Modifier.weight(1f),
        )
        PickerTab(
            label = strings.tabMine,
            count = state.mineCount,
            selected = state.selectedTab == PromptPresetPickerTab.MINE,
            onClick = { callbacks.onTabSelected(PromptPresetPickerTab.MINE) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PickerTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val labelColor = if (selected) accent else KnotworkTheme.extended.onSurfaceMuted
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = KnotworkTheme.spacing.sp2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = KnotworkTextStyles.LabelLg,
                color = labelColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp1))
            CountBubble(count = count, selected = selected)
        }
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp1))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TAB_INDICATOR_HEIGHT.dp)
                .background(color = if (selected) accent else Color.Transparent),
        )
    }
}

/** Small count chip rendered next to a tab label (`Bundled 3`). */
@Composable
private fun CountBubble(count: Int, selected: Boolean) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        KnotworkTheme.extended.surface2
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        KnotworkTheme.extended.onSurfaceMuted
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color = bg)
            .padding(horizontal = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = count.toString(),
            style = KnotworkTextStyles.LabelSm,
            color = fg,
        )
    }
}

@Composable
private fun TagFilterRow(state: PromptPresetPickerViewState, callbacks: PromptPresetPickerCallbacks) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        contentPadding = PaddingValues(vertical = KnotworkTheme.spacing.sp1),
    ) {
        items(state.tagChips, key = { it.tag ?: "__all__" }) { chip ->
            val isSelected = chip.tag == state.selectedTagFilter
            TagPill(
                label = chip.label,
                count = chip.count,
                selected = isSelected,
                onClick = { callbacks.onTagSelected(chip.tag) },
            )
        }
    }
    Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp1))
}

@Composable
private fun TagPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val border = if (selected) {
        null
    } else {
        Modifier.border(
            width = 1.dp,
            color = KnotworkTheme.extended.outlineStrong,
            shape = KnotworkTheme.shapes.full,
        )
    }
    val fg = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        KnotworkTheme.extended.onSurface2
    }
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .background(color = bg)
            .then(border ?: Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = KnotworkTextStyles.LabelMd,
                color = fg,
            )
            Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp1))
            Text(
                text = count.toString(),
                style = KnotworkTextStyles.LabelSm,
                color = fg.copy(alpha = 0.70f),
            )
        }
    }
}

@Composable
private fun BodyList(
    state: PromptPresetPickerViewState,
    strings: PromptPresetPickerStrings,
    callbacks: PromptPresetPickerCallbacks,
) {
    if (state.rows.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = EMPTY_HEIGHT.dp, max = EMPTY_HEIGHT.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.emptyMessage,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LIST_MIN_HEIGHT.dp, max = LIST_MAX_HEIGHT.dp),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        contentPadding = PaddingValues(vertical = KnotworkTheme.spacing.sp1),
    ) {
        items(state.rows, key = { it.id }) { row ->
            PresetRow(
                row = row,
                nodeType = state.nodeType,
                selected = row.id == state.selectedRowId,
                strings = strings,
                onSelect = { callbacks.onRowSelected(row.id) },
                onPreview = { callbacks.onPreviewRow(row.id) },
            )
        }
    }
}

@Composable
private fun PresetRow(
    row: PromptPresetPickerRow,
    nodeType: NodeType,
    selected: Boolean,
    strings: PromptPresetPickerStrings,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    val accent = nodeType.headerTint()
    val containerColor = if (selected) accent.copy(alpha = 0.12f) else KnotworkTheme.extended.surface1
    val borderModifier = if (selected) {
        Modifier.border(width = 1.dp, color = accent, shape = KnotworkTheme.shapes.md)
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = containerColor)
            .then(borderModifier)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(
                    selectedColor = accent,
                    unselectedColor = KnotworkTheme.extended.outlineStrong,
                ),
            )
            Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp2))
            Text(
                text = row.name,
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.isCurrent) {
                CurrentBadge(label = strings.currentBadge, accent = accent)
                Spacer(modifier = Modifier.width(KnotworkTheme.spacing.sp1))
            }
            IconButton(onClick = onPreview, modifier = Modifier.size(PREVIEW_ICON_TARGET.dp)) {
                Icon(
                    imageVector = AppIcons.Search,
                    contentDescription = strings.previewCd,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        if (row.description.isNotBlank()) {
            Text(
                text = row.description,
                style = KnotworkTextStyles.BodySm,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = ROW_BODY_INSET.dp),
                maxLines = MAX_DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MetadataRow(
            tokens = row.tokens,
            tokensSuffix = strings.tokensSuffix,
            tags = row.tags,
            modifier = Modifier.padding(start = ROW_BODY_INSET.dp),
        )
    }
}

/** Compact `● CURRENT` pill rendered when the row's prompt matches the field value. */
@Composable
private fun CurrentBadge(label: String, accent: Color) {
    val onAccent = headerOnColor(accent)
    // JetBrains Mono 700, 9.5 px, +0.6 tracking, height 20, radius full, 5 px leading dot.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CURRENT_PILL_DOT_GAP),
        modifier = Modifier
            .height(CURRENT_PILL_HEIGHT)
            .clip(KnotworkTheme.shapes.full)
            .background(color = accent)
            .padding(horizontal = CURRENT_PILL_PADDING_H),
    ) {
        Box(
            modifier = Modifier
                .size(CURRENT_PILL_DOT_SIZE)
                .background(color = onAccent, shape = CircleShape),
        )
        Text(
            text = label,
            style = KnotworkTextStyles.MonoSm.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 9.5.sp,
                letterSpacing = 0.6.sp,
            ),
            color = onAccent,
        )
    }
}

private val CURRENT_PILL_HEIGHT = 20.dp
private val CURRENT_PILL_PADDING_H = 6.dp
private val CURRENT_PILL_DOT_SIZE = 5.dp
private val CURRENT_PILL_DOT_GAP = 4.dp

/** Mono row showing `218 tok · cot · reasoning` style metadata. */
@Composable
private fun MetadataRow(tokens: Int, tokensSuffix: String, tags: List<String>, modifier: Modifier = Modifier) {
    val parts = buildList {
        add("$tokens $tokensSuffix")
        addAll(tags)
    }
    Text(
        text = parts.joinToString(separator = " · "),
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ActionRow(
    state: PromptPresetPickerViewState,
    strings: PromptPresetPickerStrings,
    callbacks: PromptPresetPickerCallbacks,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KnotworkTextButton(text = strings.cancel, onClick = callbacks.onCancel)
        Spacer(modifier = Modifier.weight(1f))
        KnotworkPrimaryButton(
            text = "✓ " + strings.usePrompt,
            onClick = callbacks.onUsePrompt,
            enabled = state.selectedRowId != null,
            size = KnotworkButtonSize.Md,
        )
    }
}

/** Tab-underline thickness. */
private const val TAB_INDICATOR_HEIGHT: Int = 2

/** Tap-target for the per-row magnifier preview icon. */
private const val PREVIEW_ICON_TARGET: Int = 28

/**
 * Indent for the row's description / metadata to align under the title.
 * Tuned so the body text starts roughly under the title (radio button is
 * 24 dp wide + 8 dp gap).
 */
private const val ROW_BODY_INSET: Int = 32

/** Body-list max height so the sheet stays under the IME on small phones. */
private const val LIST_MAX_HEIGHT: Int = 420

/** Body-list min height so the sheet doesn't collapse on a single short row. */
private const val LIST_MIN_HEIGHT: Int = 200

/** Empty-state row height. */
private const val EMPTY_HEIGHT: Int = 160

/** Max lines for the per-row description teaser. */
private const val MAX_DESCRIPTION_LINES: Int = 2
