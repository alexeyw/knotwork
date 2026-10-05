package app.knotwork.design.components.console

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Width of a field's label column. */
private val FieldLabelWidth = 56.dp

/** Size of the strip's chevron and the action glyphs. */
private val StripGlyph = 18.dp

/** Size of the small glyphs inside lines (busy, warn, now tag). */
private val LineGlyph = 13.dp

/** Size of the promise glyph. */
private val PromiseGlyph = 16.dp

/** Alpha of a disabled action's label and glyph. The reason under it keeps its colour. */
private const val DISABLED_ALPHA = 0.42f

/** Alpha of an action's second line, and of the busy line. */
private const val LINE_ALPHA = 0.8f

/** Alpha of the strip's raised surface over the console background. */
private const val RAISE_ALPHA = 0.06f

/** Share of the screen's height the expanded header may take before it scrolls, so the tabs stay in view. */
private const val EXPANDED_MAX_SCREEN_SHARE = 0.55f

/** Rotation of the chevron when the header is open. */
private const val CHEVRON_OPEN_DEGREES = 180f

/**
 * The run strip: the run the console belongs to, between the console's handle
 * and its tabs.
 *
 * Collapsed, one line — `seed · backend · model · t` — that wraps at any scale
 * and is never ellipsised. Expanded, every recorded field as label and value, the
 * one promise, and three actions: verify, run again with this seed, export the
 * trace. A disabled action keeps its reason as its second line, next to the field
 * that causes it, and stays focusable; only its label and glyph dim.
 *
 * The console is always dark, so the strip draws on the console's colours in both
 * themes. The expanded body scrolls inside a bounded height so the tabs below it
 * stay on screen.
 *
 * @param header What to show.
 * @param expanded Whether the header is open.
 * @param onToggle Opens or closes it.
 * @param onCopySeed Copies the seed.
 * @param onCopyModelSha Copies a model file's full SHA-256.
 * @param onCopyDigest Copies the full run digest.
 * @param onVerify Starts the check.
 * @param onRunAgain Starts the run again with its seed.
 * @param onExport Opens the trace export.
 * @param onOpenSettings Opens where a mismatch is fixed.
 * @param modifier Optional layout modifier.
 */
@Composable
fun RunHeaderStrip(
    header: RunHeaderUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopySeed: () -> Unit,
    onCopyModelSha: (String) -> Unit,
    onCopyDigest: () -> Unit,
    onVerify: () -> Unit,
    onRunAgain: () -> Unit,
    onExport: () -> Unit,
    onOpenSettings: (RunSettingsTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fg = KnotworkTheme.extended.consoleFg
    val motion = KnotworkTheme.motion
    val animated = if (KnotworkTheme.a11y.reducedMotion()) {
        Modifier
    } else {
        Modifier.animateContentSize(animationSpec = tween(durationMillis = motion.dur3, easing = motion.easeEmph))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(fg.copy(alpha = RAISE_ALPHA))
            .then(animated),
    ) {
        Hairline()
        if (expanded) {
            ExpandedHeader(
                header = header,
                onToggle = onToggle,
                onCopySeed = onCopySeed,
                onCopyModelSha = onCopyModelSha,
                onCopyDigest = onCopyDigest,
                onVerify = onVerify,
                onRunAgain = onRunAgain,
                onExport = onExport,
                onOpenSettings = onOpenSettings,
            )
        } else {
            RunLine(line = header.line, onToggle = onToggle)
        }
        Hairline()
    }
}

/** The collapsed line with its chevron; the whole row toggles. */
@Composable
private fun RunLine(line: RunLineUi, onToggle: () -> Unit) {
    val fg = KnotworkTheme.extended.consoleFg
    val text = runLineText(line)
    val spoken = when (line) {
        is RunLineUi.Seeded -> runLineText(line.copy(seed = line.seedDigits))
        else -> text
    }
    val description = stringResource(R.string.knotwork_run_line_a11y, spoken)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunTouchTarget)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    onToggle()
                    true
                }
            }
            .clickable(onClick = onToggle)
            .padding(start = KnotworkTheme.spacing.sp3, end = KnotworkTheme.spacing.sp1),
    ) {
        if (line == RunLineUi.PreVersion) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = fg.copy(alpha = RUN_DIM_ALPHA),
                modifier = Modifier.size(LineGlyph),
            )
        }
        Text(
            text = text,
            style = KnotworkTextStyles.MonoSm,
            color = if (line == RunLineUi.PreVersion) fg.copy(alpha = RUN_DIM_ALPHA) else fg,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = KnotworkTheme.spacing.sp1),
        )
        Chevron(open = false)
    }
}

