@file:Suppress("MatchingDeclarationName") // Hosts TriggerEditorContent + helpers.

package app.knotwork.design.screens.triggers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.KnotworkInputChip
import app.knotwork.design.components.controls.KnotworkSegmentedControl
import app.knotwork.design.components.controls.LabeledSwitchRow
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Minutes per hour — the divisor when rendering a whole-hour preset label. */
private const val MINUTES_PER_HOUR = 60L

/** The interval preset values, in minutes (15 min · 30 min · 1 h · 6 h · 24 h). */
@Suppress("MagicNumber") // Self-documenting preset minute values backing the schedule chips.
val TRIGGER_INTERVAL_PRESETS: List<Long> = listOf(15L, 30L, 60L, 360L, 1440L)

/** Side length of the identity tile on the editor header strip. */
private val EditorTile = 44.dp

/** Inner glyph size on the identity tile. */
private val EditorTileGlyph = 22.dp

/** Minimum height of the multi-line prompt field. */
private val PromptMinHeight = 96.dp

/** Renders an interval preset value as a compact chip label ("15m" / "1h"). */
internal fun intervalPresetLabel(minutes: Long): String =
    if (minutes % MINUTES_PER_HOUR == 0L) "${minutes / MINUTES_PER_HOUR}h" else "${minutes}m"

