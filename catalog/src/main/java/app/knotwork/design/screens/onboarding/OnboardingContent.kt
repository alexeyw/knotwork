@file:Suppress("MatchingDeclarationName", "TooManyFunctions") // Hosts OnboardingContent and its step composables.

package app.knotwork.design.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/** Height of the segmented progress bar shown above the bottom CTA. */
private val ProgressSegmentHeight = 4.dp

/** Width of the brand logo glyph rendered in the topbar. */
private val LogoIconSize = 24.dp

/** Diameter of the amber bullet rendered before each step-1 feature tile label. */
private val FeatureBulletSize = 8.dp

/** Diameter of the radio circle shown on each model row. */
private val RadioOuterSize = 22.dp

/** Diameter of the filled inner dot rendered when a model row is selected. */
private val RadioInnerSize = 10.dp

/** Border width of the unselected radio circle / pills / pipeline-node chip. */
private val OutlineBorderWidth = 1.dp

/** Edge of the scenario illustration tile rendered on each value card. */
private val ScenarioTileSize = 56.dp

/** Diameter of the arrow icon rendered between pipeline-node chips. */
private val ArrowIconSize = 20.dp

/** Height of the inline pipeline-node chip rendered in the ready recap. */
private val PipelineChipHeight = 28.dp

/** Multiplier turning a normalized download progress (`0f..1f`) into a percentage Int. */
private const val PERCENT_SCALE: Float = 100f

/**
 * Maximum effective `fontScale` honoured by the onboarding headlines.
 * The headline visual is layout-critical, so above the system "Largest" preset
 * (2.0×) the type is clamped to 1.6× to keep the pager from clipping its CTA /
 * progress segments off the bottom edge.
 */
private const val HEADLINE_FONT_SCALE_CLAMP: Float = 1.6f

/**
 * Threshold above which the reduced-motion fallback collapses the download bar
 * to a static full-width fill instead of running the M3 `LinearProgressIndicator`
 * stripe animation.
 */
private const val PROGRESS_FULL_BAR_THRESHOLD: Float = 0.99f

/**
 * Stateless Knotwork onboarding surface — renders one of four steps from
 * [OnboardingViewState.step]. The host (`:app/OnboardingScreen`) owns navigation;
 * the catalog stays snapshot-deterministic by deriving the visible step from
 * [state] alone.
 *
 * Layout:
 *  - Top bar: brand glyph + product title left, Skip link right.
 *  - Body: mono "0N · {label}" step indicator + headline + body + per-step
 *    content (welcome tiles / scenario cards / motivated download / ready recap).
 *  - Footer: 4 horizontal progress segments + a single full-width CTA whose
 *    label varies per step.
 *
 * @param state immutable view-state snapshot.
 * @param callbacks bundle of one-shot event handlers; defaults to no-op.
 * @param modifier optional layout modifier applied to the screen root.
 */
@Composable
fun OnboardingContent(
    state: OnboardingViewState,
    modifier: Modifier = Modifier,
    callbacks: OnboardingCallbacks = noopOnboardingCallbacks(),
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(color = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OnboardingTopBar(state = state, callbacks = callbacks)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = KnotworkTheme.spacing.sp4),
            ) {
                when (state.step) {
                    OnboardingStep.Welcome -> WelcomeStep(state = state)
                    OnboardingStep.ChooseScenario -> ChooseScenarioStep(state = state, callbacks = callbacks)
                    OnboardingStep.Download -> DownloadStep(state = state, callbacks = callbacks)
                    OnboardingStep.Ready -> ReadyStep(state = state, callbacks = callbacks)
                }
            }
            OnboardingFooter(state = state, callbacks = callbacks)
        }
    }
}

@Composable
private fun OnboardingTopBar(state: OnboardingViewState, callbacks: OnboardingCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp3,
                bottom = KnotworkTheme.spacing.sp3,
            ),
    ) {
        Icon(
            imageVector = AppIcons.Hub,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(LogoIconSize),
        )
        Text(
            text = stringResource(R.string.knotwork_onboarding_brand_title),
            style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Skip is suppressed on the final step — the user commits via the
        // primary CTA there, not by skipping.
        if (state.step != OnboardingStep.Ready) {
            KnotworkTextButton(
                text = stringResource(R.string.knotwork_onboarding_skip),
                onClick = callbacks.onSkip,
            )
        }
    }
}

