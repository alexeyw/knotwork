@file:Suppress(
    "TooManyFunctions",
    "LongMethod",
    "LongParameterList",
)

package app.knotwork.design.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.chips.ChipStyle
import app.knotwork.design.components.chips.KnotworkChip
import app.knotwork.design.components.dialogs.TypedConfirmDialog
import app.knotwork.design.components.dialogs.TypedConfirmDialogState
import app.knotwork.design.components.misc.KnotworkLoader
import app.knotwork.design.components.misc.KnotworkWarningBanner
import app.knotwork.design.components.misc.StripedPlaceholder
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

// ─── Category scaffold ───────────────────────────────────────────────────────

/**
 * Shared chrome for a settings category sub-screen: a top app bar (back + title
 * + "N settings" subtitle) over a vertically-scrollable body. The hub uses its
 * own scaffold; this is for the eight category routes.
 *
 * @param title Localised category title.
 * @param subtitle Pre-formatted "N settings" subtitle.
 * @param onBack Up navigation.
 * @param modifier Layout modifier applied to the outer Box.
 * @param overlay Optional surface-wide overlay (restart banner / dialog) drawn
 *   above the scaffold body.
 * @param content Body rows, laid out in a spaced Column inside the scroll area.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                KnotworkTopAppBarShell {
                    CategoryTopBar(title = title, subtitle = subtitle, onBack = onBack)
                }
            },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = KnotworkTheme.spacing.sp4,
                        vertical = KnotworkTheme.spacing.sp3,
                    )
                    .testTag(SETTINGS_CATEGORY_BODY_TEST_TAG),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            ) {
                content()
            }
        }
        overlay()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

// ─── Section labels ──────────────────────────────────────────────────────────

/**
 * Small uppercase section label (e.g. "BASIC", "CATEGORIES"), optionally with a
 * trailing slot.
 */
@Composable
fun SettingsSectionLabel(text: String, trailing: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text.uppercase(),
            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

// ─── Advanced disclosure ─────────────────────────────────────────────────────

/**
 * The in-category "Advanced — change deliberately" collapsible disclosure. Owns
 * its expanded state so the category Content stays stateless; [initiallyExpanded]
 * seeds it (used by snapshot fixtures to capture the expanded matrix). Content
 * expands/collapses via [AnimatedVisibility]; under reduced motion the catalog
 * theme already disables the underlying transition curve.
 *
 * @param initiallyExpanded Seed for the internal expanded flag.
 * @param content Advanced rows revealed when expanded.
 */
@Composable
internal fun AdvancedDisclosure(initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_EXPANDED_ROTATION else 0f,
        label = "advanced-chevron",
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        // A labelled rule, not a card. The card read as one more setting to
        // decide about — the heaviest object on a screen the closed test already
        // called too long — where this is what it always was: the line where the
        // everyday settings end. Drawn to the design handoff: mono, uppercase,
        // muted, a divider taking the remaining width, and the chevron.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = KnotworkTheme.spacing.sp2)
                .testTag(ADVANCED_DISCLOSURE_TEST_TAG),
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_advanced_title),
                style = KnotworkTextStyles.MonoSm.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = AdvancedLabelTracking,
                ),
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            HorizontalDivider(
                color = KnotworkTheme.extended.divider,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = AppIcons.ArrowDown,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier
                    .size(AdvancedChevronSize)
                    .rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            ) {
                content()
            }
        }
    }
}

// ─── Rows ────────────────────────────────────────────────────────────────────

/**
 * Icon + title + state + trailing Material Switch; the whole row toggles.
 *
 * @param state What the row is set to **now** — `NPU · auto`, `412 memories`.
 *   Never a sentence about what the row means: that is what [hintAnchor]
 *   summons. The two were one slot until the closed test showed that muted text
 *   under a label reads as machine state and goes unread.
 */
@Composable
internal fun IconToggleRow(
    icon: ImageVector,
    title: String,
    state: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(KnotworkTheme.spacing.sp5),
            )
            Column(modifier = Modifier.weight(1f)) {
                RowLabelWithHint(title = title)
                if (state.isNotBlank()) {
                    Text(
                        text = state,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.scale(SWITCH_SCALE),
            )
        }
        SettingsHintBody()
    }
}

