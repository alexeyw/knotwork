package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.settings.KnotworkParamSlider
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Lower bound on IntentRouter class count, mirrors `NodeConfigValidation.INTENT_CLASSES_RANGE.first`. */
private const val INTENT_ROUTER_MIN_CLASSES = 2

/** Upper bound on IntentRouter class count, mirrors `NodeConfigValidation.INTENT_CLASSES_RANGE.last`. */
private const val INTENT_ROUTER_MAX_CLASSES = 6

/**
 * INPUT has no fields of its own beyond the shared title and description: the
 * entry contract is fixed and the run's text arrives as it is. The body is a
 * single line saying so, rather than an empty sheet that reads as unfinished.
 */
@Composable
internal fun InputFormBody() {
    Text(
        text = stringResource(R.string.knotwork_node_input_no_settings),
        style = KnotworkTextStyles.BodySm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

@Composable
internal fun IntentRouterFormBody(
    config: IntentRouterConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_classes))
        config.classes.forEachIndexed { index, intentClass ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                OutlinedTextField(
                    value = intentClass.name,
                    onValueChange = { next ->
                        val updated = config.classes.toMutableList()
                        updated[index] = intentClass.copy(name = next)
                        onChange(config.copy(classes = updated))
                    },
                    singleLine = true,
                    textStyle = KnotworkTextStyles.MonoBase,
                    modifier = Modifier.weight(1f),
                    isError = intentClass.name.isBlank(),
                )
                // The remove control is disabled when removing would drop below the
                // 2-class minimum that `NodeConfigValidation` enforces — keeps the form
                // self-consistent without surfacing a "you cannot remove the last class"
                // error after the fact. The fallback selection is auto-cleared when its
                // class disappears so the now-stale dropdown does not silently survive.
                val canRemove = config.classes.size > INTENT_ROUTER_MIN_CLASSES
                IconButton(
                    onClick = {
                        val removedName = config.classes[index].name
                        val updated = config.classes.toMutableList().apply { removeAt(index) }
                        val nextFallback = config.fallbackClass?.takeIf { it != removedName }
                        onChange(config.copy(classes = updated, fallbackClass = nextFallback))
                    },
                    enabled = canRemove,
                ) {
                    Icon(
                        imageVector = AppIcons.MinusCircle,
                        contentDescription = stringResource(R.string.knotwork_node_action_remove_class),
                    )
                }
            }
        }
        // Add-class is gated on the same 6-class ceiling the validator enforces; together
        // with the per-row remove gate this means the form never lets the user push the
        // config into an invalid state — `Save` stays enabled for every reachable edit.
        val canAdd = config.classes.size < INTENT_ROUTER_MAX_CLASSES
        KnotworkTextButton(
            text = stringResource(R.string.knotwork_node_action_add_class),
            onClick = {
                // Auto-name the new class with a unique `class_N` placeholder so
                // it doesn't start blank. A blank name immediately fails the
                // `REQUIRED` validation rule and disables Save — locking the user
                // out of saving until they type a name. The placeholder is also
                // unique relative to existing names (it walks `N` until it finds
                // a free slot) so it doesn't trip CLASS_NAME_DUPLICATE either.
                val existing = config.classes.map { it.name }.toSet()
                val baseN = config.classes.size + 1
                val placeholder = generateSequence(baseN) { it + 1 }
                    .map { "class_$it" }
                    .first { it !in existing }
                val updated = config.classes + IntentClass(name = placeholder)
                onChange(config.copy(classes = updated))
            },
            enabled = canAdd,
            leadingIcon = AppIcons.Add,
        )
        InlineError(failure = errors[FieldId.CLASSES])
    }
    TextField(
        label = stringResource(R.string.knotwork_node_field_classifier_prompt),
        value = config.classifierPrompt,
        error = errors[FieldId.CLASSIFIER_PROMPT],
        singleLine = false,
        onChange = { next -> onChange(config.copy(classifierPrompt = next)) },
        libraryCategory = "INTENT_ROUTER",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(classifierPrompt = config.classifierPrompt + variable))
    })
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        SegmentedChipRow(
            label = stringResource(R.string.knotwork_node_field_fallback_class),
            // Stale fallback values (a class that was renamed or removed) are
            // still listed as the trailing option so the chip row reflects
            // the saved state — the inline error below tells the user the
            // value no longer resolves, and selecting <none> or another
            // class clears it. Without surfacing the stale value the user
            // would not see what they are about to fix.
            values = listOf<Pair<String?, String>>(null to "<none>") +
                config.classes.map { it.name to it.name } +
                listOfNotNull(
                    config.fallbackClass
                        ?.takeIf { it.isNotBlank() && it !in config.classes.map { c -> c.name } }
                        ?.let { it to it },
                ),
            selected = config.fallbackClass,
            onSelect = { next -> onChange(config.copy(fallbackClass = next)) },
        )
        InlineError(failure = errors[FieldId.FALLBACK_CLASS])
    }
    EngineProviderRow(config.engineProvider) { next -> onChange(config.copy(engineProvider = next)) }
}

