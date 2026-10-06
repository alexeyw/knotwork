package app.knotwork.design.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.components.chips.RiskPill
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/** Card outer-border stroke width. */
private val CardBorderWidth = 1.dp

/** Width of the left accent strip that mirrors the risk pill colour. */
private val AccentStripWidth = 2.dp

/** Stroke width applied to the inner JSON args block. */
private val JsonBlockBorderWidth = 1.dp

/** Summary clamp limit for the HITL confirmation card. */
private const val SUMMARY_MAX_LINES = 3

/** Collapsed line count for the JSON args block. */
private const val JSON_COLLAPSED_MAX_LINES = 2

/** Collapsed line count of the request block. */
private const val REQUEST_COLLAPSED_MAX_LINES = 3

/**
 * Collapsed line count of the request block on a destructive card at a large
 * font scale: the typed-confirm row needs the room, and the whole card must stay
 * on a 360 x 760 dp screen at 200 %.
 */
private const val REQUEST_COLLAPSED_MAX_LINES_TIGHT = 2

/** Font scale from which a destructive card collapses the request to [REQUEST_COLLAPSED_MAX_LINES_TIGHT]. */
private const val TIGHT_FONT_SCALE = 1.5f

/** Gap between the request block, its divider and the call — the card's gap pulled in by 4 dp. */
private val RequestGap = 8.dp

/** Size of the source glyph in the request label row. */
private val SourceGlyphSize = 16.dp

/** Gap between the source glyph and the label. */
private val SourceGlyphGap = 6.dp

/** Size of the request block's expand chevron. */
private val RequestChevronSize = 18.dp

/** Smallest height of the request block: it is one touch target. */
private val RequestMinHeight = 48.dp

/** Opacity of the source glyph of a trigger that can no longer be named. */
private const val UNNAMED_TRIGGER_GLYPH_ALPHA = 0.6f

/**
 * Human-in-the-loop confirmation card surfaced inside the assistant bubble
 * when the agent wants to execute a tool. Renders the risk tier, tool name,
 * an optional one-line summary, a collapsible JSON arguments block, and an
 * action row gated on the risk level. A blank
 * [HitlConfirmationModel.summary] drops the summary line instead of echoing
 * the tool name into it.
 *
 * State helpers are factored to [HitlConfirmationState]
 * so the gating logic is unit-testable without Compose.
 *
 * **Stateless** — the typed-confirm input is hoisted to the caller; the card
 * only renders [pendingTypedConfirm] and forwards every keystroke through
 * [onTypedConfirmChange]. The screen owns persistence.
 *
 * @param model immutable card payload (risk, tool name, summary, args).
 * @param pendingTypedConfirm current value of the destructive type-confirm
 * field. Ignored for Readonly / Sensitive variants.
 * @param onTypedConfirmChange invoked with each keystroke in the type-confirm
 * field. No-op for Readonly / Sensitive.
 * @param allowOnceEnabled gates the Allow CTA. Default policy:
 * `HitlConfirmationState.isAllowOnceEnabled(model.risk, pendingTypedConfirm)`.
 * Callers may override (e.g. while a previous Allow request is still pending
 * server-side).
 * @param onAllowOnce invoked when the user taps Allow.
 * @param onAllowAlways invoked when the user taps "Always allow". `null`
 * hides the affordance (the catalog always hides it for Destructive and
 * Readonly variants regardless).
 * @param onReject invoked when the user taps Reject.
 * @param modifier optional layout modifier applied to the card root.
 */
