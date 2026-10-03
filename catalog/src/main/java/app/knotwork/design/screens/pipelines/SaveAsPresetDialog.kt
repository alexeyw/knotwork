@file:Suppress("MatchingDeclarationName") // File hosts SaveAsPresetDialog + its option, view-state and result payloads.

package app.knotwork.design.screens.pipelines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.knotwork.design.components.chips.KnotworkChipSize
import app.knotwork.design.components.chips.KnotworkFilterChip
import app.knotwork.design.components.controls.KnotworkField
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.theme.KnotworkTheme

/**
 * One selectable category in [SaveAsPresetDialog].
 *
 * Deliberately not [PresetChipUi], which the manager's filter row uses: that
 * type carries a `count`, and a count means something there (how many presets
 * are in the bucket) and nothing at all here. Reusing it would have forced a
 * meaningless zero into every option.
 *
 * @property id Opaque identifier handed back on submit; `:app` maps it to its
 *   own category type.
 * @property label Resolved, translated label.
 */
data class PresetCategoryOptionUi(val id: String, val label: String)

/**
 * Everything [SaveAsPresetDialog] renders.
 *
 * Every string is already resolved. The catalog module cannot reach `:app`
 * types, and that constraint is what keeps this dialog renderable from a
 * snapshot test with no application, no ViewModel and no resources.
 *
 * @property initialName Pre-filled name, normally the source pipeline's.
 * @property categories The selectable buckets, in display order.
 * @property initialCategoryId The bucket selected when the dialog opens.
 * @property title Dialog title.
 * @property nameLabel Label of the name field.
 * @property descriptionLabel Label of the description field.
 * @property categoryLabel Label of the category chip row.
 * @property tagsLabel Label of the tags field.
 * @property saveLabel Confirm CTA.
 * @property cancelLabel Dismiss CTA.
 */
data class SaveAsPresetDialogUi(
    val initialName: String,
    val categories: List<PresetCategoryOptionUi>,
    val initialCategoryId: String,
    val title: String,
    val nameLabel: String,
    val descriptionLabel: String,
    val categoryLabel: String,
    val tagsLabel: String,
    val saveLabel: String,
    val cancelLabel: String,
)

/**
 * What the user typed, handed back on submit.
 *
 * Tags arrive already split and trimmed — the dialog does the splitting because
 * it owns the comma-separated field, and the host normalises further (dedupe,
 * blank-drop) because it owns what a tag *is*.
 *
 * @property name Trimmed display name.
 * @property description Trimmed description.
 * @property categoryId Id of the chosen [PresetCategoryOptionUi].
 * @property tags Non-empty, trimmed tags in the order typed.
 */
data class SaveAsPresetFormResult(
    val name: String,
    val description: String,
    val categoryId: String,
    val tags: List<String>,
)

/**
 * Modal dialog capturing name, description, category and tags before a pipeline
 * is saved as a preset. Raised from the pipeline-library row overflow and from
 * the editor overflow.
 *
 * The dialog owns its form state, for the reason `RenamePresetDialog` gives: a
 * text field that round-trips every keystroke through the caller is the shape
 * that makes typing feel laggy. Submission gates on a non-blank name.
 *
 * The category chips are [KnotworkFilterChip], not Material's. With
 * `filterChipColors()` the only thing selection did was **remove** the outline
 * every other chip had, so the chosen category read as the one that was not
 * chosen. That defect survived to a manual device run precisely because this
 * dialog had no baseline — which is why it is here now.
 *
 * @param ui Resolved copy, options and initial values.
 * @param onDismiss Cancel or scrim tap.
 * @param onConfirm Submit, with what the user entered.
 * @param modifier Optional layout modifier applied to the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveAsPresetDialog(
    ui: SaveAsPresetDialogUi,
    onDismiss: () -> Unit,
    onConfirm: (SaveAsPresetFormResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(ui.initialName) { mutableStateOf(ui.initialName) }
    var description by remember { mutableStateOf("") }
    var tagsRaw by remember { mutableStateOf("") }
    var categoryId by remember(ui.initialCategoryId) { mutableStateOf(ui.initialCategoryId) }

    val canSubmit = name.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(SAVE_AS_PRESET_DIALOG_TEST_TAG),
        title = { Text(ui.title) },
        text = {
            SaveAsPresetDialogBody(
                ui = ui,
                name = name,
                onNameChange = { name = it },
                description = description,
                onDescriptionChange = { description = it },
                tagsRaw = tagsRaw,
                onTagsChange = { tagsRaw = it },
                selectedCategoryId = categoryId,
                onCategorySelected = { categoryId = it },
            )
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onConfirm(
                        SaveAsPresetFormResult(
                            name = name.trim(),
                            description = description.trim(),
                            categoryId = categoryId,
                            tags = tagsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        ),
                    )
                },
            ) { Text(ui.saveLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(ui.cancelLabel) } },
    )
}

/**
 * The dialog's body, without the `AlertDialog` around it.
 *
 * Split out for one reason, and it is a harness reason rather than a design
 * one: **a text field inside an `AlertDialog` never reaches idle** under
 * Robolectric — `setContent` spins until Espresso gives up with
 * `AppNotIdleException`. Re-measured when this dialog moved, against both the
 * v2 compose rule and a clock frozen before `setContent`; neither helps,
 * because the host is the problem and not the field. The same split is why
 * `NodeConfigSheet` has a body: a `ModalBottomSheet` does not lay out here
 * either.
 *
 * So the body is what a baseline photographs, and the wrapper — which adds only
 * the scrim, the title and the two buttons — is exercised behaviourally. State
 * is hoisted so a snapshot can pin any combination without typing into it.
 *
 * @param ui Resolved copy and options.
 * @param name Current name value.
 * @param onNameChange Name edited.
 * @param description Current description value.
 * @param onDescriptionChange Description edited.
 * @param tagsRaw Current raw comma-separated tags.
 * @param onTagsChange Tags edited.
 * @param selectedCategoryId Id of the chosen category.
 * @param onCategorySelected A category chip was tapped.
 * @param modifier Optional layout modifier.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveAsPresetDialogBody(
    ui: SaveAsPresetDialogUi,
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    tagsRaw: String,
    onTagsChange: (String) -> Unit,
    selectedCategoryId: String,
    onCategorySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier,
    ) {
        KnotworkField(label = ui.nameLabel) {
            KnotworkTextField(value = name, onValueChange = onNameChange)
        }
        KnotworkField(label = ui.descriptionLabel) {
            KnotworkTextField(value = description, onValueChange = onDescriptionChange)
        }
        KnotworkField(label = ui.categoryLabel) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                ui.categories.forEach { option ->
                    KnotworkFilterChip(
                        label = option.label,
                        selected = selectedCategoryId == option.id,
                        onClick = { onCategorySelected(option.id) },
                        size = KnotworkChipSize.Sm,
                    )
                }
            }
        }
        KnotworkField(label = ui.tagsLabel) {
            KnotworkTextField(value = tagsRaw, onValueChange = onTagsChange)
        }
    }
}

/** Root test tag of the save-as-preset dialog. */
const val SAVE_AS_PRESET_DIALOG_TEST_TAG: String = "save_as_preset_dialog"
