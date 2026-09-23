@file:Suppress("MatchingDeclarationName") // Hosts SkillEditorContent + helpers.

package app.knotwork.design.screens.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.RiskPill
import app.knotwork.design.components.controls.KnotworkSegmentedControl
import app.knotwork.design.components.controls.LabeledSwitchRow
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the identity tile on the editor header strip. */
private val EditorTile = 44.dp

/** Inner glyph size on the identity tile. */
private val EditorTileGlyph = 22.dp

/** Minimum height of the multi-line instruction field. */
private val InstructionMinHeight = 132.dp

/**
 * Stateless full-screen Skill editor — create or edit a skill's name,
 * description, instruction, tool allowlist (tri-state) and context flags.
 *
 * @param state immutable editor state.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings.
 * @param callbacks one-shot callback bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorContent(
    state: SkillEditorUi,
    modifier: Modifier = Modifier,
    strings: SkillEditorStrings = SkillEditorStrings(),
    callbacks: SkillEditorCallbacks = noopSkillEditorCallbacks(),
) {
    val editing = state.id != null && !state.readOnly
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

            FieldLabel(
                label = strings.nameLabel,
                error = state.nameError,
                hint = if (state.readOnly) null else strings.required,
            )
            EditorField(
                value = state.name,
                placeholder = strings.namePlaceholder,
                onValueChange = callbacks.onNameChange,
                error = state.nameError,
                readOnly = state.readOnly,
            )
            if (state.nameError) FieldError(text = strings.nameError)

            FieldLabel(label = strings.descriptionLabel, hint = if (state.readOnly) null else strings.descriptionHint)
            EditorField(
                value = state.description,
                placeholder = strings.descriptionPlaceholder,
                onValueChange = callbacks.onDescriptionChange,
                readOnly = state.readOnly,
            )

            FieldLabel(
                label = strings.instructionLabel,
                error = state.instructionError,
                hint = if (state.readOnly) null else strings.required,
            )
            InstructionField(
                value = state.instruction,
                placeholder = strings.instructionPlaceholder,
                variables = state.availableVariables,
                error = state.instructionError,
                onValueChange = callbacks.onInstructionChange,
                readOnly = state.readOnly,
            )
            if (state.instructionError) FieldError(text = strings.instructionError)
            if (!state.readOnly) {
                VariableInsertRow(variables = state.availableVariables, strings = strings, callbacks = callbacks)
            }

            ToolAllowlist(state = state, strings = strings, callbacks = callbacks)
            ContextFlags(state = state, strings = strings, callbacks = callbacks)

            if (editing) {
                Box(modifier = Modifier.padding(top = KnotworkTheme.spacing.sp4)) {
                    DangerButton(label = strings.deleteSkill, onClick = callbacks.onDelete)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    state: SkillEditorUi,
    editing: Boolean,
    strings: SkillEditorStrings,
    callbacks: SkillEditorCallbacks,
) {
    val title = when {
        state.readOnly -> strings.titleView
        editing -> strings.titleEdit
        else -> strings.titleNew
    }
    val subtitle = when {
        state.readOnly -> strings.subtitleReadOnly
        state.fromBundled -> strings.subtitleDuplicated
        editing -> strings.subtitleMine
        else -> strings.subtitleDraft
    }
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
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
        actions = {
            if (!state.readOnly) {
                SaveButton(enabled = state.canSave, label = strings.save, onClick = callbacks.onSave)
            }
        },
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
private fun IdentityStrip(strings: SkillEditorStrings) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = KnotworkTheme.spacing.sp2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Box(
            modifier = Modifier
                .size(EditorTile)
                .clip(KnotworkTheme.shapes.md)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Skill,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(EditorTileGlyph),
            )
        }
        Column {
            Text(
                text = strings.skillKicker,
                style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
                color = accent,
            )
            Text(
                text = strings.skillKickerBody,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun ToolAllowlist(state: SkillEditorUi, strings: SkillEditorStrings, callbacks: SkillEditorCallbacks) {
    val hint = if (state.toolMode == SkillToolMode.Restrict) {
        strings.toolsSelectedFormat.format(state.selectedToolCount, state.toolTotal)
    } else {
        null
    }
    FieldLabel(label = strings.toolsLabel, hint = hint)
    KnotworkSegmentedControl(
        options = listOf(strings.toolModeAll, strings.toolModeRestrict, strings.toolModeNone),
        selectedIndex = when (state.toolMode) {
            SkillToolMode.All -> 0
            SkillToolMode.Restrict -> 1
            SkillToolMode.None -> 2
        },
        onSelect = { index ->
            if (state.readOnly) return@KnotworkSegmentedControl
            callbacks.onToolModeChange(
                when (index) {
                    0 -> SkillToolMode.All
                    1 -> SkillToolMode.Restrict
                    else -> SkillToolMode.None
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    when (state.toolMode) {
        SkillToolMode.All -> NoteCard(
            icon = AppIcons.Globe,
            title = strings.toolsAllTitle,
            body = strings.toolsAllBody,
        )
        SkillToolMode.None -> NoteCard(
            icon = AppIcons.Block,
            title = strings.toolsNoneTitle,
            body = strings.toolsNoneBody,
        )
        SkillToolMode.Restrict -> ToolSelector(state = state, strings = strings, callbacks = callbacks)
    }
}

@Composable
private fun ToolSelector(state: SkillEditorUi, strings: SkillEditorStrings, callbacks: SkillEditorCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp2)
            .clip(KnotworkTheme.shapes.md)
            .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KnotworkTheme.extended.surface1)
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.toolsSelectHeaderFormat.format(state.selectedToolCount, state.toolTotal),
                style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            if (!state.readOnly) KnotworkTextButton(text = strings.toolsClear, onClick = callbacks.onToolsClear)
        }
        state.tools.forEachIndexed { index, tool ->
            if (index > 0) HorizontalDivider(color = KnotworkTheme.extended.divider)
            ToolSelectorRow(
                tool = tool,
                readOnly = state.readOnly,
                onToggle = { callbacks.onToolToggle(tool.id) },
            )
        }
    }
}

@Composable
private fun ToolSelectorRow(tool: SkillToolOptionUi, readOnly: Boolean, onToggle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (tool.selected) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface,
            )
            .then(if (readOnly) Modifier else Modifier.clickable(onClick = onToggle))
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Checkbox(checked = tool.selected)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = tool.id,
                    style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                tool.risk?.let { RiskPill(risk = it) }
            }
            Text(
                text = tool.description,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun Checkbox(checked: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(KnotworkTheme.shapes.xs)
            .background(if (checked) accent else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (checked) accent else KnotworkTheme.extended.outlineStrong,
                shape = KnotworkTheme.shapes.xs,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ContextFlags(state: SkillEditorUi, strings: SkillEditorStrings, callbacks: SkillEditorCallbacks) {
    val onCount = state.contextFlags.count { it.enabled }
    FieldLabel(
        label = strings.contextLabel,
        hint = strings.contextOnFormat.format(onCount, state.contextFlags.size),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.md),
    ) {
        state.contextFlags.forEachIndexed { index, flag ->
            if (index > 0) HorizontalDivider(color = KnotworkTheme.extended.divider)
            LabeledSwitchRow(
                label = flag.label,
                description = flag.description,
                checked = flag.enabled,
                onToggle = { callbacks.onContextToggle(flag.key) },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                enabled = !state.readOnly,
            )
        }
    }
}

@Composable
private fun NoteCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
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
private fun VariableInsertRow(variables: List<String>, strings: SkillEditorStrings, callbacks: SkillEditorCallbacks) {
    if (variables.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = strings.insertLabel,
            style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.Bold),
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.padding(end = KnotworkTheme.spacing.sp2),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            contentPadding = PaddingValues(horizontal = KnotworkTheme.spacing.sp1),
        ) {
            items(items = variables, key = { it }) { token ->
                Box(
                    modifier = Modifier
                        .clip(KnotworkTheme.shapes.sm)
                        .background(KnotworkTheme.extended.surface1)
                        .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.sm)
                        .clickable { callbacks.onVariableInsert(token) }
                        .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
                ) {
                    Text(
                        text = token,
                        style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
    readOnly: Boolean = false,
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
            readOnly = readOnly,
            singleLine = true,
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
private fun InstructionField(
    value: String,
    placeholder: String,
    variables: List<String>,
    error: Boolean,
    onValueChange: (String) -> Unit,
    readOnly: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InstructionMinHeight)
            .clip(KnotworkTheme.shapes.md)
            .background(KnotworkTheme.extended.surface1)
            .border(
                width = 1.dp,
                color = if (error) KnotworkTheme.extended.riskDestructive else MaterialTheme.colorScheme.outline,
                shape = KnotworkTheme.shapes.md,
            )
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            textStyle = KnotworkTextStyles.MonoSm.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceDim,
                    )
                }
                inner()
            },
        )
        // Highlight pass overlays the same text region; kept invisible behind
        // the editable field for placeholder-empty so we don't double-render.
        if (value.isNotEmpty()) {
            Text(
                text = highlightVariables(text = value, variables = variables),
                style = KnotworkTextStyles.MonoSm,
                color = Color.Transparent,
            )
        }
    }
}

/**
 * Highlight every `$VAR` token in [text] with a tonal background so
 * placeholders pop. Unknown tokens are de-emphasised rather than highlighted.
 */