@Composable
internal fun IfConditionFormBody(
    config: IfConditionConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    // The four checks are laid out in the order the engine applies them, and the
    // first that matches decides the branch. That order used to be invisible:
    // the expression sat directly under the image toggle, above the two
    // deterministic checks that in fact run before it, so the sheet read as if
    // it were evaluated first when it is evaluated last.
    //
    // First: deterministic image presence. When on, the node forks True whenever
    // the user's message carries an image — no LLM call, nothing below consulted.
    ToggleRowField(
        label = stringResource(R.string.knotwork_node_field_branch_on_image),
        checked = config.branchOnImage,
        onChange = { next -> onChange(config.copy(branchOnImage = next)) },
    )
    // The two deterministic checks. Both were read by the engine long before
    // they had controls: an imported pipeline could carry keywords that decided
    // every branch, and the sheet said nothing about it.
    TextField(
        label = stringResource(R.string.knotwork_node_field_keywords),
        value = config.keywords,
        error = errors[FieldId.KEYWORDS],
        singleLine = true,
        onChange = { next -> onChange(config.copy(keywords = next)) },
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_field_keywords_help))
    IntSliderField(
        label = stringResource(R.string.knotwork_node_field_complexity_threshold),
        value = config.complexityThreshold ?: 0,
        range = 0..COMPLEXITY_THRESHOLD_MAX,
        error = errors[FieldId.COMPLEXITY_THRESHOLD],
        onChange = { next -> onChange(config.copy(complexityThreshold = next.takeIf { it > 0 })) },
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_field_complexity_threshold_help))
    // Last of the four, and the only one that costs a model call. The field maps
    // to domain `NodeModel.conditionPrompt` — a free-form natural-language
    // condition the LLM classifies as `true`/`false` via
    // `DefaultPrompts.IfCondition.EVALUATION_TEMPLATE`. It accepts presets
    // exactly like the other LLM-driven fields.
    TextField(
        label = stringResource(R.string.knotwork_node_field_expression),
        value = config.expression,
        error = errors[FieldId.EXPRESSION],
        singleLine = false,
        onChange = { next -> onChange(config.copy(expression = next)) },
        libraryCategory = "IF_CONDITION",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    FieldCaption(text = stringResource(R.string.knotwork_node_field_expression_help))
    EngineProviderRow(config.engineProvider) { next -> onChange(config.copy(engineProvider = next)) }
}

@Composable
internal fun ClarificationFormBody(
    config: ClarificationConfig,
    errors: Map<FieldId, ValidationFailure>,
    onChange: (NodeConfig) -> Unit,
    onPickFromLibrary: PromptLibraryHook?,
    onSavePreset: SavePresetHook?,
) {
    TextField(
        label = stringResource(R.string.knotwork_node_field_question),
        value = config.questionTemplate,
        error = errors[FieldId.QUESTION_TEMPLATE],
        singleLine = false,
        onChange = { next -> onChange(config.copy(questionTemplate = next)) },
        libraryCategory = "CLARIFICATION",
        onPickFromLibrary = onPickFromLibrary,
        onSavePreset = onSavePreset,
    )
    VariableChipsRow(onInsert = { variable ->
        onChange(config.copy(questionTemplate = config.questionTemplate + variable))
    })
    QuickRepliesField(
        replies = config.quickReplies,
        error = errors[FieldId.QUICK_REPLIES],
        onChange = { next -> onChange(config.copy(quickReplies = next)) },
    )
    // Timeout is edited in whole seconds (0–360). 0 means "no timeout" and
    // maps back to a null `timeoutMs`; any other value is stored as
    // milliseconds. A slider replaces the old free-text ms field so the unit
    // and bounds are unambiguous.
    val timeoutSeconds = ((config.timeoutMs ?: 0) / MILLIS_PER_SECOND).coerceIn(0, CLARIFY_TIMEOUT_MAX_SECONDS)
    KnotworkParamSlider(
        label = stringResource(R.string.knotwork_node_field_timeout_seconds),
        valueLabel = if (timeoutSeconds == 0) {
            stringResource(R.string.knotwork_node_field_timeout_none)
        } else {
            stringResource(R.string.knotwork_node_field_timeout_seconds_value, timeoutSeconds)
        },
        value = timeoutSeconds.toFloat(),
        onValueChange = { next ->
            val secs = next.toInt()
            onChange(config.copy(timeoutMs = if (secs == 0) null else secs * MILLIS_PER_SECOND))
        },
        valueRange = 0f..CLARIFY_TIMEOUT_MAX_SECONDS.toFloat(),
        steps = 0,
        errorText = errors[FieldId.TIMEOUT_OPTIONAL]?.let { stringResource(it.stringRes) },
    )
}