/**
 * Plain label for a dropdown row.
 *
 * Carries **no** help glyph: the whole row is the dropdown's `menuAnchor`, and
 * a press anywhere inside it — the glyph included — opens the menu instead of
 * the explanation. The glyph is emitted by [DropdownRowHeader] above the row,
 * outside the anchor, where a press reaches it.
 *
 * @param title Row label.
 */
@Composable
private fun DropdownRowLabel(title: String) {
    Text(
        text = title,
        style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * A row label with its help glyph, laid out so the glyph wraps onto its own
 * line instead of squeezing the label at large font scales.
 *
 * @param title Row label.
 */
@Composable
private fun RowLabelWithHint(title: String) {
    // Row with a weighted label, not FlowRow. FlowRow wraps the glyph onto a
    // line of its own as soon as the label fills the width — which on
    // "Auto-extract from conversations" left the ⓘ sitting under the text,
    // reading as a stray control. Weighting the label lets the text wrap inside
    // itself and keeps the glyph beside it.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        SettingsHintGlyph(settingName = title)
    }
}

/**
 * Icon + title + state + trailing chevron; routes to another screen.
 *
 * @param stateWarning Renders the state line as a warning — a leading warn glyph
 *   plus the warn tint — for a row whose current value leaves the feature
 *   incomplete. The glyph is not decoration: it is what carries the state for a
 *   reader who cannot see the tint, so the row never says "something is wrong"
 *   in colour alone.
 */
@Composable
internal fun NavLinkRow(
    icon: ImageVector,
    title: String,
    state: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stateWarning: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(KnotworkTheme.spacing.sp5),
            )
            Column(modifier = Modifier.weight(1f)) {
                RowLabelWithHint(title = title)
                if (state.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                    ) {
                        if (stateWarning) {
                            Icon(
                                imageVector = AppIcons.Warn,
                                contentDescription = null,
                                tint = KnotworkTheme.extended.signalWarn,
                                modifier = Modifier.size(NavLinkWarnGlyphSize),
                            )
                        }
                        Text(
                            text = state,
                            style = KnotworkTextStyles.MonoSm,
                            color = if (stateWarning) {
                                KnotworkTheme.extended.signalWarn
                            } else {
                                KnotworkTheme.extended.onSurfaceMuted
                            },
                        )
                    }
                }
            }
            Icon(
                imageVector = AppIcons.ArrowR,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        SettingsHintBody()
    }
}

/** Letter-spacing of the ADVANCED rule label. */
private val AdvancedLabelTracking = 0.9.sp

/** Chevron size on the ADVANCED rule. */
private val AdvancedChevronSize = 16.dp

/** Leading glyph size of a [NavLinkRow] warning state. */
private val NavLinkWarnGlyphSize = 13.dp

/** Collapsed external-provider row (masked key fingerprint + model + LAN pill). */
@Composable
internal fun ProviderNavRow(row: ProviderRowState, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(PROVIDER_ROW_TAG_PREFIX + row.id),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(LOCAL_MODEL_TILE_SIZE)
                .clip(KnotworkTheme.shapes.sm)
                .background(color = KnotworkTheme.extended.surface2),
        ) {
            Icon(imageVector = AppIcons.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = row.title,
                    style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (row.isLan) {
                    Surface(shape = KnotworkTheme.shapes.full, color = KnotworkTheme.extended.surface2) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_providers_lan),
                            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(
                                horizontal = KnotworkTheme.spacing.sp2,
                                vertical = KnotworkTheme.spacing.sp1,
                            ),
                        )
                    }
                }
            }
            val subtitle = when {
                row.fingerprint == null -> androidx.compose.ui.res.stringResource(
                    R.string.knotwork_settings_providers_not_configured,
                )
                row.endpointHint != null -> "${row.endpointHint} · ${row.model.orEmpty()}"
                row.model != null -> "${row.fingerprint} · ${row.model}"
                else -> row.fingerprint
            }
            Text(text = subtitle, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
        }
        Icon(imageVector = AppIcons.ArrowR, contentDescription = null, tint = KnotworkTheme.extended.onSurfaceMuted)
    }
}

// ─── System-instructions textarea ────────────────────────────────────────────

/**
 * System-instructions textarea with its `$VARIABLE` chip row and counter. Kept
 * stateless apart from a local `TextFieldValue` cache that preserves the cursor
 * across the DataStore round-trip (the host re-pushes the persisted string on
 * every keystroke).
 */
