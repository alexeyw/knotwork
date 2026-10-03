@file:Suppress("MatchingDeclarationName") // File hosts PresetPickerSheetBody + its row and view-state payloads.

package app.knotwork.design.screens.pipelines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Background alpha for the selected picker row tint. */
private const val SELECTED_BG_ALPHA = 0.10f

/** Border thickness for the selected picker row. */
private val SELECTED_BORDER_WIDTH = 1.5.dp

/** Fraction of the viewport the picker sheet pins to so the footer stays anchored. */
private const val SHEET_HEIGHT_FRACTION = 0.85f

/**
 * One selectable preset in the picker.
 *
 * Deliberately not [PresetRowUi], which the manager screen uses: that type
 * carries `canRename` / `canDelete`, and neither means anything in a picker
 * whose only verb is "use this one". Sharing it would have put two dead flags
 * on every row here.
 *
 * @property id Opaque identifier handed back on confirmation.
 * @property name Preset name.
 * @property description Its description; blank hides the line.
 * @property flowPreview One-line rendering of the graph's shape.
 * @property categoryLabel Resolved category label for the badge.
 * @property categoryTone Accent of that badge.
 */
data class PresetPickerRowUi(
    val id: String,
    val name: String,
    val description: String,
    val flowPreview: String,
    val categoryLabel: String,
    val categoryTone: PresetCategoryToneUi,
)

/**
 * Everything [PresetPickerSheetBody] renders.
 *
 * @property title Sheet title.
 * @property tabs Bundled / Mine, with their counts.
 * @property chips Category filter chips, the reset chip first.
 * @property rows The presets to show; already filtered by tab and category.
 * @property selectedRowId Currently selected row, or `null` for none.
 * @property emptyMessage Shown in place of the list when [rows] is empty.
 * @property isLoading `true` while a preset is being instantiated; the confirm
 *   CTA is disabled for the duration so a second tap cannot start a second load.
 * @property cancelLabel Dismiss CTA.
 * @property useLabel Confirm CTA.
 * @property closeContentDescription Label of the header's close icon.
 */
data class PresetPickerViewState(
    val title: String,
    val tabs: List<PresetTabUi>,
    val chips: List<PresetChipUi>,
    val rows: List<PresetPickerRowUi>,
    val selectedRowId: String?,
    val emptyMessage: String,
    val isLoading: Boolean,
    val cancelLabel: String,
    val useLabel: String,
    val closeContentDescription: String,
)

/**
 * Bottom-sheet body for choosing a preset to instantiate.
 *
 * Shares its tab row, chip row and category badge with the preset manager, so
 * the two surfaces cannot drift apart in how a category looks.
 *
 * **Selection is gated on visibility, not merely on existence.** Changing the
 * tab or the category filter can hide the row the user picked; the confirm CTA
 * therefore checks that the selected id is still among [rows]. Without that,
 * confirming would instantiate a preset the user can no longer see.
 *
 * The body is height-bounded to a fraction of the viewport so the list has a
 * finite vertical budget and the footer stays pinned. Without the clamp the
 * column wraps its content and the CTA scrolls off-screen on a long list.
 *
 * No `ModalBottomSheet` wrapper here: the host owns it, so scrim, IME and
 * navigation behaviour stay tunable at the screen level — and so a baseline can
 * photograph this at all, a sheet not laying out under Robolectric.
 *
 * @param state What to render.
 * @param onTabSelected A tab was tapped, by id.
 * @param onCategorySelected A chip was tapped; `null` is the reset chip.
 * @param onRowSelected A row was tapped.
 * @param onUsePreset Confirmed, with the selected id.
 * @param onDismiss Cancel, or the header's close icon.
 * @param modifier Optional layout modifier applied to the body root.
 */