@Composable
fun HitlConfirmationCard(
    model: HitlConfirmationModel,
    pendingTypedConfirm: String,
    onTypedConfirmChange: (String) -> Unit,
    allowOnceEnabled: Boolean,
    onAllowOnce: () -> Unit,
    onAllowAlways: (() -> Unit)?,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val riskColor = riskBorderColor(model.risk)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // IntrinsicSize.Min sizes the Row to its content's intrinsic height before
            // the accent-strip Spacer measures. Without this, `Spacer.fillMaxHeight()`
            // would consume the parent's full available height and the card would
            // balloon to fill any unbounded vertical container.
            .height(IntrinsicSize.Min)
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .border(
                border = BorderStroke(width = CardBorderWidth, color = riskColor),
                shape = KnotworkTheme.shapes.md,
            ),
    ) {
        Spacer(
            modifier = Modifier
                .width(AccentStripWidth)
                .fillMaxHeight()
                .background(color = riskColor),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
        ) {
            RiskPillRow(model = model)
            val request = model.request
            if (request == null) {
                ToolNameRow(toolName = model.toolName, described = false)
            } else {
                // The request goes above the call: the card reads "you asked ->
                // the agent wants to use -> allow?", and the call stays right
                // above the buttons, the last thing read before deciding.
                Column(verticalArrangement = Arrangement.spacedBy(RequestGap)) {
                    RequestBlock(request = request, risk = model.risk)
                    HorizontalDivider(color = KnotworkTheme.extended.divider)
                    ToolNameRow(toolName = model.toolName, described = true)
                }
            }
            // A blank summary means the caller has no explanation to show. The
            // line is dropped rather than filled with the tool id, which would
            // print the same string twice and read as a rendering fault.
            if (model.summary.isNotBlank()) {
                Text(
                    text = model.summary,
                    style = KnotworkTextStyles.BodyBase,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = SUMMARY_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            JsonArgsBlock(arguments = model.arguments)
            if (HitlConfirmationState.showTypedConfirmRow(model.risk)) {
                TypedConfirmRow(value = pendingTypedConfirm, onChange = onTypedConfirmChange)
            }
            ButtonRow(
                risk = model.risk,
                allowOnceEnabled = allowOnceEnabled,
                onAllowOnce = onAllowOnce,
                onAllowAlways = onAllowAlways,
                onReject = onReject,
            )
        }
    }
}

/** Top row — risk pill on the leading side, timestamp on the trailing side. */
@Composable
private fun RiskPillRow(model: HitlConfirmationModel) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        RiskPill(risk = model.risk)
        Spacer(modifier = Modifier.fillMaxWidth().weight(1f))
        Text(
            text = model.timestamp,
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

/**
 * The tool id in mono. Next to a request it is described to TalkBack as what
 * the agent wants to do, so the call reads as the answer to the request above it.
 */
@Composable
private fun ToolNameRow(toolName: String, described: Boolean) {
    val description = stringResource(R.string.knotwork_hitl_call_a11y, toolName)
    Text(
        text = toolName,
        style = KnotworkTextStyles.MonoBase,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = if (described) Modifier.clearAndSetSemantics { contentDescription = description } else Modifier,
    )
}

/**
 * What the run was asked to do: a label naming whose words these are, then the
 * request as plain body text — never markup, never a link — in its own reading
 * direction. It collapses like the arguments; the whole block is the touch
 * target and one TalkBack node, and it opens collapsed every time.
 */
@Composable
private fun RequestBlock(request: HitlRequestContext, risk: Risk) {
    var expanded by remember(request) { mutableStateOf(false) }
    var overflows by remember(request) { mutableStateOf(false) }
    val tight = risk == Risk.Destructive && LocalDensity.current.fontScale >= TIGHT_FONT_SCALE
    val collapsedLines = if (tight) REQUEST_COLLAPSED_MAX_LINES_TIGHT else REQUEST_COLLAPSED_MAX_LINES
    val label = request.label.let { stringResource(it.text, *listOfNotNull(it.argument).toTypedArray()) }
    val text = request.request
    val description = when {
        text == null -> stringResource(R.string.knotwork_hitl_request_a11y_image_only)
        request.shortened -> stringResource(R.string.knotwork_hitl_request_a11y_shortened, label, text)
        else -> stringResource(R.string.knotwork_hitl_request_a11y, label, text)
    }
    val clickLabel = stringResource(
        if (expanded) R.string.knotwork_hitl_request_show_less else R.string.knotwork_hitl_request_show_all,
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RequestMinHeight)
            .then(
                if (overflows) {
                    Modifier.clickable(onClickLabel = clickLabel) { expanded = !expanded }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = request.glyph,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier
                    .size(SourceGlyphSize)
                    .alpha(
                        if (request.source ==
                            HitlRequestSource.Trigger(name = null)
                        ) {
                            UNNAMED_TRIGGER_GLYPH_ALPHA
                        } else {
                            1f
                        },
                    ),
            )
            Spacer(modifier = Modifier.width(SourceGlyphGap))
            Text(
                text = label,
                style = KnotworkTextStyles.LabelMd,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {},
            )
            if (overflows) {
                Icon(
                    imageVector = if (expanded) AppIcons.ArrowUp else AppIcons.ArrowDown,
                    contentDescription = null,
                    tint = KnotworkPalette.Accent500,
                    modifier = Modifier.size(RequestChevronSize),
                )
            }
        }
        if (text == null) {
            Text(
                text = stringResource(R.string.knotwork_hitl_request_image_only_body),
                style = KnotworkTextStyles.BodyBase,
                color = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.clearAndSetSemantics {},
            )
        } else {
            Text(
                text = text,
                style = KnotworkTextStyles.BodyBase.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
                overflow = TextOverflow.Ellipsis,
                // Only the collapsed layout can tell whether there is more to show;
                // once expanded the chevron stays so the block can collapse again.
                onTextLayout = { layout -> if (!expanded) overflows = layout.hasVisualOverflow },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {},
            )
        }
    }
}

/** The glyph of a request's source: an image whenever the message carried one. */
private val HitlRequestContext.glyph: ImageVector
    get() = if (hadImage) {
        AppIcons.Image
    } else {
        when (source) {
            HitlRequestSource.Chat -> AppIcons.Chat
            HitlRequestSource.Shared -> AppIcons.Share
            is HitlRequestSource.Trigger -> AppIcons.Trigger
            HitlRequestSource.ScheduledTask -> AppIcons.History
            HitlRequestSource.QuickTile -> AppIcons.Bolt
            HitlRequestSource.OtherApp -> AppIcons.External
        }
    }

/** Collapsible mono JSON args block — surface2 background, 1 dp outlineVariant border. */
@Composable
private fun JsonArgsBlock(arguments: Map<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    val rendered = remember(arguments) {
        if (arguments.isEmpty()) {
            "{}"
        } else {
            arguments.entries.joinToString(
                separator = ",\n",
                prefix = "{\n",
                postfix = "\n}",
            ) { (k, v) -> "  \"$k\": $v" }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface2)
            .border(
                border = BorderStroke(
                    width = JsonBlockBorderWidth,
                    color = KnotworkTheme.extended.divider,
                ),
                shape = KnotworkTheme.shapes.sm,
            )
            .clickable { expanded = !expanded }
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = rendered,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurface2,
                maxLines = if (expanded) Int.MAX_VALUE else JSON_COLLAPSED_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) AppIcons.ArrowUp else AppIcons.ArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.knotwork_hitl_args_collapse else R.string.knotwork_hitl_args_expand,
                ),
                tint = KnotworkPalette.Accent500,
                modifier = Modifier.size(KnotworkTheme.spacing.sp4),
            )
        }
    }
}

