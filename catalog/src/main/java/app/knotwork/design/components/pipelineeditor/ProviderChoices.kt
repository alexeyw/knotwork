package app.knotwork.design.components.pipelineeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * What the node sheet's provider fields know about this device: which providers are set up here
 * and with which model, what *Auto* would pick, and the on-device model.
 *
 * A pipeline moves between devices, so a provider that is not set up here still saves; this only
 * changes what its row says.
 *
 * @property configuredModels Each provider set up on this device, with the model id it uses.
 * @property autoResolvesTo What *Auto* resolves to here, or `null` when nothing is set up.
 * @property onDeviceModel The on-device model's name, or `null` when none is installed.
 */
data class ProviderChoices(
    val configuredModels: Map<CloudProvider, String> = emptyMap(),
    val autoResolvesTo: CloudProvider? = null,
    val onDeviceModel: String? = null,
)

/** The [ProviderChoices] of the node sheet; the editor screen provides it. */
val LocalProviderChoices = staticCompositionLocalOf { ProviderChoices() }

/**
 * The CLOUD node's *Provider*: *Auto*, then every provider, each by name.
 *
 * @param selected The node's provider.
 * @param onSelect A provider was picked.
 */
@Composable
internal fun CloudProviderField(selected: CloudProvider, onSelect: (CloudProvider) -> Unit) {
    val auto = Choice(
        value = CloudProvider.AUTO,
        name = stringResource(R.string.knotwork_node_provider_auto),
        line = stringResource(R.string.knotwork_node_provider_auto_line),
        here = true,
    )
    ChoiceField(
        label = stringResource(R.string.knotwork_node_field_provider),
        first = auto,
        selected = selected,
        fieldLine = { choice ->
            if (choice.value == CloudProvider.AUTO) autoFieldLine(auto.line) else choice.line
        },
        hint = { choice ->
            if (choice.value == CloudProvider.AUTO) {
                stringResource(R.string.knotwork_node_provider_hint_auto)
            } else {
                stringResource(R.string.knotwork_node_provider_hint_cloud, choice.name)
            }
        },
        onSelect = { it?.let(onSelect) },
    )
}

/**
 * The *Engine* of a structured node: *On-device*, then every provider. There is no *Auto* here, on
 * purpose — a structured node runs on a provider chosen for it, or on the device.
 *
 * @param selected The node's engine provider, or `null` for on-device.
 * @param onSelect An engine was picked (`null` ⇒ on-device).
 */
@Composable
internal fun EngineField(selected: CloudProvider?, onSelect: (CloudProvider?) -> Unit) {
    val onDevice = Choice(
        value = null,
        name = stringResource(R.string.knotwork_node_field_engine_on_device),
        line = LocalProviderChoices.current.onDeviceModel
            ?: stringResource(R.string.knotwork_node_provider_on_device_line),
        here = true,
    )
    ChoiceField(
        label = stringResource(R.string.knotwork_node_field_engine),
        first = onDevice,
        selected = selected,
        fieldLine = { it.line },
        hint = { choice ->
            choice.value?.let { stringResource(R.string.knotwork_node_provider_hint_engine, choice.name) }
        },
        onSelect = onSelect,
    )
}

/**
 * One option of a provider field.
 *
 * @property value The provider, or `null` for on-device.
 * @property name Its name.
 * @property line The mono line under the name: a model id, or why it is not usable here.
 * @property here Whether it is set up on this device; otherwise the line leads with a minus glyph.
 */
private data class Choice(val value: CloudProvider?, val name: String, val line: String, val here: Boolean)

/** *Auto* in the field says what it resolves to here, when anything is set up. */
@Composable
private fun autoFieldLine(listLine: String): String = LocalProviderChoices.current.autoResolvesTo?.let {
    stringResource(R.string.knotwork_node_provider_auto_resolved, it.displayName())
} ?: listLine

/** Every provider, in the order the provider surfaces share, as options. */
@Composable
private fun providerChoices(): List<Choice> {
    val models = LocalProviderChoices.current.configuredModels
    val notHere = stringResource(R.string.knotwork_node_provider_not_configured)
    return PROVIDER_ORDER.map { provider ->
        val model = models[provider]
        Choice(value = provider, name = provider.displayName(), line = model ?: notHere, here = model != null)
    }
}

/**
 * The field: the chosen option's name and its mono line, opening the list; under it, one line
 * saying what the node does if that provider is not set up on the device that runs it.
 */
