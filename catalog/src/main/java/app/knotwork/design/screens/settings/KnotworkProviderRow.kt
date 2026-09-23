@file:Suppress("MatchingDeclarationName") // Hosts KnotworkProviderRow plus OllamaProviderInputs.

package app.knotwork.design.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.misc.KnotworkLoader
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Optional Ollama-specific fields. Non-null only for the Ollama provider;
 * every cloud provider (OpenAI / Anthropic / Google / DeepSeek) leaves
 * this field `null`.
 *
 * @property baseUrl current base URL (`http://192.168.1.100:11434`).
 * @property baseUrlPlaceholder placeholder text shown when [baseUrl] empty.
 * @property baseUrlValidationError optional inline validation error; renders
 *  beneath the URL field with a `signalError`-tinted helper.
 * @property contextWindow current context window text (free-form digits so
 *  the field can render partial input while typing).
 * @property contextWindowLabel localised label for the context-window field.
 * @property baseUrlLabel localised label for the URL field.
 */
data class OllamaProviderInputs(
    val baseUrl: String,
    val baseUrlPlaceholder: String,
    val baseUrlValidationError: String?,
    val contextWindow: String,
    val contextWindowLabel: String,
    val baseUrlLabel: String,
)

/**
 * Per-provider collapsible row used inside the `Models` section of
 * `SettingsContent`.
 *
 * Visual contract:
 *  - Header row: provider title (`TitleMd`, semi-bold), optional
 *    `pendingChange` loader, trailing chevron.
 *  - Tap on the header toggles expansion (animated via `AnimatedVisibility`).
 *  - Body (expanded only): masked `OutlinedTextField` (key),
 *    `ExposedDropdownMenuBox` (model). For Ollama: extra base-URL + numeric
 *    context-window fields. Validation error renders beneath the URL field.
 *  - Whole card sits on `KnotworkTheme.shapes.md` with the standard outline.
 *
 * The composable is fully stateless except for the expanded flag (held
 * internally — opening / closing the card is purely visual and not part
 * of the settings persistence model).
 *
 * @param title provider display name (e.g. `OpenAI`).
 * @param keyValue current API key.
 * @param onKeyChange callback invoked when the user edits the key.
 * @param keyLabel localised key field label.
 * @param modelValue current model identifier.
 * @param onModelChange callback invoked when a model is picked from the
 *  dropdown (cloud) or typed (Ollama free-form).
 * @param modelLabel localised model field label.
 * @param availableModels dropdown choices for [modelValue]; an empty list
 *  switches the field into a free-form text input (used by Ollama where
 *  the user types the model identifier themselves).
 * @param pendingChange `true` while an async write is in flight; shows the
 *  `KnotworkLoader` in the header and disables the inputs.
 * @param ollama optional Ollama-specific fields; `null` for cloud providers.
 * @param onOllamaBaseUrlChange callback invoked when the Ollama URL field
 *  is edited; ignored when [ollama] is `null`.
 * @param onOllamaContextWindowChange callback invoked when the Ollama
 *  context-window field is edited; ignored when [ollama] is `null`.
 * @param modifier optional layout modifier applied to the outer surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // Stable public API; provider rows have many distinct attributes by design.
@Composable
fun KnotworkProviderRow(
    title: String,
    keyValue: String,
    onKeyChange: (String) -> Unit,
    keyLabel: String,
    modelValue: String,
    onModelChange: (String) -> Unit,
    modelLabel: String,
    availableModels: List<String>,
    modifier: Modifier = Modifier,
    pendingChange: Boolean = false,
    ollama: OllamaProviderInputs? = null,
    onOllamaBaseUrlChange: (String) -> Unit = {},
    onOllamaContextWindowChange: (String) -> Unit = {},
    showApiKey: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KnotworkTheme.shapes.md,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = OUTLINE_WIDTH,
            color = KnotworkTheme.extended.outlineStrong,
        ),
    ) {
        Column(modifier = Modifier.padding(PaddingValues(KnotworkTheme.spacing.sp3))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClick = { expanded = !expanded },
                    ),
            ) {
                Text(
                    text = title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (pendingChange) {
                    Box(modifier = Modifier.size(KnotworkTheme.spacing.sp4)) { KnotworkLoader() }
                }
                Icon(
                    imageVector = if (expanded) AppIcons.ArrowUp else AppIcons.ArrowDown,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = KnotworkTheme.spacing.sp3),
                    verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                ) {
                    if (showApiKey) {
                        OutlinedTextField(
                            value = keyValue,
                            onValueChange = onKeyChange,
                            label = { Text(keyLabel) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            enabled = !pendingChange,
                            colors = brandTextFieldColors(),
                        )
                    }
                    if (availableModels.isEmpty()) {
                        // Free-form input — Ollama variant where the user types the model id.
                        OutlinedTextField(
                            value = modelValue,
                            onValueChange = onModelChange,
                            label = { Text(modelLabel) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !pendingChange,
                            colors = brandTextFieldColors(),
                        )
                    } else {
                        // Fixed-list dropdown — cloud providers. The field is read-only so
                        // tapping it surfaces the menu without raising the soft keyboard.
                        ExposedDropdownMenuBox(
                            expanded = modelDropdownExpanded,
                            onExpandedChange = { modelDropdownExpanded = it },
                        ) {
                            OutlinedTextField(
                                value = modelValue,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(modelLabel) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(
                                        ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                        enabled = !pendingChange,
                                    ),
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded)
                                },
                                enabled = !pendingChange,
                                colors = brandTextFieldColors(),
                            )
                            ExposedDropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false },
                            ) {
                                availableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = { Text(model) },
                                        onClick = {
                                            onModelChange(model)
                                            modelDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (ollama != null) {
                        OutlinedTextField(
                            value = ollama.baseUrl,
                            onValueChange = onOllamaBaseUrlChange,
                            label = { Text(ollama.baseUrlLabel) },
                            placeholder = { Text(ollama.baseUrlPlaceholder) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !pendingChange,
                            isError = ollama.baseUrlValidationError != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                autoCorrectEnabled = false,
                            ),
                            supportingText = {
                                if (ollama.baseUrlValidationError != null) {
                                    Text(
                                        text = ollama.baseUrlValidationError,
                                        color = KnotworkTheme.extended.signalError,
                                    )
                                }
                            },
                            colors = brandTextFieldColors(),
                        )
                        OutlinedTextField(
                            value = ollama.contextWindow,
                            onValueChange = onOllamaContextWindowChange,
                            label = { Text(ollama.contextWindowLabel) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            enabled = !pendingChange,
                            colors = brandTextFieldColors(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun brandTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
)

private val OUTLINE_WIDTH = 1.dp