/** The collapsed line's text. */
@Composable
private fun runLineText(line: RunLineUi): String = when (line) {
    is RunLineUi.Seeded -> {
        val base = if (line.backend != null && line.model != null) {
            stringResource(R.string.knotwork_run_line, line.seed, line.backend, line.model, line.temperature)
        } else {
            stringResource(R.string.knotwork_run_line_seed_only, line.seed, line.temperature)
        }
        if (line.withCloud) stringResource(R.string.knotwork_run_line_cloud_suffix, base) else base
    }
    RunLineUi.CloudOnly -> stringResource(R.string.knotwork_run_line_cloud_only)
    RunLineUi.PreVersion -> stringResource(R.string.knotwork_run_line_pre_version)
}

/** The open header: title row, fields, promise, actions. */
@Composable
private fun ExpandedHeader(
    header: RunHeaderUi,
    onToggle: () -> Unit,
    onCopySeed: () -> Unit,
    onCopyModelSha: (String) -> Unit,
    onCopyDigest: () -> Unit,
    onVerify: () -> Unit,
    onRunAgain: () -> Unit,
    onExport: () -> Unit,
    onOpenSettings: (RunSettingsTarget) -> Unit,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxHeight = windowHeight * EXPANDED_MAX_SCREEN_SHARE
    Column(modifier = Modifier.fillMaxWidth()) {
        TitleRow(onToggle = onToggle)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            when (val detail = header.detail) {
                RunDetailUi.PreVersion -> Text(
                    text = stringResource(R.string.knotwork_run_repro_pre_version),
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.consoleFg,
                    modifier = Modifier.padding(
                        start = KnotworkTheme.spacing.sp3,
                        end = KnotworkTheme.spacing.sp3,
                        bottom = KnotworkTheme.spacing.sp3,
                    ),
                )
                is RunDetailUi.Recorded -> RecordedFields(
                    detail = detail,
                    onCopySeed = onCopySeed,
                    onCopyModelSha = onCopyModelSha,
                    onCopyDigest = onCopyDigest,
                )
            }
            Actions(
                actions = header.actions,
                onVerify = onVerify,
                onRunAgain = onRunAgain,
                onExport = onExport,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

/** "THIS RUN" and the chevron that closes the header. */
@Composable
private fun TitleRow(onToggle: () -> Unit) {
    val description = stringResource(R.string.knotwork_run_line_open_a11y)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RunTouchTarget)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                heading()
                onClick {
                    onToggle()
                    true
                }
            }
            .clickable(onClick = onToggle)
            .padding(start = KnotworkTheme.spacing.sp3, end = KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = stringResource(R.string.knotwork_run_title).uppercase(),
            style = KnotworkTextStyles.LabelSm,
            fontWeight = FontWeight.Bold,
            color = KnotworkTheme.extended.consoleTag,
            modifier = Modifier.weight(1f),
        )
        Chevron(open = true)
    }
}

/** The chevron at the end of the line: down when closed, up when open. */
@Composable
private fun Chevron(open: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(RunTouchTarget)) {
        Icon(
            imageVector = AppIcons.ArrowDown,
            contentDescription = null,
            tint = KnotworkTheme.extended.consoleFg,
            modifier = Modifier
                .size(StripGlyph)
                .rotate(if (open) CHEVRON_OPEN_DEGREES else 0f),
        )
    }
}