@Composable
private fun OnboardingFooter(state: OnboardingViewState, callbacks: OnboardingCallbacks) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = KnotworkTheme.spacing.sp4,
                vertical = KnotworkTheme.spacing.sp4,
            ),
    ) {
        ProgressSegments(currentStepIndex = state.step.pageIndex)
        val ctaLabel = when (state.step) {
            OnboardingStep.Welcome -> stringResource(R.string.knotwork_onboarding_continue)
            OnboardingStep.ChooseScenario -> scenarioCtaLabel(state)
            OnboardingStep.Download -> liteRtCtaLabel(state)
            OnboardingStep.Ready -> readyCtaLabel(state)
        }
        val leadingIcon = if (state.step == OnboardingStep.Ready) AppIcons.ArrowR else null
        val ctaClick: () -> Unit = when {
            state.isReadyWarmUpRetryable -> callbacks.onRetryWarmUp
            state.isFinalStep -> callbacks.onFinish
            state.step == OnboardingStep.ChooseScenario -> callbacks.onSetUpScenario
            state.step == OnboardingStep.Download && state.installedModelId == null ->
                callbacks.onStartDownload
            else -> callbacks.onNext
        }
        KnotworkPrimaryButton(
            text = ctaLabel,
            onClick = ctaClick,
            enabled = state.isPrimaryCtaEnabled,
            leadingIcon = leadingIcon,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProgressSegments(currentStepIndex: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OnboardingStep.entries.forEach { entry ->
            val filled = entry.pageIndex <= currentStepIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(ProgressSegmentHeight)
                    .clip(KnotworkTheme.shapes.full)
                    .background(
                        color = if (filled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            KnotworkTheme.extended.divider
                        },
                    ),
            )
        }
    }
}

@Composable
private fun StepIndicator(step: OnboardingStep) {
    val padded = "%02d".format(step.pageIndex + 1)
    Text(
        text = "$padded · ${step.indicatorLabel}",
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

@Composable
private fun StepHeadline(text: String) {
    val systemScale = KnotworkTheme.a11y.fontScale()
    val outer = LocalDensity.current
    val clampedScale = if (systemScale > HEADLINE_FONT_SCALE_CLAMP) HEADLINE_FONT_SCALE_CLAMP else systemScale
    CompositionLocalProvider(
        LocalDensity provides Density(density = outer.density, fontScale = clampedScale),
    ) {
        Text(
            text = text,
            style = KnotworkTextStyles.TitleXl,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StepBody(text: String) {
    // Near-full ink, not muted. This paragraph is the one that says cloud
    // providers exist and are optional, and the first external tester skipped it
    // on sight — "Серенькым обычно неважное" · "Я такое не читаю". Muted type is
    // the slot this app uses for machine state, and a reader who learns that
    // slot stops reading anything in it, however important the sentence is.
    Text(
        text = text,
        style = KnotworkTextStyles.BodyBase,
        color = KnotworkTheme.extended.onSurface2,
    )
}

// ----------------------- Step 1 · Welcome ---------------------------------

@Composable
private fun WelcomeStep(state: OnboardingViewState) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier.fillMaxSize(),
    ) {
        StepIndicator(step = state.step)
        StepHeadline(text = stringResource(R.string.knotwork_onboarding_welcome_headline))
        StepBody(text = stringResource(R.string.knotwork_onboarding_welcome_body))
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp2))
        FeatureTile(label = stringResource(R.string.knotwork_onboarding_welcome_tile_litert))
        FeatureTile(label = stringResource(R.string.knotwork_onboarding_welcome_tile_appfunctions))
        FeatureTile(label = stringResource(R.string.knotwork_onboarding_welcome_tile_storage))
    }
}

@Composable
private fun FeatureTile(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Box(
            modifier = Modifier
                .size(FeatureBulletSize)
                .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
        )
        Text(
            text = label,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ----------------------- Step 2 · Choose a scenario -----------------------

@Composable
private fun ChooseScenarioStep(state: OnboardingViewState, callbacks: OnboardingCallbacks) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StepIndicator(step = state.step)
        StepHeadline(text = stringResource(R.string.knotwork_onboarding_scenario_headline))
        StepBody(text = stringResource(R.string.knotwork_onboarding_scenario_body))
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp2))
        // While a set-up is materialising, the gallery stops taking input: a
        // card tap would otherwise cancel the in-flight job and a second CTA
        // tap could persist a duplicate pipeline.
        val interactive = !state.isSettingUpScenario
        OnboardingScenario.entries.forEach { scenario ->
            ScenarioCard(
                scenario = scenario,
                selected = scenario == state.selectedScenario,
                enabled = interactive,
                onClick = { callbacks.onScenarioPick(scenario) },
            )
        }
        StartFromScratchCard(enabled = interactive, onClick = callbacks.onStartFromScratch)
    }
}