@Composable
internal fun SystemInstructionsField(
    state: SystemInstructionsCardState,
    onValueChange: (String) -> Unit,
    onChipInsert: (String) -> Unit,
) {
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(text = state.value, selection = TextRange(state.value.length)))
    }
    androidx.compose.runtime.LaunchedEffect(state.value) {
        if (state.value != fieldValue.text) {
            fieldValue = TextFieldValue(text = state.value, selection = TextRange(state.value.length))
        }
    }
    SettingsFieldHeader(
        title = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_system_instructions_title),
    )
    OutlinedTextField(
        value = fieldValue,
        onValueChange = { next ->
            fieldValue = next
            if (next.text != state.value) onValueChange(next.text)
        },
        placeholder = { Text(state.placeholder, style = KnotworkTextStyles.MonoSm) },
        textStyle = KnotworkTextStyles.MonoSm,
        minLines = SYSTEM_INSTRUCTIONS_MIN_LINES,
        maxLines = SYSTEM_INSTRUCTIONS_MAX_LINES,
        isError = state.validationError != null,
        shape = KnotworkTheme.shapes.md,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SYSTEM_INSTRUCTIONS_FIELD_TEST_TAG),
    )
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items = state.variableChips, key = { it }) { placeholder ->
            KnotworkChip(label = placeholder, style = ChipStyle.Outline, onClick = { onChipInsert(placeholder) })
        }
    }
    // The panel the header's glyph opens. Without it the glyph rendered, was
    // announced by TalkBack, took the "one open at a time" slot away from
    // whichever hint was open — and showed nothing.
    SettingsHintBody()
    // The prose helper that used to sit here is gone: it explained what the
    // field is for, which the header's hint now does, and it was drawn in a Row
    // with the counter without a width constraint of its own — so at any real
    // length the two overlapped. The counter is state, and stays.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = androidx.compose.ui.res.stringResource(
                R.string.knotwork_settings_system_instructions_counter,
                state.characterCount,
                state.characterLimit,
                state.approximateTokens,
            ),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
    if (state.validationError != null) {
        Text(
            text = state.validationError,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.signalError,
        )
    }
}

/**
 * Title line for a settings control that has no label of its own — the two
 * textareas and the rows built from bare Composables rather than from
 * [IconToggleRow] / [NavLinkRow].
 *
 * Carries the row's help glyph, so a control that is not one of the standard
 * rows still has somewhere to open its explanation.
 *
 * @param title The control's name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsFieldHeader(title: String) {
    FlowRow(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        SettingsHintGlyph(settingName = title, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

// ─── Dropdown rows ───────────────────────────────────────────────────────────

/** Restart-gated local-model backend selector (labelled row + dropdown menu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackendDropdownRow(
    title: String,
    backendLabel: String,
    selectedBackend: String,
    options: List<String>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        // The glyph sits on the row but OUTSIDE the menu anchor, which is
        // applied to the inner Row. Anchoring the whole row made every press
        // inside it — the glyph included — open the dropdown instead of the
        // explanation; hoisting the glyph to a line of its own fixed that and
        // left it floating above the row, attached to nothing.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                modifier = Modifier
                    .weight(1f)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
            ) {
                Icon(
                    imageVector = AppIcons.Chip,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.size(KnotworkTheme.spacing.sp5),
                )
                Column(modifier = Modifier.weight(1f)) {
                    DropdownRowLabel(title = title)
                    Text(
                        text = backendLabel,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
                Text(
                    text = selectedBackend,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
                Icon(
                    imageVector = AppIcons.ArrowDown,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            SettingsHintGlyph(settingName = title)
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
    SettingsHintBody()
}

/** Embedding-provider selector (labelled row + dropdown menu). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmbeddingProviderDropdownRow(
    title: String,
    selectedLabel: String,
    options: List<EmbeddingOptionRow>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                modifier = Modifier
                    .weight(1f)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    .testTag(MEMORY_EMBEDDING_ROW_TAG),
            ) {
                Icon(
                    imageVector = AppIcons.Ram,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                    modifier = Modifier.size(KnotworkTheme.spacing.sp5),
                )
                Column(modifier = Modifier.weight(1f)) {
                    DropdownRowLabel(title = title)
                    Text(
                        text = selectedLabel,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
                Icon(
                    imageVector = AppIcons.ArrowDown,
                    contentDescription = null,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            SettingsHintGlyph(settingName = title)
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
    SettingsHintBody()
}

// ─── Active pill / re-embed banner / loading ─────────────────────────────────

/** Small "Active" success pill rendered beside the active model name. */
@Composable
internal fun ActivePill() {
    Surface(
        shape = KnotworkTheme.shapes.full,
        color = KnotworkTheme.extended.signalSuccess.copy(alpha = ACTIVE_PILL_ALPHA),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_local_model_active_pill),
            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
        )
    }
}