/**
 * Top of the IF_CONDITION length-threshold slider, in characters.
 *
 * 2 000 rather than a round 1 000 or 10 000: the check exists to fork "short
 * question" from "long brief", and a brief long enough to matter is a few
 * paragraphs. Past this the condition would never fire on anything a person
 * types into a chat.
 */
private const val COMPLEXITY_THRESHOLD_MAX: Int = 2_000

/** Milliseconds per second — Clarify timeout slider works in whole seconds. */
private const val MILLIS_PER_SECOND: Int = 1_000

/** Upper bound (seconds) of the Clarify wait-timeout slider. */
private const val CLARIFY_TIMEOUT_MAX_SECONDS: Int = 360

/**
 * Comma-separated quick-reply editor.
 *
 * Holds the user's raw text in a `remember`-backed local state so a
 * keystroke that adds a comma is not parsed-and-re-serialised on the
 * way back through the model. The previous implementation called
 * `text.split(',').map { it.trim() }.filter { it.isNotEmpty() }` on
 * every keystroke and then re-rendered `joinToString(", ")` from the
 * filtered list — so typing `yes,` produced `["yes"]`, which rendered
 * as `yes` and dropped the user's pending comma. The field was
 * effectively unable to accept more than one quick reply.
 *
 * The local-state approach is necessary because the model carries
 * `List<String>`: the trailing empty token a user types ahead of a new
 * reply has no canonical representation in the list. We sync the
 * trimmed-and-empty-filtered list to the model so validation
 * (`QUICK_REPLIES_RANGE`) still fires from the model, but the field
 * always shows what the user actually typed.
 *
 * If the caller hands in a new `replies` value (e.g. config loaded
 * from disk), the `LaunchedEffect` keyed on the serialised form
 * re-syncs the raw text so the field stays in sync with the model.
 */
@Composable
private fun QuickRepliesField(replies: List<String>, error: ValidationFailure?, onChange: (List<String>) -> Unit) {
    val serialised = replies.joinToString(", ")
    var rawText by remember { mutableStateOf(serialised) }
    LaunchedEffect(serialised) {
        if (serialised != rawText.parseQuickReplies().joinToString(", ")) {
            rawText = serialised
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        FieldLabel(text = stringResource(R.string.knotwork_node_field_quick_replies))
        FieldCaption(text = stringResource(R.string.knotwork_node_field_quick_replies_help))
        OutlinedTextField(
            value = rawText,
            onValueChange = { next ->
                rawText = next
                onChange(next.parseQuickReplies())
            },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        InlineError(failure = error)
    }
}

/** Trims and drops empty tokens; the canonical model representation. */
private fun String.parseQuickReplies(): List<String> =
    if (isEmpty()) emptyList() else split(',').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
internal fun QueueProcessorFormBody(config: QueueProcessorConfig, onChange: (NodeConfig) -> Unit) {
    FieldCaption(text = stringResource(R.string.knotwork_node_queue_source_help))
    ToggleRowField(
        label = stringResource(R.string.knotwork_node_field_stop_on_error),
        checked = config.stopOnError,
        onChange = { next -> onChange(config.copy(stopOnError = next)) },
    )
}