@Composable
private fun ScenarioCard(scenario: OnboardingScenario, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        KnotworkTheme.extended.surface1
    }
    // Featured (Virtual Companion) keeps a 1 dp accent border even when
    // unselected; selection uses the same accent so the two states rhyme.
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        scenario.featured -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = containerColor)
            .border(width = OutlineBorderWidth, color = borderColor, shape = KnotworkTheme.shapes.md)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        if (scenario.featured) {
            FeaturedBadge()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
            Icon(
                painter = painterResource(scenarioTileRes(scenario)),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(ScenarioTileSize),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = scenarioTitle(scenario),
                    style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = scenarioValueLine(scenario),
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            RadioCircle(selected = selected)
        }
        ScenarioMetaRow(scenario = scenario)
    }
}

@Composable
private fun ScenarioMetaRow(scenario: OnboardingScenario) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        MetaChip(
            text = stringResource(R.string.knotwork_onboarding_scenario_meta_model, scenario.requiredModel.sizeLabel),
        )
        scenarioMetaExtras(scenario).forEach { chip -> MetaChip(text = chip.text, sensitive = chip.sensitive) }
    }
}

@Composable
private fun MetaChip(text: String, sensitive: Boolean = false) {
    val outline = if (sensitive) MaterialTheme.colorScheme.error else KnotworkTheme.extended.divider
    val textColor = if (sensitive) MaterialTheme.colorScheme.error else KnotworkTheme.extended.onSurfaceMuted
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(KnotworkTheme.shapes.sm)
            .border(width = OutlineBorderWidth, color = outline, shape = KnotworkTheme.shapes.sm)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(text = text, style = KnotworkTextStyles.MonoSm, color = textColor)
    }
}

@Composable
private fun FeaturedBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(KnotworkTheme.shapes.sm)
            .background(color = MaterialTheme.colorScheme.primary)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = stringResource(R.string.knotwork_onboarding_scenario_featured_badge),
            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun StartFromScratchCard(enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .border(width = OutlineBorderWidth, color = KnotworkTheme.extended.divider, shape = KnotworkTheme.shapes.md)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Icon(
            imageVector = AppIcons.Flow,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(ArrowIconSize),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.knotwork_onboarding_scenario_scratch_title),
                style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.knotwork_onboarding_scenario_scratch_body),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

// ----------------------- Step 3 · Motivated download ----------------------

@Composable
private fun DownloadStep(state: OnboardingViewState, callbacks: OnboardingCallbacks) {
    val scenario = state.selectedScenario
    // The scenario's model is shown first ("needs this"), but the headline / body
    // follow the *currently selected* model so they stay consistent with the CTA
    // and progress when the user overrides via "Or choose another".
    val requiredModel = scenario?.requiredModel ?: state.liteRtModel
    val selectedModel = state.liteRtModel
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StepIndicator(step = state.step)
        StepHeadline(text = downloadHeadline(state = state, scenario = scenario, selectedModel = selectedModel))
        StepBody(text = downloadBody(state = state, selectedModel = selectedModel))
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp2))

        // Pre-selected "needs this" model first, then the alternatives.
        LiteRtModelRow(
            model = requiredModel,
            selected = requiredModel == state.liteRtModel,
            installed = state.installedModelId == requiredModel.id,
            downloadProgress = state.downloadProgress.takeIf { requiredModel == state.liteRtModel },
            pill = ModelRowPill.NeedsThis,
            onClick = { callbacks.onLiteRtModelPick(requiredModel) },
        )

        Text(
            text = stringResource(R.string.knotwork_onboarding_download_choose_another),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        OnboardingLiteRtModel.entries.filter { it != requiredModel }.forEach { model ->
            val isSelected = model == state.liteRtModel
            LiteRtModelRow(
                model = model,
                selected = isSelected,
                installed = state.installedModelId == model.id,
                downloadProgress = state.downloadProgress.takeIf { isSelected },
                pill = if (state.installedModelId == model.id) ModelRowPill.Installed else ModelRowPill.None,
                onClick = { callbacks.onLiteRtModelPick(model) },
            )
            if (isSelected && model == OnboardingLiteRtModel.CustomUrl) {
                CustomUrlField(value = state.customDownloadUrl, onValueChange = callbacks.onCustomDownloadUrlChanged)
            }
        }
        state.downloadError?.let { ErrorBanner(message = it) }
    }
}