/**
 * Persistent provider-mismatch warning banner with an inline re-embed button.
 *
 * @param text Localised banner message.
 * @param buttonLabel Localised re-embed button label.
 * @param onReembedClick Same callback as the Memory Re-embed button.
 */
@Composable
internal fun ReembedBanner(text: String, buttonLabel: String, onReembedClick: () -> Unit) {
    KnotworkWarningBanner(
        text = text,
        actionLabel = buttonLabel,
        onAction = onReembedClick,
        testTag = MEMORY_REEMBED_BANNER_TAG,
    )
}

/** Re-embed in-flight progress bar + dot loader, `0..100`. */
@Composable
internal fun ReembedProgress(progressPercent: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = { progressPercent / PERCENT_DENOMINATOR },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Box(modifier = Modifier.size(KnotworkTheme.spacing.sp4)) { KnotworkLoader() }
            Text(
                text = androidx.compose.ui.res.stringResource(
                    R.string.knotwork_settings_memory_reembed_progress,
                    progressPercent,
                ),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

/** Striped loading skeleton used by the hub before the first read settles. */
@Composable
internal fun SettingsLoadingBody(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(KnotworkTheme.spacing.sp4),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        repeat(SETTINGS_LOADING_ROWS) {
            StripedPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LOADING_ROW_HEIGHT),
            )
        }
    }
}

// ─── Restart banner & destructive dialogs ────────────────────────────────────

/** Bottom-anchored "changes take effect on restart" banner with a Restart action. */
@Composable
internal fun RestartBanner(message: String, onRestart: () -> Unit) {
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxSize()
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Surface(
            shape = KnotworkTheme.shapes.md,
            color = KnotworkTheme.extended.surface3,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(RESTART_BANNER_TEST_TAG),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
            ) {
                Text(
                    text = message,
                    style = KnotworkTextStyles.BodyBase,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                app.knotwork.design.components.buttons.KnotworkTextButton(
                    text = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_restart_action),
                    onClick = onRestart,
                )
            }
        }
    }
}

/** Renders the staged destructive dialog (typed-confirm for Clear, plain for Reset). */
@Composable
internal fun DestructiveDialogHost(payload: DestructiveActionState, callbacks: SettingsCallbacks) {
    when (payload.kind) {
        DestructiveActionKind.ClearMemory -> TypedConfirmDialog(
            state = TypedConfirmDialogState(
                title = payload.title,
                body = payload.body,
                keyword = payload.keyword,
                hint = payload.hint,
                pendingInput = payload.pendingInput,
            ),
            confirmLabel = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_destructive_confirm),
            cancelLabel = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_destructive_cancel),
            onInputChange = callbacks.onDestructiveTypedConfirmChange,
            onConfirm = callbacks.onDestructiveConfirm,
            onCancel = callbacks.onDestructiveCancel,
            fieldTestTag = DESTRUCTIVE_TYPED_FIELD_TEST_TAG,
            confirmTestTag = DESTRUCTIVE_CONFIRM_BUTTON_TEST_TAG,
        )
        DestructiveActionKind.ResetSettings -> AlertDialog(
            onDismissRequest = callbacks.onDestructiveCancel,
            title = { Text(payload.title) },
            text = { Text(text = payload.body, style = KnotworkTextStyles.BodyBase) },
            confirmButton = {
                KnotworkPrimaryButton(
                    text = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_destructive_confirm),
                    onClick = callbacks.onDestructiveConfirm,
                    modifier = Modifier.testTag(RESET_CONFIRM_BUTTON_TEST_TAG),
                )
            },
            dismissButton = {
                TextButton(onClick = callbacks.onDestructiveCancel) {
                    Text(text = androidx.compose.ui.res.stringResource(R.string.knotwork_settings_destructive_cancel))
                }
            },
        )
    }
}