@Composable
fun PresetPickerSheetBody(
    state: PresetPickerViewState,
    onTabSelected: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onRowSelected: (String) -> Unit,
    onUsePreset: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(SHEET_HEIGHT_FRACTION)
            .testTag(PRESET_PICKER_SHEET_TEST_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = KnotworkTheme.spacing.sp6,
                    end = KnotworkTheme.spacing.sp2,
                    bottom = KnotworkTheme.spacing.sp2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `titleMedium`, not `titleLarge`: the larger step overpowered the
            // row content beneath it.
            Text(text = state.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(AppIcons.X, contentDescription = state.closeContentDescription)
            }
        }

        PresetTabRow(tabs = state.tabs, onTabSelected = onTabSelected)
        PresetCategoryChipRow(
            chips = state.chips,
            onCategorySelected = onCategorySelected,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
        )
        HorizontalDivider(color = KnotworkTheme.extended.divider)

        val selectionVisible = state.selectedRowId != null && state.rows.any { it.id == state.selectedRowId }
        // `weight(1f)` is what lets the footer below stay anchored to the bottom.
        Box(modifier = Modifier.weight(1f)) {
            if (state.rows.isEmpty()) {
                Text(
                    text = state.emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.align(Alignment.Center).padding(KnotworkTheme.spacing.sp6),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().testTag(PRESET_PICKER_LIST_TEST_TAG),
                    contentPadding = PaddingValues(vertical = KnotworkTheme.spacing.sp2),
                    verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                ) {
                    items(items = state.rows, key = { it.id }) { row ->
                        PresetPickerRow(
                            row = row,
                            selected = row.id == state.selectedRowId,
                            onClick = { onRowSelected(row.id) },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = KnotworkTheme.extended.divider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = state.cancelLabel, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(
                enabled = selectionVisible && !state.isLoading,
                onClick = { state.selectedRowId?.takeIf { id -> state.rows.any { it.id == id } }?.let(onUsePreset) },
                modifier = Modifier.testTag(PRESET_PICKER_USE_TEST_TAG),
            ) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.padding(end = KnotworkTheme.spacing.sp1),
                )
                Text(state.useLabel)
            }
        }
    }
}

/**
 * One picker row: leading radio, name with its category badge, a two-line
 * description and the mono graph-flow preview. The whole row is tappable so the
 * user does not have to aim for the radio dot.
 *
 * A selected row gets a primary tint and border. Outer padding is identical in
 * both states, so the list does not jump as the selection moves.
 *
 * Public rather than private because an instrumented test asserts, on a real
 * device, that the category badge stays entirely on screen — the row's one
 * genuinely fragile property, and one Robolectric cannot settle.
 *
 * @param row The preset to render.
 * @param selected Whether this row is the current selection.
 * @param onClick The row was tapped.
 */
@Composable
fun PresetPickerRow(row: PresetPickerRowUi, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_BG_ALPHA) else Color.Transparent
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val shape = RoundedCornerShape(size = KnotworkTheme.spacing.sp2)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp4)
            .background(color = bg, shape = shape)
            .border(width = SELECTED_BORDER_WIDTH, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp2)
            .testTag(presetPickerRowTestTag(row.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier.padding(start = KnotworkTheme.spacing.sp1).weight(1f),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                // `weight(fill = false)` is load-bearing: without it the title
                // is measured against the full row width, leaving the badge
                // zero width — its label then wraps one character per line and
                // renders as a tall vertical sliver. `fill = false` keeps a
                // short title snug against its badge rather than pushing it out.
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                PresetCategoryBadge(label = row.categoryLabel, tone = row.categoryTone)
            }
            if (row.description.isNotBlank()) {
                Text(
                    text = row.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.flowPreview,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Stable per-row test tag.
 *
 * @param presetId The row's preset id.
 * @return Its tag.
 */
fun presetPickerRowTestTag(presetId: String): String = "preset_row_$presetId"

/** Test tag of the picker sheet body root. */
const val PRESET_PICKER_SHEET_TEST_TAG: String = "preset_picker_sheet"

/** Test tag of the preset list inside the picker. */
const val PRESET_PICKER_LIST_TEST_TAG: String = "preset_picker_list"

/** Test tag of the confirm CTA. */
const val PRESET_PICKER_USE_TEST_TAG: String = "preset_picker_use"