/** Every field of a run with a header, then the promise. */
@Composable
private fun RecordedFields(
    detail: RunDetailUi.Recorded,
    onCopySeed: () -> Unit,
    onCopyModelSha: (String) -> Unit,
    onCopyDigest: () -> Unit,
) {
    val fg = KnotworkTheme.extended.consoleFg
    val dim = fg.copy(alpha = RUN_DIM_ALPHA)
    Field(
        label = stringResource(R.string.knotwork_run_field_seed),
        copyDescription = stringResource(R.string.knotwork_run_copy_seed_cd),
        onCopy = onCopySeed,
    ) { FieldText(detail.seed) }
    Field(label = stringResource(R.string.knotwork_run_field_sampler)) {
        FieldText(stringResource(R.string.knotwork_run_sampler_value, detail.temperature, detail.topK, detail.topP))
    }
    if (detail.models.isEmpty() && detail.cloud == RunCloudUse.ALL) {
        Field(label = stringResource(R.string.knotwork_run_field_model)) {
            FieldText(stringResource(R.string.knotwork_run_model_none), color = dim)
        }
    }
    detail.models.forEach { model -> ModelFields(model = model, onCopyModelSha = onCopyModelSha) }
    if (detail.cloud != RunCloudUse.NONE) {
        Field(label = stringResource(R.string.knotwork_run_field_cloud)) {
            FieldText(
                stringResource(
                    if (detail.cloud ==
                        RunCloudUse.ALL
                    ) {
                        R.string.knotwork_run_cloud_all
                    } else {
                        R.string.knotwork_run_cloud_some
                    },
                ),
            )
        }
    }
    Field(label = stringResource(R.string.knotwork_run_field_app)) {
        FieldText(stringResource(R.string.knotwork_run_app_value, detail.appVersion, detail.runtimeVersion))
    }
    Field(label = stringResource(R.string.knotwork_run_field_device)) { FieldText(detail.device) }
    val digest = detail.digest
    Field(
        label = stringResource(R.string.knotwork_run_field_digest),
        copyDescription = digest?.let { stringResource(R.string.knotwork_run_copy_digest_cd) },
        onCopy = onCopyDigest.takeIf { digest != null },
    ) {
        if (digest != null) {
            FullHash(sha256 = digest, what = stringResource(R.string.knotwork_run_field_digest_a11y), color = fg)
        } else {
            FieldText(stringResource(R.string.knotwork_run_digest_pending), color = dim)
        }
    }
    Promise(promise = detail.promise)
}

/** A model's name, backend and window, then its file's SHA-256. */
@Composable
private fun ModelFields(model: RunModelUi, onCopyModelSha: (String) -> Unit) {
    val fg = KnotworkTheme.extended.consoleFg
    Field(label = stringResource(R.string.knotwork_run_field_model)) {
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            FieldText(stringResource(R.string.knotwork_run_model_value, model.name, model.backend, model.window))
            model.nowTag?.let { NowTag(it) }
        }
    }
    val sha = model.sha256
    Field(
        label = stringResource(R.string.knotwork_run_field_sha),
        copyDescription = sha?.let { stringResource(R.string.knotwork_run_copy_sha_cd) },
        onCopy = sha?.let { { onCopyModelSha(it) } },
    ) {
        if (sha != null) {
            FullHash(sha256 = sha, what = stringResource(R.string.knotwork_run_hash_model_a11y), color = fg)
        } else {
            FieldText(stringResource(R.string.knotwork_run_sha_not_recorded), color = fg.copy(alpha = RUN_DIM_ALPHA))
        }
    }
}

/**
 * One field: label and value side by side, the label stacked above at large font
 * scales; an optional copy button at the end.
 */
@Composable
private fun Field(
    label: String,
    copyDescription: String? = null,
    onCopy: (() -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    val stacked = KnotworkTheme.a11y.fontScale() >= LARGE_FONT_SCALE
    val labelText = @Composable {
        Text(
            text = label,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.consoleFg.copy(alpha = RUN_DIM_ALPHA),
            modifier = if (stacked) Modifier else Modifier.width(FieldLabelWidth),
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (onCopy != null) RunTouchTarget else 0.dp)
            .padding(start = KnotworkTheme.spacing.sp3, end = if (onCopy != null) 0.dp else KnotworkTheme.spacing.sp3),
    ) {
        if (stacked) {
            Column(modifier = Modifier.weight(1f).padding(vertical = KnotworkTheme.spacing.sp1).semantics(true) {}) {
                labelText()
                value()
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier.weight(1f).padding(vertical = 2.dp).semantics(true) {},
            ) {
                labelText()
                Box(modifier = Modifier.weight(1f)) { value() }
            }
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = AppIcons.Copy,
                    contentDescription = copyDescription,
                    tint = KnotworkTheme.extended.consoleFg.copy(alpha = RUN_DIM_ALPHA),
                    modifier = Modifier.size(PromiseGlyph),
                )
            }
        }
    }
}

