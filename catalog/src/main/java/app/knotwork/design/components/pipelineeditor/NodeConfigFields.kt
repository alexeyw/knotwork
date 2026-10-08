package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.chips.ChipStyle
import app.knotwork.design.components.chips.KnotworkChip
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.settings.KnotworkParamSlider
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Field label rendered above each input. */
@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelMd,
        color = KnotworkTheme.extended.onSurface2,
    )
}

/** Muted helper caption rendered under a field to explain its purpose. */
@Composable
internal fun FieldCaption(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.BodySm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

/** Inline error rendered under a field when validation fails. */
@Composable
internal fun InlineError(failure: ValidationFailure?) {
    if (failure != null) {
        Text(
            text = stringResource(failure.stringRes),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.signalError,
        )
    }
}

/**
 * Catalog-side variable chips row. The production app uses the
 * `presentation/components/VariableChipsRow` which talks to the prompt
 * variable providers in `:app`; the catalog version is a static
 * presentational stub so the design system surfaces the chips without
 * pulling in the `:app` module.
 *
 * @param onInsert invoked with the chip text when the user taps a chip;
 * forms route this to the field caret.
 */
@Composable
internal fun VariableChipsRow(onInsert: (String) -> Unit) {
    // Full catalog of `PromptVariableProvider` keys exposed in
    // `app/.../data/prompt/*`. Order mirrors the order users most often reach
    // for in templates (date / time first, contextual identity last). Add new
    // keys here when a `PromptVariableProvider` is registered in
    // `PromptTemplateModule`.
    val variables = PROMPT_VARIABLE_KEYS
    // LazyRow with `horizontalScroll`-like behaviour: the chips no longer wrap
    // to a second row (the previous `FlowRow` was visually noisy and pushed
    // the slider section down). Keep a clear right-edge padding so the last
    // chip doesn't kiss the sheet edge.
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(VARIABLE_CHIPS_ROW_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        contentPadding = PaddingValues(end = KnotworkTheme.spacing.sp2),
    ) {
        items(variables) { name ->
            KnotworkChip(label = name, onClick = { onInsert(name) }, style = ChipStyle.Outline)
        }
    }
}

/**
 * Variable keys surfaced by the prompt-template engine — kept here as a
 * snapshot so the catalog stub matches what the `:app` providers register at
 * runtime. When a new `PromptVariableProvider` lands, add the key here too.
 */
private val PROMPT_VARIABLE_KEYS: List<String> = listOf(
    "\$DATE",
    "\$TIME",
    "\$LANG",
    "\$LOCATION",
    "\$USER",
    "\$DEVICE",
    "\$MODEL",
    "\$TOOLS",
    "\$MEMORY_SUMMARY",
)

/** Fixed row height — chip baseline plus a small breathing budget. */
private const val VARIABLE_CHIPS_ROW_HEIGHT: Int = 40

/** Single-line title field — shared across every node type. */
@Composable
internal fun TitleField(title: String, error: ValidationFailure?, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_title))
        OutlinedTextField(
            value = title,
            onValueChange = onChange,
            singleLine = true,
            isError = error != null,
            // Every field on the sheet
            // adopts `MonoBase` so identifiers, prompts, model ids, and titles
            // all read in the same JetBrains Mono face. Mixed body/mono fonts
            // read as accidentally inconsistent on the sheet.
            textStyle = KnotworkTextStyles.MonoBase,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(failure = error)
    }
}

/**
 * Stringly-typed text field used for most per-type rows.
 *
 * Layout: `[FieldLabel + optional library button] / [OutlinedTextField] /
 * [InlineError]`. The library button stays in a sibling row above the field
 * (not inside the field's `trailingIcon`) so prompt-bearing fields preserve
 * room for the chips row underneath. Every field uses `MonoBase`
 * so the sheet reads as a uniform stack regardless
 * of which field is prose vs identifier vs prompt.
 */