/** Trailing pill rendered on a model row. */
private enum class ModelRowPill { None, NeedsThis, Installed }

@Composable
private fun LiteRtModelRow(
    model: OnboardingLiteRtModel,
    selected: Boolean,
    installed: Boolean,
    downloadProgress: Float?,
    pill: ModelRowPill,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        KnotworkTheme.extended.surface1
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = containerColor)
            .border(width = OutlineBorderWidth, color = borderColor, shape = KnotworkTheme.shapes.md)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        ) {
            RadioCircle(selected = selected)
            Column(
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = model.displayName,
                    style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = model.sizeLabel,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            when {
                installed -> InstalledPill()
                pill == ModelRowPill.NeedsThis -> NeedsThisPill()
                else -> Unit
            }
        }
        if (downloadProgress != null) {
            DownloadProgressIndicator(progress = downloadProgress)
        }
    }
}

@Composable
private fun DownloadProgressIndicator(progress: Float) {
    val clamped = progress.coerceIn(minimumValue = 0f, maximumValue = 1f)
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp2),
    ) {
        if (reducedMotion && clamped >= PROGRESS_FULL_BAR_THRESHOLD) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ProgressSegmentHeight)
                    .clip(KnotworkTheme.shapes.full)
                    .background(color = MaterialTheme.colorScheme.primary),
            )
        } else {
            LinearProgressIndicator(
                progress = { clamped },
                color = MaterialTheme.colorScheme.primary,
                trackColor = KnotworkTheme.extended.divider,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = stringResource(
                R.string.knotwork_onboarding_models_progress,
                (clamped * PERCENT_SCALE).toInt(),
            ),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun CustomUrlField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(text = stringResource(R.string.knotwork_onboarding_models_custom_url_label)) },
        placeholder = { Text(text = stringResource(R.string.knotwork_onboarding_models_custom_url_placeholder)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp2),
    )
}

@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Text(
            text = message,
            style = KnotworkTextStyles.BodyBase,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun RadioCircle(selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(RadioOuterSize)
            .border(
                width = OutlineBorderWidth + 0.5.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(RadioInnerSize)
                    .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
            )
        }
    }
}

@Composable
private fun InstalledPill() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(KnotworkTheme.shapes.sm)
            .border(
                width = OutlineBorderWidth,
                color = MaterialTheme.colorScheme.primary,
                shape = KnotworkTheme.shapes.sm,
            )
            .background(color = MaterialTheme.colorScheme.primary)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = stringResource(R.string.knotwork_onboarding_models_installed),
            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun NeedsThisPill() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(KnotworkTheme.shapes.sm)
            .border(
                width = OutlineBorderWidth,
                color = MaterialTheme.colorScheme.primary,
                shape = KnotworkTheme.shapes.sm,
            )
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = stringResource(R.string.knotwork_onboarding_download_needs_this),
            style = KnotworkTextStyles.LabelSm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Download-step headline, framed around the **selected** model so it stays
 * consistent with the CTA and progress when the user overrides the scenario's
 * pre-selected model. Uses the "{scenario} needs {model}" copy only while the
 * selection is still the scenario's own model.
 */
@Composable
private fun downloadHeadline(
    state: OnboardingViewState,
    scenario: OnboardingScenario?,
    selectedModel: OnboardingLiteRtModel,
): String = when {
    state.installedModelId == selectedModel.id ->
        stringResource(R.string.knotwork_onboarding_download_installed_headline, selectedModel.displayName)
    selectedModel == OnboardingLiteRtModel.CustomUrl ->
        stringResource(R.string.knotwork_onboarding_download_custom_headline)
    scenario != null && selectedModel == scenario.requiredModel ->
        stringResource(
            R.string.knotwork_onboarding_download_headline,
            scenarioTitle(scenario),
            selectedModel.displayName,
        )
    else ->
        stringResource(R.string.knotwork_onboarding_download_generic_headline, selectedModel.displayName)
}