// ─── Shared slider list ──────────────────────────────────────────────────────

/**
 * Renders a list of [SettingSliderRow]s through `KnotworkParamSlider`. Each row
 * is wrapped in a [SettingsAnchor] keyed by its [SettingSliderRow.anchorKey] so
 * a deep-link from settings search can scroll it into view and flash it.
 */
@Composable
internal fun SettingSliderList(
    sliders: List<SettingSliderRow>,
    tagPrefix: String,
    onChange: (id: String, value: Float) -> Unit,
) {
    sliders.forEach { slider ->
        SettingsAnchor(anchorKey = slider.anchorKey) {
            KnotworkParamSlider(
                label = slider.title,
                valueLabel = slider.valueLabel,
                value = slider.value,
                onValueChange = { newValue -> onChange(slider.id, newValue) },
                valueRange = slider.valueRange,
                steps = slider.steps,
                errorText = slider.errorText,
                modifier = Modifier.testTag(tagPrefix + slider.id),
            )
        }
    }
}

// ─── Search deep-link highlight ──────────────────────────────────────────────

/**
 * The anchor key of the settings row a search deep-link asked to highlight, or
 * `null` when no highlight is pending. A category sub-screen provides this around
 * its body; every [SettingsAnchor] reads it to decide whether to flash.
 */
val LocalSettingsHighlightKey = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * Wraps a settings row so a search deep-link can scroll it into view and briefly
 * accent it. The row flashes when [anchorKey] matches [LocalSettingsHighlightKey]:
 * a 1 dp primary outline plus a faint tonal fill that fades over
 * [SETTINGS_ANCHOR_FLASH_MS]. Under reduced motion the accent is static (no
 * fade), per the design's reduced-motion contract.
 *
 * @param anchorKey Stable anchor of the wrapped row; `null` (or unmatched) leaves
 *   the row untouched.
 * @param modifier Layout modifier applied to the wrapper.
 * @param content The row to render.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingsAnchor(anchorKey: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val active = anchorKey != null && LocalSettingsHighlightKey.current == anchorKey
    // The anchor is provided around BOTH branches. Providing it only around the
    // unhighlighted one would take the help glyph away from the row a search
    // deep-link just landed on — the single moment the user has demonstrably
    // asked "what is this?" — and pop it back in when the flash expires.
    CompositionLocalProvider(LocalSettingsRowAnchor provides anchorKey) {
        SettingsAnchorBody(active = active, anchorKey = anchorKey, modifier = modifier, content = content)
    }
}

/**
 * The highlight body of [SettingsAnchor], split out so the anchor
 * `CompositionLocalProvider` wraps both branches rather than one.
 *
 * @param active Whether this row is the deep-link target being flashed.
 * @param anchorKey Stable anchor of the wrapped row.
 * @param modifier Layout modifier applied to the wrapper.
 * @param content The row to render.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsAnchorBody(
    active: Boolean,
    anchorKey: String?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    // A Column (not Box) preserves the vertical stacking + spacing of rows that
    // emit several siblings (e.g. the system-instructions field + chip row), which
    // would otherwise overlap when wrapped. Single-child rows are unaffected.
    if (!active) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        ) { content() }
        return
    }
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(1f) }
    LaunchedEffect(anchorKey) {
        requester.bringIntoView()
        if (reducedMotion) flash.snapTo(1f) else flash.animateTo(0f, tween(SETTINGS_ANCHOR_FLASH_MS))
    }
    val intensity = if (reducedMotion) 1f else flash.value
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .bringIntoViewRequester(requester)
            .clip(KnotworkTheme.shapes.md)
            .background(accent.copy(alpha = intensity * SETTINGS_ANCHOR_FILL_ALPHA))
            .border(SETTINGS_ANCHOR_BORDER, accent.copy(alpha = intensity), KnotworkTheme.shapes.md)
            .padding(SETTINGS_ANCHOR_PADDING),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        content()
    }
}

// ─── Constants ───────────────────────────────────────────────────────────────

/** Test tag for the scrollable body of a category sub-screen. */
const val SETTINGS_CATEGORY_BODY_TEST_TAG: String = "settings_category_body"