/** A field's value text. */
@Composable
private fun FieldText(text: String, color: Color = KnotworkTheme.extended.consoleFg) {
    Text(text = text, style = KnotworkTextStyles.MonoSm, color = color)
}

/** The warn tag on a field that no longer matches this device: `now CPU`. */
@Composable
private fun NowTag(value: String) {
    val warn = KnotworkTheme.extended.signalWarn
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .border(width = 1.dp, color = warn, shape = KnotworkTheme.shapes.sm)
            .padding(horizontal = KnotworkTheme.spacing.sp1),
    ) {
        Icon(imageVector = AppIcons.Warn, contentDescription = null, tint = warn, modifier = Modifier.size(LineGlyph))
        Text(
            text = stringResource(R.string.knotwork_run_now_tag, value),
            style = KnotworkTextStyles.MonoSm,
            fontWeight = FontWeight.Bold,
            color = warn,
        )
    }
}

/** The promise: a check when promised, an info glyph when not — never warn; nothing went wrong. */
@Composable
private fun Promise(promise: RunPromiseUi) {
    val fg = KnotworkTheme.extended.consoleFg
    val promised = promise in setOf(RunPromiseUi.CPU, RunPromiseUi.GPU, RunPromiseUi.MIXED_CPU, RunPromiseUi.MIXED_GPU)
    val head = stringResource(
        when (promise) {
            RunPromiseUi.CPU, RunPromiseUi.GPU -> R.string.knotwork_run_repro_head
            RunPromiseUi.MIXED_CPU, RunPromiseUi.MIXED_GPU -> R.string.knotwork_run_repro_head_mixed
            RunPromiseUi.NPU -> R.string.knotwork_run_repro_npu_head
            RunPromiseUi.CLOUD_ONLY -> R.string.knotwork_run_repro_cloud_head
            RunPromiseUi.IMAGE -> R.string.knotwork_run_repro_image_head
        },
    )
    val body = when (promise) {
        RunPromiseUi.CPU -> listOf(R.string.knotwork_run_repro_cpu, R.string.knotwork_run_repro_limit)
        RunPromiseUi.GPU -> listOf(R.string.knotwork_run_repro_gpu, R.string.knotwork_run_repro_limit)
        RunPromiseUi.MIXED_CPU -> listOf(
            R.string.knotwork_run_repro_cpu,
            R.string.knotwork_run_repro_mixed_cloud,
            R.string.knotwork_run_repro_limit,
        )
        RunPromiseUi.MIXED_GPU -> listOf(
            R.string.knotwork_run_repro_gpu,
            R.string.knotwork_run_repro_mixed_cloud,
            R.string.knotwork_run_repro_limit,
        )
        RunPromiseUi.NPU -> listOf(R.string.knotwork_run_repro_npu_body)
        RunPromiseUi.CLOUD_ONLY -> listOf(R.string.knotwork_run_repro_cloud_body)
        RunPromiseUi.IMAGE -> listOf(R.string.knotwork_run_repro_image_body)
    }.map { stringResource(it) }
    Hairline()
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = if (promised) AppIcons.Check else AppIcons.Info,
            contentDescription = null,
            tint = if (promised) KnotworkTheme.extended.signalSuccess else fg.copy(alpha = RUN_DIM_ALPHA),
            modifier = Modifier.size(PromiseGlyph),
        )
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            Text(text = head, style = KnotworkTextStyles.MonoSm, fontWeight = FontWeight.Bold, color = fg)
            body.forEachIndexed { index, line ->
                val last = index == body.lastIndex && promised
                Text(
                    text = line,
                    style = KnotworkTextStyles.MonoSm,
                    color = fg.copy(alpha = if (last) RUN_DIM_ALPHA else LINE_ALPHA),
                )
            }
        }
    }
}

