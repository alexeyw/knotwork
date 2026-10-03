@file:Suppress("MatchingDeclarationName") // File hosts SavePromptAsPresetDialog + its view-state and result payloads.

package app.knotwork.design.screens.prompts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import app.knotwork.design.components.controls.KnotworkField
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.theme.KnotworkTheme

/** Cap for the read-only prompt preview rendered above the form. */
private const val PREVIEW_MAX_LINES: Int = 4

/**
 * Everything [SavePromptAsPresetDialog] renders.
 *
 * @property title Dialog title.
 * @property subtitle Second title line, naming the node type the prompt came from.
 * @property promptPreview The draft being saved, shown read-only so the user can
 *   confirm *which* prompt this is — the dialog opens from a single icon button,
 *   so the association is otherwise implicit. Blank means there is nothing to
 *   save, and [blankPromptError] is shown instead.
 * @property nameLabel Label of the name field.
 * @property descriptionLabel Label of the description field.
 * @property tagsLabel Label of the tags field.
 * @property tagsHint Sentence under the tags field.
 * @property blankPromptError Shown in place of the preview when the draft is empty.
 * @property nameTooLongError Shown under the name field when it is over length.
 * @property saveLabel Confirm CTA.
 * @property cancelLabel Dismiss CTA.
 * @property maxNameLength Longest accepted name; the host owns the number.
 */
data class SavePromptAsPresetDialogUi(
    val title: String,
    val subtitle: String,
    val promptPreview: String,
    val nameLabel: String,
    val descriptionLabel: String,
    val tagsLabel: String,
    val tagsHint: String,
    val blankPromptError: String,
    val nameTooLongError: String,
    val saveLabel: String,
    val cancelLabel: String,
    val maxNameLength: Int,
)

/**
 * What the user typed, handed back on submit.
 *
 * @property name Trimmed display name.
 * @property description Trimmed description.
 * @property tags Non-empty, trimmed tags in the order typed.
 */
data class SavePromptAsPresetFormResult(val name: String, val description: String, val tags: List<String>)

/**
 * Modal dialog capturing the metadata needed to persist the prompt currently
 * being edited as a reusable preset.
 *
 * Submission is gated on three things at once: a non-blank name, a name within
 * [SavePromptAsPresetDialogUi.maxNameLength], and a non-blank source prompt.
 * Each failing condition also says so inline — a disabled Save with no
 * explanation is the shape that makes a user retype the same name twice.
 *
 * @param ui Resolved copy and limits.
 * @param onConfirm Submit, with what the user entered.
 * @param onDismiss Cancel or scrim tap.
 * @param modifier Optional layout modifier applied to the dialog.
 */
@Composable
fun SavePromptAsPresetDialog(
    ui: SavePromptAsPresetDialogUi,
    onConfirm: (SavePromptAsPresetFormResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var tagsRaw by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(SAVE_PROMPT_AS_PRESET_DIALOG_TEST_TAG),
        title = {
            Column {
                Text(text = ui.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = ui.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            SavePromptAsPresetDialogBody(
                ui = ui,
                name = name,
                onNameChange = { name = it },
                description = description,
                onDescriptionChange = { description = it },
                tagsRaw = tagsRaw,
                onTagsChange = { tagsRaw = it },
            )
        },
        confirmButton = {
            TextButton(
                enabled = canSavePromptPreset(name, ui.promptPreview, ui.maxNameLength),
                onClick = {
                    onConfirm(
                        SavePromptAsPresetFormResult(
                            name = name.trim(),
                            description = description.trim(),
                            tags = parsePromptPresetTags(tagsRaw),
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
 * Split out because a text field inside an `AlertDialog` never reaches idle
 * under Robolectric, so this is what a baseline can photograph. See
 * `SaveAsPresetDialogBody` for the measurement.
 *
 * @param ui Resolved copy and limits.
 * @param name Current name value.
 * @param onNameChange Name edited.
 * @param description Current description value.
 * @param onDescriptionChange Description edited.
 * @param tagsRaw Current raw comma-separated tags.
 * @param onTagsChange Tags edited.
 * @param modifier Optional layout modifier.
 */
@Composable
fun SavePromptAsPresetDialogBody(
    ui: SavePromptAsPresetDialogUi,
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    tagsRaw: String,
    onTagsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier,
    ) {
        if (ui.promptPreview.isBlank()) {
            Text(
                text = ui.blankPromptError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = ui.promptPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KnotworkField(label = ui.nameLabel) {
            KnotworkTextField(value = name, onValueChange = onNameChange)
        }
        if (name.trim().length > ui.maxNameLength) {
            Text(
                text = ui.nameTooLongError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        KnotworkField(label = ui.descriptionLabel) {
            KnotworkTextField(value = description, onValueChange = onDescriptionChange)
        }
        KnotworkField(label = ui.tagsLabel) {
            KnotworkTextField(value = tagsRaw, onValueChange = onTagsChange)
        }
        Text(
            text = ui.tagsHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Splits a comma-separated tag string into a clean list — trims each token and
 * drops blanks. Final de-duplication is the host's, which owns what a tag is.
 *
 * @param raw The field's text.
 * @return The tags, in the order typed.
 */
fun parsePromptPresetTags(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Whether Save should be enabled: a non-blank name, within [maxNameLength], and
 * a non-blank source prompt.
 *
 * A pure function beside the composable so the rule can be tested without a
 * Compose runtime, and so the button's enablement and the inline errors cannot
 * disagree about what "valid" means.
 *
 * @param name The name as typed.
 * @param systemPrompt The draft being saved.
 * @param maxNameLength Longest accepted name.
 * @return `true` when the form may be submitted.
 */
fun canSavePromptPreset(name: String, systemPrompt: String, maxNameLength: Int): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.length <= maxNameLength && systemPrompt.isNotBlank()
}

/** Root test tag of the save-prompt-as-preset dialog. */
const val SAVE_PROMPT_AS_PRESET_DIALOG_TEST_TAG: String = "save_prompt_as_preset_dialog"