/** Test tag for the Advanced disclosure header row. */
const val ADVANCED_DISCLOSURE_TEST_TAG: String = "settings_advanced_disclosure"

/** Test tag for the system-instructions text field. */
const val SYSTEM_INSTRUCTIONS_FIELD_TEST_TAG: String = "settings_system_instructions_field"

/** Test tag for the restart-required banner. */
const val RESTART_BANNER_TEST_TAG: String = "settings_restart_banner"

/** Test tag for the destructive typed-confirm field. */
const val DESTRUCTIVE_TYPED_FIELD_TEST_TAG: String = "settings_destructive_typed_field"

/** Test tag for the destructive typed-confirm button. */
const val DESTRUCTIVE_CONFIRM_BUTTON_TEST_TAG: String = "settings_destructive_confirm"

/** Test tag for the plain "Reset all settings" confirm button. */
const val RESET_CONFIRM_BUTTON_TEST_TAG: String = "settings_reset_confirm"

/** Test-tag prefix for provider rows. */
const val PROVIDER_ROW_TAG_PREFIX: String = "settings_provider_row_"

/** Test-tag prefix for generation sliders. */
const val GENERATION_SLIDER_TAG_PREFIX: String = "settings_generation_slider_"

/** Test-tag prefix for memory tuning sliders. */
const val MEMORY_PARAM_ROW_TAG_PREFIX: String = "settings_memory_param_"

/** Test-tag prefix for pipeline sliders. */
const val PIPELINE_SLIDER_TAG_PREFIX: String = "settings_pipeline_slider_"

/** Test-tag prefix for background sliders. */
const val BACKGROUND_SLIDER_TAG_PREFIX: String = "settings_background_slider_"

/** Test-tag prefix for tool / workspace ceiling sliders. */
const val TOOLS_SLIDER_TAG_PREFIX: String = "settings_tools_slider_"

/** Test-tag prefix for privacy retention sliders. */
const val PRIVACY_PARAM_ROW_TAG_PREFIX: String = "settings_privacy_param_"

/** Test tag for the Memory embedding-provider dropdown anchor row. */
const val MEMORY_EMBEDDING_ROW_TAG: String = "settings_memory_embedding_row"

/** Test tag for the inline Memory tuning validation-error text. */
const val MEMORY_VALIDATION_ERROR_TAG: String = "settings_memory_validation_error"

/** Test tag for the embedding-provider-mismatch "re-embed recommended" banner. */
const val MEMORY_REEMBED_BANNER_TAG: String = "settings_memory_reembed_banner"

/** Test-tag prefix for the eight hub category navigation rows. */
const val HUB_CATEGORY_ROW_TAG_PREFIX: String = "settings_hub_category_"

internal val LOCAL_MODEL_TILE_SIZE = 40.dp
internal val SectionCardBorder = 1.dp
private val LOADING_ROW_HEIGHT = 56.dp
private const val SETTINGS_LOADING_ROWS = 6
private const val SYSTEM_INSTRUCTIONS_MIN_LINES = 5
private const val SYSTEM_INSTRUCTIONS_MAX_LINES = 12
internal const val ACTIVE_PILL_ALPHA = 0.18f
private const val REEMBED_BANNER_TINT_ALPHA = 0.12f
private const val PERCENT_DENOMINATOR = 100f
private const val CHEVRON_EXPANDED_ROTATION = 180f

/** Duration of the search deep-link flash before it fades out. */
private const val SETTINGS_ANCHOR_FLASH_MS = 1200

/** Width of the deep-link highlight outline. */
private val SETTINGS_ANCHOR_BORDER = 1.dp

/** Inset between the highlight outline and the wrapped row content. */
private val SETTINGS_ANCHOR_PADDING = 2.dp

/** Peak alpha of the faint tonal fill behind a flashed row. */
private const val SETTINGS_ANCHOR_FILL_ALPHA = 0.07f

/**
 * Relative weight of the trailing segmented control inside the "Approve tool
 * calls" row so each option fits its longest label at the default font scale.
 */
internal const val SEGMENTED_TRAILING_WEIGHT = 2.5f

/** Visual scale of the Material3 Switch so it stays balanced against 14 sp titles. */
private const val SWITCH_SCALE = 0.78f

/** Overflow handling for single-line model names. */
internal val ModelNameOverflow = TextOverflow.Ellipsis