@Composable
private fun highlightVariables(text: String, variables: List<String>): AnnotatedString {
    val background = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val foreground = MaterialTheme.colorScheme.primary
    val known = variables.toSet()
    return buildAnnotatedString {
        val regex = Regex(pattern = """\$[A-Z_][A-Z0-9_]*""")
        var cursor = 0
        for (match in regex.findAll(text)) {
            if (cursor < match.range.first) append(text.substring(cursor, match.range.first))
            val token = match.value
            val style = if (token in known || variables.isEmpty()) {
                SpanStyle(background = background, color = foreground)
            } else {
                SpanStyle(color = KnotworkTheme.extended.onSurfaceMuted)
            }
            withStyle(style) { append(token) }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
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

/** Localised string bundle threaded into [SkillEditorContent]. */
@Suppress("LongParameterList")
data class SkillEditorStrings(
    val titleNew: String = "New skill",
    val titleEdit: String = "Edit skill",
    val titleView: String = "View skill",
    val subtitleDraft: String = "draft",
    val subtitleMine: String = "mine",
    val subtitleDuplicated: String = "editable copy · from bundled",
    val subtitleReadOnly: String = "bundled · read-only",
    val closeCd: String = "Close",
    val save: String = "Save",
    val required: String = "required",
    val skillKicker: String = "SKILL",
    val skillKickerBody: String = "Instruction · tool restriction · context — reused as one bundle.",
    val nameLabel: String = "NAME",
    val namePlaceholder: String = "e.g. Standup digest",
    val nameError: String = "Name is required",
    val descriptionLabel: String = "DESCRIPTION",
    val descriptionHint: String = "short",
    val descriptionPlaceholder: String = "One line — what this skill does",
    val instructionLabel: String = "INSTRUCTION",
    val instructionPlaceholder: String =
        "Describe the capability. Use \$VARIABLES for runtime values — they're substituted when the skill runs.",
    val instructionError: String = "Instruction can't be empty",
    val insertLabel: String = "INSERT",
    val toolsLabel: String = "TOOLS",
    val toolModeAll: String = "All tools",
    val toolModeRestrict: String = "Restrict",
    val toolModeNone: String = "No tools",
    val toolsSelectedFormat: String = "%1\$d of %2\$d selected",
    val toolsSelectHeaderFormat: String = "SELECT TOOLS · %1\$d/%2\$d",
    val toolsClear: String = "Clear",
    val toolsAllTitle: String = "Every tool — unrestricted",
    val toolsAllBody: String =
        "The skill can call any enabled tool, including tools you add later. " +
            "Pick this for general-purpose skills.",
    val toolsNoneTitle: String = "Instruction only — no tools",
    val toolsNoneBody: String =
        "The skill can't call any tool, even ones the agent has. " +
            "An explicit empty allowlist, not the same as \"unrestricted\".",
    val contextLabel: String = "CONTEXT THE SKILL SEES",
    val contextOnFormat: String = "%1\$d of %2\$d on",
    val deleteSkill: String = "Delete skill",
)