/** The three action rows, with the busy line above them while the run goes on. */
@Composable
private fun Actions(
    actions: RunActionsUi,
    onVerify: () -> Unit,
    onRunAgain: () -> Unit,
    onExport: () -> Unit,
    onOpenSettings: (RunSettingsTarget) -> Unit,
) {
    val fg = KnotworkTheme.extended.consoleFg
    Hairline()
    if (actions.busy) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier.padding(
                start = KnotworkTheme.spacing.sp3,
                end = KnotworkTheme.spacing.sp3,
                top = KnotworkTheme.spacing.sp2,
            ),
        ) {
            Icon(
                imageVector = AppIcons.Hourglass,
                contentDescription = null,
                tint = fg.copy(alpha = LINE_ALPHA),
                modifier = Modifier.size(LineGlyph),
            )
            Text(
                text = stringResource(R.string.knotwork_run_action_busy),
                style = KnotworkTextStyles.MonoSm,
                color = fg.copy(alpha = LINE_ALPHA),
            )
        }
    }
    VerifyRow(actions = actions, onVerify = onVerify, onOpenSettings = onOpenSettings)
    Hairline()
    ActionRow(
        icon = AppIcons.Redo,
        label = stringResource(R.string.knotwork_run_action_seed),
        line = if (actions.busy) null else runAgainLine(actions.runAgain),
        enabled = !actions.busy && actions.runAgain == RunAgainActionUi.AVAILABLE,
        onClick = onRunAgain,
    )
    Hairline()
    ActionRow(
        icon = AppIcons.ExportFile,
        label = stringResource(R.string.knotwork_run_action_export),
        line = if (actions.busy) {
            null
        } else {
            stringResource(
                when (actions.export) {
                    RunExportActionUi.FULL -> R.string.knotwork_run_action_export_line
                    RunExportActionUi.RECORDS_ONLY -> R.string.knotwork_run_action_export_line_pre
                },
            )
        },
        enabled = !actions.busy,
        onClick = onExport,
    )
    // The record is what all three read; retention removes it with the run.
    Text(
        text = stringResource(R.string.knotwork_run_kept_note),
        style = KnotworkTextStyles.MonoSm,
        color = fg.copy(alpha = RUN_DIM_ALPHA),
        modifier = Modifier.padding(
            start = KnotworkTheme.spacing.sp3,
            end = KnotworkTheme.spacing.sp3,
            bottom = KnotworkTheme.spacing.sp3,
        ),
    )
}

/** The check's row, with a mismatch's reason in warn and its settings button. */
@Composable
private fun VerifyRow(actions: RunActionsUi, onVerify: () -> Unit, onOpenSettings: (RunSettingsTarget) -> Unit) {
    val verify = actions.verify
    val mismatch = (verify as? RunVerifyActionUi.Mismatch)?.reason
    val target = when (mismatch) {
        is RunMismatchUi.Backend, is RunMismatchUi.BackendAndWindow -> RunSettingsTarget.SETTINGS
        is RunMismatchUi.Window -> RunSettingsTarget.GENERATION
        else -> null
    }
    ActionRow(
        icon = AppIcons.CheckSquare,
        label = stringResource(R.string.knotwork_run_action_verify),
        line = if (actions.busy) null else verifyLine(verify),
        enabled = !actions.busy && (verify is RunVerifyActionUi.Available || verify is RunVerifyActionUi.NpuOnly),
        warn = !actions.busy && mismatch != null,
        onClick = onVerify,
        extra = if (!actions.busy && target != null) {
            {
                KnotworkConsoleTextButton(
                    text = stringResource(
                        if (target == RunSettingsTarget.GENERATION) {
                            R.string.knotwork_run_verify_open_generation_settings
                        } else {
                            R.string.knotwork_run_verify_open_settings
                        },
                    ),
                    onClick = { onOpenSettings(target) },
                )
            }
        } else {
            null
        },
    )
}

/** The check row's second line. */
@Composable
private fun verifyLine(verify: RunVerifyActionUi): String = when (verify) {
    is RunVerifyActionUi.Available -> if (verify.estimate != null) {
        pluralStringResource(R.plurals.knotwork_run_action_verify_line, verify.calls, verify.calls, verify.estimate)
    } else {
        pluralStringResource(R.plurals.knotwork_run_action_verify_line_count, verify.calls, verify.calls)
    }
    is RunVerifyActionUi.NpuOnly ->
        pluralStringResource(R.plurals.knotwork_run_action_verify_line_npu, verify.calls, verify.calls)
    RunVerifyActionUi.NoLocalCalls -> stringResource(R.string.knotwork_run_verify_no_local)
    RunVerifyActionUi.PreVersion -> stringResource(R.string.knotwork_run_verify_pre_version)
    is RunVerifyActionUi.Mismatch -> mismatchText(verify.reason)
}