/** Destructive "type yes" row. */
@Composable
private fun TypedConfirmRow(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = {
            Text(
                text = stringResource(
                    R.string.knotwork_hitl_typed_confirm_placeholder,
                    HitlConfirmationState.DESTRUCTIVE_CONFIRM_WORD,
                ),
                style = KnotworkTextStyles.MonoBase,
                color = KnotworkTheme.extended.onSurfaceDim,
            )
        },
        singleLine = true,
        textStyle = KnotworkTextStyles.MonoBase,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Bottom action row — Reject + Allow + (optional) Always allow.
 *
 * Uses [FlowRow] so the Sensitive variant (which adds the "Always allow"
 * text button) wraps onto a second line when the bubble's max-width
 * (`screenWidth - 64 dp` for the assistant side) cannot fit all three CTAs
 * side-by-side. Cross-axis alignment is `End` to keep the primary CTA
 * pinned to the trailing edge regardless of wrap.
 */
@Composable
private fun ButtonRow(
    risk: Risk,
    allowOnceEnabled: Boolean,
    onAllowOnce: () -> Unit,
    onAllowAlways: (() -> Unit)?,
    onReject: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(
            space = KnotworkTheme.spacing.sp2,
            alignment = Alignment.End,
        ),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier.fillMaxWidth(),
    ) {
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_hitl_action_reject),
            onClick = onReject,
            destructive = true,
        )
        if (HitlConfirmationState.showAlwaysAllow(risk) && onAllowAlways != null) {
            KnotworkTextButton(
                text = stringResource(R.string.knotwork_hitl_action_always_allow),
                onClick = onAllowAlways,
            )
        }
        KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_hitl_action_allow_once),
            onClick = onAllowOnce,
            enabled = allowOnceEnabled,
        )
    }
}

/** Border + accent-strip colour for the card outer chrome. */
@Composable
private fun riskBorderColor(risk: Risk): Color = when (risk) {
    Risk.Readonly -> KnotworkTheme.extended.riskReadonly
    Risk.Sensitive -> KnotworkTheme.extended.riskSensitive
    Risk.Destructive -> KnotworkTheme.extended.riskDestructive
}
