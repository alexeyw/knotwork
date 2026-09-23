@file:Suppress("MatchingDeclarationName") // Hosts ModelsContent + private helpers.

package app.knotwork.design.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.controls.LabeledSwitchRow
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading chip-style icon tile on Active card / preset rows. */
private val LeadingTileSize = 40.dp

/** Inner glyph size on the leading tile. */
private val LeadingGlyphSize = 20.dp

/** Diameter of the green "active" status dot. */
private val ActiveDotSize = 8.dp

/** Height of the determinate download progress bar shown on a downloading preset. */
private val ProgressBarHeight = 3.dp

/**
 * Stateless Knotwork Models surface
 * (Active card → HF auth → Custom URL → Available presets).
 *
 * @param state immutable view state — drives loader / empty / default / error layouts.
 * @param modifier optional layout modifier applied to the root scaffold.
 * @param strings localised display strings (TopAppBar title + section labels + CTAs).
 * @param callbacks one-shot callback bundle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsContent(
    state: ModelsViewState,
    modifier: Modifier = Modifier,
    strings: ModelsStrings = ModelsStrings(),
    callbacks: ModelsCallbacks = noopModelsCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            androidx.compose.foundation.layout.Column {
                ModelsTopBar(state = state, strings = strings, callbacks = callbacks)
                androidx.compose.material3.HorizontalDivider(color = KnotworkTheme.extended.divider)
            }
        },
    ) { padding ->
        when (state.visualState) {
            ModelsVisualState.Loading -> ModelsLoading(padding = padding)
            ModelsVisualState.Empty -> ModelsEmpty(padding = padding, strings = strings, callbacks = callbacks)
            ModelsVisualState.Error -> ModelsError(
                padding = padding,
                state = state,
                strings = strings,
                callbacks = callbacks,
            )
            ModelsVisualState.Default -> ModelsBody(
                padding = padding,
                state = state,
                strings = strings,
                callbacks = callbacks,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsTopBar(state: ModelsViewState, strings: ModelsStrings, callbacks: ModelsCallbacks) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = strings.title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.subtitle.isNotEmpty()) {
                    Text(
                        text = state.subtitle,
                        style = KnotworkTextStyles.MonoSm,
                        color = KnotworkTheme.extended.onSurfaceMuted,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = strings.backCd,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        // Single top-bar action: open the Hugging Face model-discovery
        // surface. Other model-management actions are not part of v0.x.
        actions = {
            IconButton(onClick = callbacks.onDiscover) {
                Icon(
                    imageVector = AppIcons.Hub,
                    contentDescription = strings.discoverCd,
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

@Composable
private fun ModelsLoading(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModelsEmpty(padding: PaddingValues, strings: ModelsStrings, callbacks: ModelsCallbacks) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = strings.emptyTitle,
            subtitle = strings.emptySubtitle,
            ctaLabel = strings.emptyCta,
            onCtaClick = callbacks.onCustomUrlSubmit,
        )
    }
}

@Composable
private fun ModelsError(
    padding: PaddingValues,
    state: ModelsViewState,
    strings: ModelsStrings,
    callbacks: ModelsCallbacks,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(KnotworkTheme.spacing.sp6),
        contentAlignment = Alignment.Center,
    ) {
        EmptyState(
            title = strings.errorTitle,
            subtitle = state.errorMessage.orEmpty(),
            ctaLabel = strings.errorRetry,
            onCtaClick = callbacks.onRetry,
        )
    }
}

@Composable
private fun ModelsBody(
    padding: PaddingValues,
    state: ModelsViewState,
    strings: ModelsStrings,
    callbacks: ModelsCallbacks,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(
            start = KnotworkTheme.spacing.sp4,
            end = KnotworkTheme.spacing.sp4,
            top = KnotworkTheme.spacing.sp2,
            bottom = KnotworkTheme.spacing.sp4,
        ),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
    ) {
        state.active?.let { active ->
            item(key = "active") {
                ActiveModelCard(active = active, strings = strings, callbacks = callbacks)
            }
        }
        state.performance?.let { performance ->
            item(key = "performance") {
                PerformanceCard(
                    state = performance,
                    strings = strings.performance,
                    callbacks = PerformanceCallbacks(
                        onRunBenchmark = callbacks.onRunBenchmark,
                        onCancelBenchmark = callbacks.onCancelBenchmark,
                        onShareReport = callbacks.onShareBenchmark,
                        onDismissReport = callbacks.onDismissBenchmark,
                    ),
                )
            }
        }
        item(key = "hf-section") {
            SectionHeader(label = strings.hfSection, trailing = strings.hfOptional)
        }
        item(key = "hf-field") {
            AuthTokenField(state = state, strings = strings, callbacks = callbacks)
        }
        item(key = "custom-section") {
            SectionHeader(label = strings.customUrlSection)
        }
        item(key = "custom-field") {
            CustomUrlRow(state = state, strings = strings, callbacks = callbacks)
        }
        state.customDownload?.let { downloading ->
            // In-flight progress for a non-preset custom URL download.
            // Rendered as a standalone progress row directly under the
            // Custom URL field so the user can see something is
            // happening even before the model lands on disk.
            item(key = "custom-progress") {
                CustomDownloadRow(downloading = downloading, strings = strings, callbacks = callbacks)
            }
        }
        item(key = "format-hint") {
            Text(
                text = strings.formatHint,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        item(key = "presets-section") {
            // "All ↗" link is intentionally
            // hidden until we have a destination to send users to —
            // a no-op button is worse UX than no button.
            SectionHeader(label = strings.presetsSection)
        }
        items(items = state.presets, key = { it.id }) { preset ->
            PresetCard(preset = preset, strings = strings, callbacks = callbacks)
        }
        if (state.downloadedRows.isNotEmpty()) {
            item(key = "downloaded-section") {
                SectionHeader(label = strings.downloadedSection)
            }
            items(items = state.downloadedRows, key = { "downloaded:${it.id}" }) { row ->
                PresetCard(preset = row, strings = strings, callbacks = callbacks)
            }
        }
    }
}

/**
 * Standalone progress row rendered under the Custom URL field for the
 * in-flight download of a non-preset model URL.
 */