/**
 * A mismatch's sentence, naming the recorded and the current value and where it
 * is set.
 *
 * @param reason The mismatch.
 * @return The sentence.
 */
@Composable
internal fun mismatchText(reason: RunMismatchUi): String = when (reason) {
    is RunMismatchUi.Backend ->
        stringResource(R.string.knotwork_run_verify_mismatch_backend, reason.recorded, reason.now)
    is RunMismatchUi.Window ->
        stringResource(R.string.knotwork_run_verify_mismatch_window, reason.recorded, reason.now)
    is RunMismatchUi.BackendAndWindow ->
        stringResource(R.string.knotwork_run_verify_mismatch_both, reason.recorded, reason.window, reason.now)
    is RunMismatchUi.ModelMissing -> stringResource(R.string.knotwork_run_verify_model_missing, reason.model)
    is RunMismatchUi.ModelChanged -> stringResource(R.string.knotwork_run_verify_model_changed, reason.model)
    RunMismatchUi.HashPending -> stringResource(R.string.knotwork_run_verify_hash_pending)
}

/** The run-again row's second line. */
@Composable
private fun runAgainLine(action: RunAgainActionUi): String = stringResource(
    when (action) {
        RunAgainActionUi.AVAILABLE -> R.string.knotwork_run_action_seed_line
        RunAgainActionUi.NOT_OFFERED_IMAGE -> R.string.knotwork_run_seed_not_image
        RunAgainActionUi.NOT_OFFERED_BACKGROUND -> R.string.knotwork_run_seed_not_background
        RunAgainActionUi.NOT_OFFERED_PRE_VERSION -> R.string.knotwork_run_seed_not_pre_version
        RunAgainActionUi.NOT_OFFERED_PIPELINE_DELETED -> R.string.knotwork_run_seed_not_pipeline_deleted
    },
)

/**
 * One action: glyph, label, second line. A disabled row stays focusable and says
 * "dimmed"; its reason keeps full contrast.
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    line: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    warn: Boolean = false,
    extra: (@Composable () -> Unit)? = null,
) {
    val fg = KnotworkTheme.extended.consoleFg
    val tag = KnotworkTheme.extended.consoleTag
    val warnColor = KnotworkTheme.extended.signalWarn
    val dimmed = stringResource(R.string.knotwork_run_action_dimmed_a11y, label, line.orEmpty())
    val spoken = if (enabled) listOfNotNull(label, line).joinToString(separator = ". ") else dimmed
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RunTouchTarget)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                    if (enabled) {
                        onClick {
                            onClick()
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tag else fg.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier.size(StripGlyph),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = KnotworkTextStyles.MonoBase,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) fg else fg.copy(alpha = DISABLED_ALPHA),
                )
                if (line != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
                        if (warn) {
                            Icon(
                                imageVector = AppIcons.Warn,
                                contentDescription = null,
                                tint = warnColor,
                                modifier = Modifier.size(LineGlyph).padding(top = 2.dp),
                            )
                        }
                        Text(
                            text = line,
                            style = KnotworkTextStyles.MonoSm,
                            color = if (warn) warnColor else fg.copy(alpha = LINE_ALPHA),
                        )
                    }
                }
            }
        }
        if (extra != null) {
            Box(modifier = Modifier.padding(start = KnotworkTheme.spacing.sp10, bottom = KnotworkTheme.spacing.sp1)) {
                extra()
            }
        }
    }
}

/** A text button drawn on the console's colours. */
@Composable
private fun KnotworkConsoleTextButton(text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .heightIn(min = RunTouchTarget)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = AppIcons.Cog,
            contentDescription = null,
            tint = KnotworkTheme.extended.consoleTag,
            modifier = Modifier.size(PromiseGlyph),
        )
        Text(
            text = text,
            style = KnotworkTextStyles.LabelMd,
            color = KnotworkTheme.extended.consoleTag,
        )
    }
}

/** A console hairline across the strip. */
@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(KnotworkTheme.extended.consoleFg.copy(alpha = RUN_HAIRLINE_ALPHA)),
    )
}