/** Download-step body, framed around the selected model (see [downloadHeadline]). */
@Composable
private fun downloadBody(state: OnboardingViewState, selectedModel: OnboardingLiteRtModel): String = when {
    state.installedModelId == selectedModel.id ->
        stringResource(R.string.knotwork_onboarding_download_installed_body)
    selectedModel == OnboardingLiteRtModel.CustomUrl ->
        stringResource(R.string.knotwork_onboarding_download_custom_body)
    else ->
        stringResource(R.string.knotwork_onboarding_download_body, selectedModel.sizeLabel)
}

@Composable
private fun liteRtCtaLabel(state: OnboardingViewState): String = when {
    state.downloadProgress != null -> stringResource(R.string.knotwork_onboarding_models_downloading_cta)
    state.installedModelId != null -> stringResource(R.string.knotwork_onboarding_models_continue_cta)
    else -> stringResource(R.string.knotwork_onboarding_models_download_cta, state.liteRtModel.displayName)
}

@Composable
private fun scenarioCtaLabel(state: OnboardingViewState): String = when {
    state.isSettingUpScenario -> stringResource(R.string.knotwork_onboarding_scenario_cta_setting_up)
    state.selectedScenario != null ->
        stringResource(R.string.knotwork_onboarding_scenario_cta_setup, scenarioTitle(state.selectedScenario))
    else -> stringResource(R.string.knotwork_onboarding_scenario_cta_empty)
}

@Composable
private fun readyCtaLabel(state: OnboardingViewState): String = when {
    state.isReadyWarmUpRetryable -> stringResource(R.string.knotwork_onboarding_ready_retry_cta)
    state.isCheckingAcceleration -> stringResource(R.string.knotwork_onboarding_ready_checking_acceleration_cta)
    !state.isModelWarmed -> stringResource(R.string.knotwork_onboarding_ready_preparing_cta)
    state.selectedScenario != null ->
        stringResource(R.string.knotwork_onboarding_ready_open_cta, scenarioTitle(state.selectedScenario))
    else -> stringResource(R.string.knotwork_onboarding_ready_open_generic_cta)
}

// ----------------------- Step 4 · Ready -----------------------------------

@Composable
private fun ReadyStep(state: OnboardingViewState, callbacks: OnboardingCallbacks) {
    val scenario = state.selectedScenario
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        StepIndicator(step = state.step)
        StepHeadline(
            text =
            scenario?.let {
                stringResource(R.string.knotwork_onboarding_ready_scenario_headline, scenarioTitle(it))
            }
                ?: stringResource(R.string.knotwork_onboarding_ready_generic_headline),
        )
        StepBody(text = stringResource(R.string.knotwork_onboarding_ready_scenario_body))
        Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp2))
        ActiveModelRow(state = state)
        // A warm-up failure surfaces here (not just on the download step) so the
        // user sees why the CTA turned into "Retry" instead of a silent dead-end.
        state.downloadError?.let { ErrorBanner(message = it) }
        state.scenarioPreview?.let { PipelinePreviewCard(preview = it) }
        if (scenario?.bindsShareSurface == true) {
            InfoRow(
                label = stringResource(R.string.knotwork_onboarding_ready_surface_label),
                value = stringResource(R.string.knotwork_onboarding_ready_surface_value),
            )
            InfoRow(
                label = stringResource(R.string.knotwork_onboarding_ready_safety_label),
                value = stringResource(R.string.knotwork_onboarding_ready_safety_value),
            )
        }
        DocumentationRow(onOpen = callbacks.onOpenDocumentation)
    }
}

/**
 * The one line of onboarding that says the documentation exists.
 *
 * A row added to the existing step rather than a step of its own: onboarding is
 * measured on how fast it reaches first value, and a page about reading
 * material would be a page nobody finishes.
 */
@Composable
private fun DocumentationRow(onOpen: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Text(
            text = stringResource(R.string.knotwork_onboarding_ready_docs_label),
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_onboarding_ready_docs_cta),
            onClick = onOpen,
            size = KnotworkButtonSize.Sm,
        )
    }
}