@Composable
private fun CustomDownloadRow(
    downloading: PresetStatus.Downloading,
    strings: ModelsStrings,
    callbacks: ModelsCallbacks,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface3)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.customDownloadLabel,
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { callbacks.onCustomDownloadCancel() }) {
                Icon(
                    imageVector = AppIcons.X,
                    contentDescription = strings.presetCancelCd,
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        LinearProgressIndicator(
            progress = { downloading.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(ProgressBarHeight),
            color = MaterialTheme.colorScheme.primary,
            trackColor = KnotworkTheme.extended.surface1,
            strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
        )
        Text(
            text = downloading.metaLine ?: "${downloading.progress}%",
            style = KnotworkTextStyles.MonoSm,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SectionHeader(label: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = KnotworkTextStyles.LabelSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun ActiveModelCard(active: ActiveModelRow, strings: ModelsStrings, callbacks: ModelsCallbacks) {
    // Status card with a single interactive affordance: the image-support
    // toggle below the identity row. The identity row itself stays
    // non-interactive (no active-model detail surface in v0.x).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
        ) {
            LeadingChipTile(background = MaterialTheme.colorScheme.surface)
            Column(
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = strings.activeBadge,
                        style = KnotworkTextStyles.LabelSm,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = KnotworkTheme.spacing.sp1)
                            .size(ActiveDotSize)
                            .background(
                                color = KnotworkTheme.extended.signalSuccess,
                                shape = KnotworkTheme.shapes.full,
                            ),
                    )
                }
                Text(
                    text = active.displayName,
                    style = KnotworkTextStyles.MonoBase,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = active.meta,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
        }
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        LabeledSwitchRow(
            label = strings.visionLabel,
            description = strings.visionDescription,
            checked = active.visionSupported,
            onToggle = { callbacks.onToggleVision(!active.visionSupported) },
        )
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        LabeledSwitchRow(
            label = strings.audioLabel,
            description = strings.audioDescription,
            checked = active.audioSupported,
            onToggle = { callbacks.onToggleAudio(!active.audioSupported) },
        )
    }
}

@Composable
private fun LeadingChipTile(background: Color) {
    Box(
        modifier = Modifier
            .size(LeadingTileSize)
            .background(color = background, shape = KnotworkTheme.shapes.sm),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Chip,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurface2,
            modifier = Modifier.size(LeadingGlyphSize),
        )
    }
}

@Composable
private fun AuthTokenField(state: ModelsViewState, strings: ModelsStrings, callbacks: ModelsCallbacks) {
    InlineFieldRow(
        leadingIcon = {
            Icon(
                imageVector = AppIcons.Key,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        },
        value = state.authToken,
        placeholder = strings.hfPlaceholder,
        onChange = callbacks.onAuthTokenChange,
        masked = true,
        trailing = {
            // Sm size so the inline action stays compact, a
            // chip-style "+ Paste" affordance.
            KnotworkPrimaryButton(
                text = strings.hfPaste,
                onClick = callbacks.onAuthTokenPaste,
                size = KnotworkButtonSize.Sm,
            )
        },
    )
}

@Composable
private fun CustomUrlRow(state: ModelsViewState, strings: ModelsStrings, callbacks: ModelsCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        InlineFieldRow(
            leadingIcon = null,
            value = state.customUrl,
            placeholder = strings.customUrlPlaceholder,
            onChange = callbacks.onCustomUrlChange,
            masked = false,
            modifier = Modifier.weight(1f),
        )
        // Sm size keeps it inline: the action chip is anchored
        // to the field row, not a standalone full-width CTA.
        KnotworkPrimaryButton(
            text = strings.customUrlGet,
            onClick = callbacks.onCustomUrlSubmit,
            enabled = state.customUrlEnabled && state.customUrl.isNotBlank(),
            leadingIcon = AppIcons.Download,
            size = KnotworkButtonSize.Sm,
        )
    }
}

@Composable
@Suppress("LongParameterList") // Private layout helper; collapsing hurts call-site clarity.
private fun InlineFieldRow(
    leadingIcon: (@Composable () -> Unit)?,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    masked: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val transformation: VisualTransformation = if (masked && value.isNotEmpty()) {
        PasswordVisualTransformation()
    } else {
        VisualTransformation.None
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface2)
            .border(
                width = 1.dp,
                color = KnotworkTheme.extended.outlineStrong,
                shape = KnotworkTheme.shapes.md,
            )
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
    ) {
        if (leadingIcon != null) leadingIcon()
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                visualTransformation = transformation,
                textStyle = KnotworkTextStyles.MonoBase.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = KnotworkTextStyles.MonoBase,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
private fun PresetCard(preset: PresetRow, strings: ModelsStrings, callbacks: ModelsCallbacks) {
    val isDownloading = preset.status is PresetStatus.Downloading
    val tint = if (isDownloading) KnotworkTheme.extended.surface3 else KnotworkTheme.extended.surface1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = tint)
            .clickable(onClick = { callbacks.onPresetActivate(preset.id) }, role = Role.Button)
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        ) {
            LeadingChipTile(background = MaterialTheme.colorScheme.surface)
            Column(
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = preset.name,
                    style = KnotworkTextStyles.BodyBase,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preset.source,
                    style = KnotworkTextStyles.Caption,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PresetTrailing(preset = preset, strings = strings, callbacks = callbacks)
        }
        if (preset.status is PresetStatus.Downloading) {
            LinearProgressIndicator(
                progress = { preset.status.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(ProgressBarHeight),
                color = MaterialTheme.colorScheme.primary,
                trackColor = KnotworkTheme.extended.surface1,
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )
            Text(
                text = preset.status.metaLine ?: "${preset.status.progress}%",
                style = KnotworkTextStyles.MonoSm,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val sizeMeta = when (val status = preset.status) {
            is PresetStatus.OnDisk -> status.sizeMeta
            is PresetStatus.Active -> status.sizeMeta
            else -> null
        }
        if (sizeMeta != null) {
            Text(
                text = sizeMeta,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun PresetTrailing(preset: PresetRow, strings: ModelsStrings, callbacks: ModelsCallbacks) {
    when (preset.status) {
        is PresetStatus.Idle -> KnotworkPrimaryButton(
            text = strings.presetGet,
            onClick = { callbacks.onPresetDownload(preset.id) },
            leadingIcon = AppIcons.Download,
            size = KnotworkButtonSize.Sm,
        )
        is PresetStatus.Downloading -> IconButton(onClick = { callbacks.onPresetCancelDownload(preset.id) }) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = strings.presetCancelCd,
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        is PresetStatus.OnDisk -> PresetOverflowMenu(
            preset = preset,
            strings = strings,
            callbacks = callbacks,
            includeActivate = true,
        )
        is PresetStatus.Active -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Text(
                text = strings.activeBadge,
                style = KnotworkTextStyles.LabelSm,
                color = KnotworkTheme.extended.signalSuccess,
            )
            Box(
                modifier = Modifier
                    .size(ActiveDotSize)
                    .background(color = KnotworkTheme.extended.signalSuccess, shape = KnotworkTheme.shapes.full),
            )
            // Active row still needs a Delete affordance — the activate
            // entry is hidden because the row is already active.
            PresetOverflowMenu(
                preset = preset,
                strings = strings,
                callbacks = callbacks,
                includeActivate = false,
            )
        }
    }
}

/**
 * Three-dot overflow menu rendered as the trailing affordance for an
 * on-disk model row. Replaces the earlier full-width `Activate` button
 * that was crowding the title.
 *
 * @param includeActivate when `true`, the dropdown surfaces the Activate
 * action; the Active row passes `false` because activating the
 * already-active model is a no-op.
 */
@Composable
private fun PresetOverflowMenu(
    preset: PresetRow,
    strings: ModelsStrings,
    callbacks: ModelsCallbacks,
    includeActivate: Boolean,
) {
    val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value = false) }
    Box {
        IconButton(onClick = { expanded.value = true }) {
            Icon(
                imageVector = AppIcons.More,
                contentDescription = strings.rowMenuCd,
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false },
            containerColor = KnotworkTheme.extended.surface1,
        ) {
            if (includeActivate) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            text = strings.presetActivate,
                            style = KnotworkTextStyles.BodyBase,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        expanded.value = false
                        callbacks.onPresetActivate(preset.id)
                    },
                )
            }
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        text = strings.presetDelete,
                        style = KnotworkTextStyles.BodyBase,
                        color = KnotworkTheme.extended.signalError,
                    )
                },
                onClick = {
                    expanded.value = false
                    callbacks.onPresetDelete(preset.id)
                },
            )
        }
    }
}