@Composable
internal fun TextField(
    label: String,
    value: String,
    error: ValidationFailure?,
    singleLine: Boolean,
    onChange: (String) -> Unit,
    libraryCategory: String? = null,
    onPickFromLibrary: PromptLibraryHook? = null,
    onSavePreset: SavePresetHook? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        // When the field is prompt-bearing AND the screen provided a library/save
        // hook, surface small icon buttons next to the label so the user can replace
        // the field with a saved prompt (📚) or persist the current draft as a new
        // user preset (💾). The buttons sit in a row with the label (not inside the
        // field) so the VariableChipsRow below the field stays visually attached to
        // the prompt area.
        val hasLibrary = libraryCategory != null && onPickFromLibrary != null
        val hasSave = libraryCategory != null && onSavePreset != null
        if (hasLibrary || hasSave) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldLabel(text = label)
                Spacer(modifier = Modifier.weight(1f))
                if (hasLibrary) {
                    IconButton(
                        onClick = {
                            onPickFromLibrary(libraryCategory, value) { picked -> onChange(picked) }
                        },
                        modifier = Modifier.size(LIBRARY_BUTTON_TARGET_DP.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Book,
                            contentDescription = stringResource(R.string.knotwork_node_action_load_from_library),
                        )
                    }
                }
                if (hasSave) {
                    IconButton(
                        onClick = { onSavePreset(libraryCategory, value) },
                        modifier = Modifier.size(LIBRARY_BUTTON_TARGET_DP.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.BookmarkAdd,
                            contentDescription = stringResource(R.string.knotwork_node_action_save_as_preset),
                        )
                    }
                }
            }
        } else {
            FieldLabel(text = label)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            isError = error != null,
            // Same MonoBase rationale as TitleField — the sheet reads as a
            // uniform mono stack regardless of which field is prose vs ident.
            textStyle = KnotworkTextStyles.MonoBase,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(failure = error)
    }
}

/**
 * Compact tap-target for the prompt-library trigger button next to a field
 * label. 32 dp keeps the label row tighter than the M3 default 48 dp
 * `IconButton` (which would otherwise inflate the row to fit the touch area).
 */
private const val LIBRARY_BUTTON_TARGET_DP: Float = 32f

/** Float field rendered as a slider plus the resolved numeric value. */
@Composable
private fun FloatSliderField(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    error: ValidationFailure?,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    KnotworkParamSlider(
        label = label,
        valueLabel = "%.2f".format(value),
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        errorText = error?.let { stringResource(it.stringRes) },
    )
}

/** Integer field rendered as a slider over an `IntRange`. */
@Composable
internal fun IntSliderField(
    label: String,
    value: Int,
    range: IntRange,
    error: ValidationFailure?,
    onChange: (Int) -> Unit,
) {
    // Keep the slider continuous (`steps = 0`) and round on change. Naïve
    // `steps = range.last - range.first - 1` would request one tick PER integer:
    // Cloud's `timeoutMs` range `1_000..600_000` then asks Material3 Slider to
    // allocate ~600 000 tick composables, freezing the main thread and ANRing the
    // app when the CLOUD config sheet opens. Continuous + round-on-change gives
    // identical integer increments at user-perceptible drag resolution without
    // the tick-mark blow-up.
    KnotworkParamSlider(
        label = label,
        valueLabel = "$value",
        value = value.toFloat(),
        onValueChange = { next -> onChange(next.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = 0,
        errorText = error?.let { stringResource(it.stringRes) },
    )
}

/** Labelled boolean toggle: a [FieldLabel] on the left and a [Switch] pushed to the right. */
@Composable
internal fun ToggleRowField(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldLabel(text = label)
        Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Segmented chip row that picks one enum value out of a labelled set. */
@Composable
internal fun <T> SegmentedChipRow(label: String, values: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = label)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            values.forEach { (value, label) ->
                val isSelected = value == selected
                KnotworkChip(
                    label = label,
                    selected = isSelected,
                    onClick = { onSelect(value) },
                    style = ChipStyle.Tonal,
                    // Compose Tonal chips at the project's primaryContainer tint are visibly
                    // similar to the unselected tonal surface in some Knotwork palettes,
                    // which made the Summary format (and other segmented-chip groups) look
                    // unmarked. A leading check unambiguously surfaces the active state
                    // regardless of theme contrast.
                    leadingIcon = if (isSelected) AppIcons.Check else null,
                )
            }
        }
    }
}

/**
 * Optional engine selector shared by the structured node types
 * (INTENT_ROUTER / DECOMPOSITION / EVALUATION / IF_CONDITION / TOOL / SKILL).
 *
 * Unlike the CLOUD node — which is always cloud — these nodes default to
 * on-device inference, so the first option is *On-device* (a `null` provider) and
 * the rest pick a concrete cloud provider that backs the node's structured-output
 * gate. *Auto* is intentionally omitted: structured nodes select a concrete
 * provider, never runtime auto-detection. Every provider appears by name, with
 * what this device would use or that it is not set up here ([EngineField]).
 *
 * @param selected the currently selected provider, or `null` for on-device.
 * @param onSelect invoked with the new selection (`null` ⇒ on-device).
 */
@Composable
internal fun EngineProviderRow(selected: CloudProvider?, onSelect: (CloudProvider?) -> Unit) {
    EngineField(selected = selected, onSelect = onSelect)
}