@Composable
private fun ChoiceField(
    label: String,
    first: Choice,
    selected: CloudProvider?,
    fieldLine: @Composable (Choice) -> String,
    hint: @Composable (Choice) -> String?,
    onSelect: (CloudProvider?) -> Unit,
) {
    val options = listOf(first) + providerChoices()
    val current = options.firstOrNull { it.value == selected } ?: first
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Text(
            text = label.uppercase(),
            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        Surface(
            shape = KnotworkTheme.shapes.sm,
            color = KnotworkTheme.extended.surface1,
            border = BorderStroke(1.dp, KnotworkTheme.extended.outlineStrong),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.DropdownList) { open = true }
                .testTag(PROVIDER_CHOICE_FIELD_TEST_TAG),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH)
                    .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = current.name,
                        style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ChoiceLine(line = fieldLine(current), here = current.here)
                }
                Icon(
                    imageVector = AppIcons.ArrowDown,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        hint(current)?.let { text ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            ) {
                Icon(
                    imageVector = AppIcons.Info,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurface2,
                    modifier = Modifier.padding(top = HINT_GLYPH_TOP).size(LINE_GLYPH),
                )
                Text(text = text, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
            }
        }
    }
    if (open) {
        ChoiceSheet(
            title = label,
            options = options,
            selected = current.value,
            onPick = { value ->
                onSelect(value)
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

/** The list over the node sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceSheet(
    title: String,
    options: List<Choice>,
    selected: CloudProvider?,
    onPick: (CloudProvider?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ProviderChoiceListContent(
            title = title,
            options = options.map { ProviderChoiceRowUi(it.value, it.name, it.line, it.here) },
            selected = selected,
            onPick = onPick,
        )
    }
}

/**
 * One row of [ProviderChoiceListContent].
 *
 * @property value The provider, or `null` for on-device.
 * @property name Its name.
 * @property line The mono line under the name.
 * @property here Whether it is set up on this device.
 */
data class ProviderChoiceRowUi(val value: CloudProvider?, val name: String, val line: String, val here: Boolean)

/**
 * The provider list: a title, a note saying why rows not set up here can still be chosen, the
 * first option (*Auto* or *On-device*), then the hosted providers and the servers the user runs
 * under their headings — radio rows in one selectable group, the chosen one scrolled into view.
 *
 * Rows not set up on this device keep their radio and get no block glyph: in the editor that glyph
 * means a row cannot be picked, and these can.
 *
 * @param title The field's name, "Provider" or "Engine".
 * @param options The first option, then every provider in order.
 * @param selected The current value.
 * @param onPick A row was picked.
 * @param modifier Layout modifier from the caller.
 */
@Composable
fun ProviderChoiceListContent(
    title: String,
    options: List<ProviderChoiceRowUi>,
    selected: CloudProvider?,
    onPick: (CloudProvider?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hosted = stringResource(R.string.knotwork_node_provider_group_hosted)
    val own = stringResource(R.string.knotwork_node_provider_group_own)
    val entries = buildList {
        add(ListEntry.Row(options.first()))
        add(ListEntry.Heading(hosted))
        options.drop(1).filter { it.value !in OWN_SERVERS }.forEach { add(ListEntry.Row(it)) }
        add(ListEntry.Heading(own))
        options.drop(1).filter { it.value in OWN_SERVERS }.forEach { add(ListEntry.Row(it)) }
    }
    val selectedIndex = entries.indexOfFirst { it is ListEntry.Row && it.option.value == selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    Column(modifier = modifier.testTag(PROVIDER_CHOICE_LIST_TEST_TAG)) {
        Column(modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp4)) {
            Text(
                text = title,
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.knotwork_node_provider_picker_note),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is ListEntry.Heading -> item {
                        Text(
                            text = entry.title.uppercase(),
                            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                            color = KnotworkTheme.extended.onSurfaceMuted,
                            modifier = Modifier
                                .padding(
                                    start = KnotworkTheme.spacing.sp3,
                                    top = KnotworkTheme.spacing.sp2,
                                    bottom = KnotworkTheme.spacing.sp1,
                                )
                                .semantics { heading() },
                        )
                    }
                    is ListEntry.Row -> item {
                        RadioRow(
                            option = entry.option,
                            selected = entry.option.value == selected,
                            onClick = { onPick(entry.option.value) },
                        )
                    }
                }
            }
        }
    }
}

/** A heading or a row of the provider list. */
private sealed interface ListEntry {
    data class Heading(val title: String) : ListEntry

    data class Row(val option: ProviderChoiceRowUi) : ListEntry
}

/** A radio row: name in full, then the mono line; selected rows are tinted and outlined. */
@Composable
private fun RadioRow(option: ProviderChoiceRowUi, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) primary.copy(alpha = SELECTED_ALPHA) else Color.Transparent,
                shape = KnotworkTheme.shapes.md,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = MIN_TOUCH)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(RADIO_SIZE))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.name,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            ChoiceLine(line = option.line, here = option.here)
        }
    }
}

/** The mono line; for a provider not set up here, it leads with a minus glyph. */
@Composable
private fun ChoiceLine(line: String, here: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        if (!here) {
            Icon(
                imageVector = AppIcons.MinusCircle,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.size(LINE_GLYPH),
            )
        }
        Text(
            text = line,
            style = KnotworkTextStyles.MonoSm,
            color = if (here) KnotworkTheme.extended.onSurfaceMuted else KnotworkTheme.extended.onSurface2,
        )
    }
}

/** A provider's own name. Product names, never translated. */
internal fun CloudProvider.displayName(): String = when (this) {
    CloudProvider.OPEN_AI -> "OpenAI"
    CloudProvider.ANTHROPIC -> "Anthropic"
    CloudProvider.GOOGLE -> "Google"
    CloudProvider.DEEPSEEK -> "DeepSeek"
    CloudProvider.OPENROUTER -> "OpenRouter"
    CloudProvider.GROQ -> "Groq"
    CloudProvider.OLLAMA -> "Ollama"
    CloudProvider.OPENAI_COMPATIBLE -> "OpenAI-compatible server"
    CloudProvider.AUTO -> "Auto"
}

/** Every concrete provider, in the order the provider surfaces share. */
private val PROVIDER_ORDER: List<CloudProvider> = CloudProvider.entries - CloudProvider.AUTO

/** The providers that are servers the user runs, listed under their own heading. */
private val OWN_SERVERS: Set<CloudProvider> = setOf(CloudProvider.OLLAMA, CloudProvider.OPENAI_COMPATIBLE)

private const val SELECTED_ALPHA = 0.08f
private val MIN_TOUCH = 48.dp
private val LINE_GLYPH = 12.dp
private val HINT_GLYPH_TOP = 2.dp
private val RADIO_SIZE = 20.dp

/** Test tag of a provider field on the node sheet. */
const val PROVIDER_CHOICE_FIELD_TEST_TAG: String = "provider_choice_field"

/** Test tag of the provider list. */
const val PROVIDER_CHOICE_LIST_TEST_TAG: String = "provider_choice_list"