/**
 * Localised string bundle threaded into `ModelsContent`. Kept as a `data
 * class` with English defaults so design-side previews compile without
 * resource lookups; the app-side mapper passes localised values.
 */
@Suppress("LongParameterList") // Hand-tuned default-arg list keeps catalog previews terse.
data class ModelsStrings(
    val title: String = "Models",
    val backCd: String = "Back",
    val overflowCd: String = "More options",
    val activeBadge: String = "ACTIVE",
    val hfSection: String = "HUGGINGFACE",
    val hfOptional: String = "optional",
    val hfPlaceholder: String = "Tap to paste hf_… token",
    val hfPaste: String = "+ Paste",
    val customUrlSection: String = "CUSTOM MODEL URL",
    val customUrlPlaceholder: String = "https://huggingface.co/…/model",
    val customUrlGet: String = "Get",
    val customDownloadLabel: String = "Downloading custom model…",
    val formatHint: String = ".litertlm · .task · .gguf (experimental)",
    val presetsSection: String = "AVAILABLE PRESETS",
    val downloadedSection: String = "DOWNLOADED MODELS",
    val presetGet: String = "Get",
    val presetActivate: String = "Activate",
    val presetDelete: String = "Delete",
    val presetCancelCd: String = "Cancel download",
    val rowMenuCd: String = "Model actions",
    val emptyTitle: String = "No models installed",
    val emptySubtitle: String = "Add a model from the presets list or paste a custom URL.",
    val emptyCta: String = "Add model",
    val errorTitle: String = "Couldn't load models",
    val errorRetry: String = "Retry",
    val discoverCd: String = "Discover models",
    val visionLabel: String = "Image support",
    val visionDescription: String = "Let this model read attached photos",
    val audioLabel: String = "Audio support",
    val audioDescription: String = "Record or pick a clip to transcribe",
    val performance: PerformanceStrings = PerformanceStrings(),
)