@Composable
private fun ActiveModelRow(state: OnboardingViewState) {
    val installedDisplayName = state.installedModelId?.let { id ->
        OnboardingLiteRtModel.entries.firstOrNull { it.id == id }?.displayName
    }
    val value = if (installedDisplayName != null && state.isModelWarmed) {
        stringResource(R.string.knotwork_onboarding_ready_active_model_value, installedDisplayName)
    } else {
        stringResource(R.string.knotwork_onboarding_ready_active_model_pending)
    }
    InfoRow(
        label = stringResource(R.string.knotwork_onboarding_ready_active_model_label),
        value = value,
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        Text(text = label, style = KnotworkTextStyles.MonoSm, color = KnotworkTheme.extended.onSurfaceMuted)
        Text(
            text = value,
            style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PipelinePreviewCard(preview: OnboardingDefaultPipelinePreview) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            preview.nodes.forEachIndexed { index, name ->
                PipelineNodeChip(name = name, accent = name == preview.accentNodeName)
                if (index < preview.nodes.lastIndex) {
                    Icon(
                        imageVector = AppIcons.ArrowR,
                        contentDescription = null,
                        tint = KnotworkTheme.extended.onSurfaceMuted,
                        modifier = Modifier
                            .size(ArrowIconSize)
                            .padding(top = KnotworkTheme.spacing.sp1),
                    )
                }
            }
        }
        Text(
            text = pluralStringResource(
                R.plurals.knotwork_onboarding_ready_preview_caption,
                preview.nodeCount,
                preview.nodeCount,
                preview.edgeCount,
            ),
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

@Composable
private fun PipelineNodeChip(name: String, accent: Boolean) {
    val border = if (accent) KnotworkPalette.NodeIfCondition else MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(PipelineChipHeight)
            .clip(KnotworkTheme.shapes.sm)
            .border(width = OutlineBorderWidth, color = border, shape = KnotworkTheme.shapes.sm)
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = name,
            style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.SemiBold),
            // The routing node's green outline is the IF_CONDITION node hue, 2.9:1 on
            // the light surface; its name takes the text-safe tone of the same hue.
            color = if (accent) KnotworkTheme.extended.signalSuccessText else border,
        )
    }
}

// ----------------------- Scenario copy mapping ----------------------------

private fun scenarioTileRes(scenario: OnboardingScenario): Int = when (scenario) {
    OnboardingScenario.StyledTranslation -> R.drawable.scenario_translate
    OnboardingScenario.ShareHandler -> R.drawable.scenario_capture
    OnboardingScenario.VirtualCompanion -> R.drawable.scenario_companion
}

@Composable
private fun scenarioTitle(scenario: OnboardingScenario): String = stringResource(
    when (scenario) {
        OnboardingScenario.StyledTranslation -> R.string.knotwork_onboarding_scenario_translate_title
        OnboardingScenario.ShareHandler -> R.string.knotwork_onboarding_scenario_capture_title
        OnboardingScenario.VirtualCompanion -> R.string.knotwork_onboarding_scenario_companion_title
    },
)

@Composable
private fun scenarioValueLine(scenario: OnboardingScenario): String = stringResource(
    when (scenario) {
        OnboardingScenario.StyledTranslation -> R.string.knotwork_onboarding_scenario_translate_value
        OnboardingScenario.ShareHandler -> R.string.knotwork_onboarding_scenario_capture_value
        OnboardingScenario.VirtualCompanion -> R.string.knotwork_onboarding_scenario_companion_value
    },
)

/** A meta chip label plus whether it carries the sensitive (HITL) tone. */
private data class MetaChipLabel(val sensitive: Boolean, val text: String)

@Composable
private fun scenarioMetaExtras(scenario: OnboardingScenario): List<MetaChipLabel> = when (scenario) {
    OnboardingScenario.StyledTranslation -> listOf(
        MetaChipLabel(false, stringResource(R.string.knotwork_onboarding_scenario_meta_ondevice)),
    )
    OnboardingScenario.ShareHandler -> listOf(
        MetaChipLabel(false, stringResource(R.string.knotwork_onboarding_scenario_meta_share)),
        MetaChipLabel(true, stringResource(R.string.knotwork_onboarding_scenario_meta_hitl)),
    )
    OnboardingScenario.VirtualCompanion -> listOf(
        MetaChipLabel(false, stringResource(R.string.knotwork_onboarding_scenario_meta_private)),
    )
}