/**
 * Stateless full-screen Trigger editor — create or edit a trigger's name,
 * condition (type + parameters), bound pipeline, input prompt and enabled flag.
 *
 * @param state immutable editor state.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings.
 * @param callbacks one-shot callback bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerEditorContent(
    state: TriggerEditorUi,
    modifier: Modifier = Modifier,
    strings: TriggerEditorStrings = TriggerEditorStrings(),
    callbacks: TriggerEditorCallbacks = noopTriggerEditorCallbacks(),
) {
    val editing = state.id != null
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            Column {
                EditorTopBar(state = state, editing = editing, strings = strings, callbacks = callbacks)
                HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            IdentityStrip(strings = strings)

            FieldLabel(label = strings.nameLabel, error = state.nameError, hint = strings.required)
            EditorField(
                value = state.name,
                placeholder = strings.namePlaceholder,
                onValueChange = callbacks.onNameChange,
                error = state.nameError,
            )
            if (state.nameError) FieldError(text = strings.nameErrorText)

            FieldLabel(label = strings.conditionLabel)
            ConditionTypeGrid(selected = state.type, strings = strings, onSelect = callbacks.onTypeChange)
            ConditionParams(state = state, strings = strings, callbacks = callbacks)

            FieldLabel(label = strings.pipelineLabel)
            PipelinePicker(state = state, strings = strings, onSelect = callbacks.onPipelineSelect)

            FieldLabel(label = strings.promptLabel)
            PromptField(
                value = state.prompt,
                placeholder = strings.promptPlaceholder,
                onValueChange = callbacks.onPromptChange,
            )

            EnabledRow(enabled = state.enabled, strings = strings, onToggle = callbacks.onEnabledToggle)

            if (editing) {
                Box(modifier = Modifier.padding(top = KnotworkTheme.spacing.sp4)) {
                    DangerButton(label = strings.deleteTrigger, onClick = callbacks.onDelete)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    state: TriggerEditorUi,
    editing: Boolean,
    strings: TriggerEditorStrings,
    callbacks: TriggerEditorCallbacks,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = if (editing) strings.titleEdit else strings.titleNew,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (editing) strings.subtitleEdit else strings.subtitleNew,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onClose) {
                Icon(
                    imageVector = AppIcons.X,
                    contentDescription = strings.closeCd,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        actions = { SaveButton(enabled = state.canSave, label = strings.save, onClick = callbacks.onSave) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun SaveButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    val container = if (enabled) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.surface3
    val content = if (enabled) MaterialTheme.colorScheme.onPrimary else KnotworkTheme.extended.onSurfaceDim
    Box(
        modifier = Modifier
            .padding(end = KnotworkTheme.spacing.sp3)
            .clip(KnotworkTheme.shapes.full)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
    ) {
        Text(text = label, style = KnotworkTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold), color = content)
    }
}

@Composable
private fun IdentityStrip(strings: TriggerEditorStrings) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Box(
            modifier = Modifier.size(EditorTile).clip(KnotworkTheme.shapes.md).background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Trigger,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(EditorTileGlyph),
            )
        }
        Column {
            Text(
                text = strings.kicker,
                style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
                color = accent,
            )
            Text(
                text = strings.kickerBody,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionTypeGrid(
    selected: TriggerConditionType,
    strings: TriggerEditorStrings,
    onSelect: (TriggerConditionType) -> Unit,
) {
    val options = listOf(
        Triple(TriggerConditionType.Interval, strings.typeInterval, strings.typeIntervalSub),
        Triple(TriggerConditionType.Daily, strings.typeDaily, strings.typeDailySub),
        Triple(TriggerConditionType.Charging, strings.typeCharging, strings.typeChargingSub),
        Triple(TriggerConditionType.Network, strings.typeNetwork, strings.typeNetworkSub),
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        maxItemsInEachRow = 2,
    ) {
        options.forEach { (type, label, sub) ->
            ConditionTypeCard(
                type = type,
                label = label,
                sub = sub,
                selected = type == selected,
                onSelect = { onSelect(type) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConditionTypeCard(
    type: TriggerConditionType,
    label: String,
    sub: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(KnotworkTheme.shapes.md)
            .background(if (selected) accent.copy(alpha = 0.10f) else KnotworkTheme.extended.surface1)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outline,
                shape = KnotworkTheme.shapes.md,
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = type.glyph(),
            contentDescription = null,
            tint = if (selected) accent else KnotworkTheme.extended.onSurface2,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = if (selected) MaterialTheme.colorScheme.onSurface else KnotworkTheme.extended.onSurface2,
            )
            Text(text = sub, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
        }
    }
}

@Composable
private fun ConditionParams(state: TriggerEditorUi, strings: TriggerEditorStrings, callbacks: TriggerEditorCallbacks) {
    when (state.type) {
        TriggerConditionType.Interval -> IntervalParams(state = state, strings = strings, callbacks = callbacks)
        TriggerConditionType.Daily -> DailyParams(state = state, strings = strings, callbacks = callbacks)
        TriggerConditionType.Charging -> NoteCard(
            icon = AppIcons.Battery,
            title = strings.chargingTitle,
            body = strings.chargingBody,
        )
        TriggerConditionType.Network -> NetworkParams(state = state, strings = strings, callbacks = callbacks)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntervalParams(state: TriggerEditorUi, strings: TriggerEditorStrings, callbacks: TriggerEditorCallbacks) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp1),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        TRIGGER_INTERVAL_PRESETS.forEach { minutes ->
            IntervalChip(
                label = intervalPresetLabel(minutes),
                selected = !state.intervalCustom && state.intervalMinutes == minutes,
                onClick = { callbacks.onIntervalPreset(minutes) },
            )
        }
        IntervalChip(
            label = strings.intervalCustom,
            icon = AppIcons.Sliders,
            selected = state.intervalCustom,
            onClick = callbacks.onIntervalCustomToggle,
        )
    }
    if (state.intervalCustom) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp2),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.width(96.dp)) {
                EditorField(
                    value = state.intervalCustomAmount,
                    placeholder = "45",
                    onValueChange = callbacks.onIntervalCustomAmountChange,
                    error = state.intervalError,
                    numeric = true,
                )
            }
            KnotworkSegmentedControl(
                options = listOf(strings.unitMinutes, strings.unitHours),
                selectedIndex = if (state.intervalCustomUnitHours) 1 else 0,
                onSelect = { callbacks.onIntervalUnitChange(it == 1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (state.intervalError) {
        FieldError(text = strings.intervalErrorText)
    } else {
        Row(
            modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = strings.intervalFloorNote,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun IntervalChip(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .background(if (selected) accent else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outline,
                shape = KnotworkTheme.shapes.full,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        val content = if (selected) MaterialTheme.colorScheme.onPrimary else KnotworkTheme.extended.onSurface2
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        }
        Text(
            text = label,
            style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.SemiBold),
            color = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyParams(state: TriggerEditorUi, strings: TriggerEditorStrings, callbacks: TriggerEditorCallbacks) {
    var pickerOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp1)
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.md)
            .clickable { pickerOpen = true }
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = AppIcons.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.dailyFieldLabel,
                style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            Text(
                text = state.dailyTimeLabel,
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (pickerOpen) {
        val pickerState = rememberTimePickerState(
            initialHour = state.dailyHour,
            initialMinute = state.dailyMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                KnotworkTextButton(
                    text = strings.timeOk,
                    onClick = {
                        callbacks.onDailyTimeChange(pickerState.hour, pickerState.minute)
                        pickerOpen = false
                    },
                )
            },
            dismissButton = { KnotworkTextButton(text = strings.cancel, onClick = { pickerOpen = false }) },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkParams(state: TriggerEditorUi, strings: TriggerEditorStrings, callbacks: TriggerEditorCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp1)
            .clip(KnotworkTheme.shapes.md)
            .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.md),
    ) {
        LabeledSwitchRow(
            label = strings.wifiOnlyLabel,
            description = strings.wifiOnlyDesc,
            checked = state.wifiOnly,
            onToggle = callbacks.onWifiOnlyToggle,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        )
    }
    SsidScope(state = state, strings = strings, callbacks = callbacks)
}

/**
 * SSID-scoping controls for the Network condition: the current network names as
 * removable chips plus an "add a name" input. Empty list = fire on any Wi-Fi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SsidScope(state: TriggerEditorUi, strings: TriggerEditorStrings, callbacks: TriggerEditorCallbacks) {
    var input by remember { mutableStateOf("") }
    val submit = {
        if (input.isNotBlank()) {
            callbacks.onSsidAdd(input)
            input = ""
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp3)) {
        Text(
            text = strings.ssidSectionLabel,
            style = KnotworkTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
            color = KnotworkTheme.extended.onSurface2,
        )
        Text(
            text = strings.ssidSectionDesc,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
        )
        if (state.ssids.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp2),
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                state.ssids.forEach { ssid ->
                    KnotworkInputChip(label = ssid, onRemove = { callbacks.onSsidRemove(ssid) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp2),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                EditorField(
                    value = input,
                    placeholder = strings.ssidPlaceholder,
                    onValueChange = { input = it },
                )
            }
            KnotworkTextButton(text = strings.ssidAdd, onClick = submit, enabled = input.isNotBlank())
        }
    }
}

@Composable
private fun PipelinePicker(state: TriggerEditorUi, strings: TriggerEditorStrings, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val bound = state.pipelineName != null
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(KnotworkTheme.shapes.md)
                .background(KnotworkTheme.extended.surface1)
                .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.md)
                .clickable { expanded = true }
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            Icon(
                imageVector = AppIcons.Flow,
                contentDescription = null,
                tint = if (bound) MaterialTheme.colorScheme.primary else KnotworkTheme.extended.onSurfaceDim,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = state.pipelineName ?: strings.pipelineNone,
                style = KnotworkTextStyles.BodyBase,
                color = if (bound) MaterialTheme.colorScheme.onSurface else KnotworkTheme.extended.onSurfaceDim,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = AppIcons.ArrowDown,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = KnotworkTheme.extended.surface1,
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = strings.pipelineNone,
                            style = KnotworkTextStyles.BodySm,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = strings.pipelineNoneSub,
                            style = KnotworkTextStyles.MonoSm,
                            color = KnotworkTheme.extended.onSurfaceMuted,
                        )
                    }
                },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            state.pipelines.forEach { option ->
                HorizontalDivider(color = KnotworkTheme.extended.divider)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.name,
                            style = KnotworkTextStyles.BodySm,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = AppIcons.Flow,
                            contentDescription = null,
                            tint = KnotworkTheme.extended.onSurface2,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    onClick = {
                        onSelect(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EnabledRow(enabled: Boolean, strings: TriggerEditorStrings, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp2)
            .clip(KnotworkTheme.shapes.md)
            .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.md),
    ) {
        LabeledSwitchRow(
            label = strings.enabledLabel,
            description = strings.enabledDesc,
            checked = enabled,
            onToggle = onToggle,
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
private fun NoteCard(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp1)
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(1.dp, KnotworkTheme.extended.divider, KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                text = title,
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = body, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurfaceMuted)
        }
    }
}

@Composable
private fun FieldLabel(label: String, error: Boolean = false, hint: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = KnotworkTextStyles.LabelSm,
            color = if (error) KnotworkTheme.extended.riskDestructive else KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(text = hint, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
        }
    }
}

@Composable
private fun FieldError(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            imageVector = AppIcons.AlertCircle,
            contentDescription = null,
            tint = KnotworkTheme.extended.riskDestructive,
            modifier = Modifier.size(12.dp),
        )
        Text(text = text, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.riskDestructive)
    }
}

@Composable
private fun EditorField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    error: Boolean = false,
    numeric: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(
                width = 1.dp,
                color = if (error) KnotworkTheme.extended.riskDestructive else MaterialTheme.colorScheme.outline,
                shape = KnotworkTheme.shapes.md,
            )
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            textStyle = KnotworkTextStyles.BodyBase.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.isEmpty()) {
            Text(text = placeholder, style = KnotworkTextStyles.BodyBase, color = KnotworkTheme.extended.onSurfaceDim)
        }
    }
}

@Composable
private fun PromptField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PromptMinHeight)
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = KnotworkTextStyles.BodyBase.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = KnotworkTextStyles.BodyBase,
                        color = KnotworkTheme.extended.onSurfaceDim,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .border(1.dp, KnotworkTheme.extended.riskDestructive, KnotworkTheme.shapes.full)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = AppIcons.Trash,
            contentDescription = null,
            tint = KnotworkTheme.extended.riskDestructive,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = KnotworkTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
            color = KnotworkTheme.extended.riskDestructive,
        )
    }
}

/** Localised string bundle threaded into [TriggerEditorContent]. */
@Suppress("LongParameterList")
data class TriggerEditorStrings(
    val titleNew: String = "New trigger",
    val titleEdit: String = "Edit trigger",
    val subtitleNew: String = "draft",
    val subtitleEdit: String = "saved",
    val closeCd: String = "Close",
    val save: String = "Save",
    val required: String = "required",
    val kicker: String = "TRIGGER",
    val kickerBody: String = "A condition runs a pipeline in the background — the result lands in chat.",
    val nameLabel: String = "NAME",
    val namePlaceholder: String = "e.g. Overnight summary",
    val nameErrorText: String = "Name is required",
    val conditionLabel: String = "WHEN",
    val typeInterval: String = "Interval",
    val typeIntervalSub: String = "Every N min / h",
    val typeDaily: String = "Daily",
    val typeDailySub: String = "Time of day",
    val typeCharging: String = "Charging",
    val typeChargingSub: String = "Charger connects",
    val typeNetwork: String = "Network",
    val typeNetworkSub: String = "On connect",
    val intervalCustom: String = "Custom",
    val intervalErrorText: String = "Interval must be at least 15 minutes",
    val intervalFloorNote: String = "Background runs are batched; the OS may defer by a few minutes. Minimum 15 min.",
    val unitMinutes: String = "minutes",
    val unitHours: String = "hours",
    val dailyFieldLabel: String = "TIME OF DAY · 24H",
    val chargingTitle: String = "When the charger connects",
    val chargingBody: String = "Fires once each time the device starts charging; re-arms when you unplug.",
    val wifiOnlyLabel: String = "Wi-Fi only",
    val wifiOnlyDesc: String = "On = only Wi-Fi connections fire it. Off = any network.",
    val ssidSectionLabel: String = "Only on these Wi-Fi networks",
    val ssidSectionDesc: String =
        "Leave empty to fire on any Wi-Fi. Add names to fire only on them (needs location permission).",
    val ssidPlaceholder: String = "e.g. Home",
    val ssidAdd: String = "Add",
    val pipelineLabel: String = "RUN THIS PIPELINE",
    val pipelineNone: String = "None — leave unbound",
    val pipelineNoneSub: String = "The trigger stays inert",
    val promptLabel: String = "INPUT PROMPT",
    val promptPlaceholder: String =
        "What should the agent do when this fires? e.g. Summarize today's unread mail and post the digest.",
    val enabledLabel: String = "Enabled",
    val enabledDesc: String = "Off = saved but won't fire. Also toggleable from the list.",
    val timeOk: String = "OK",
    val cancel: String = "Cancel",
    val deleteTrigger: String = "Delete trigger",
)
