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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
@Suppress("LongParameterList", "LongMethod") // Stable HITL API + 6-section spec mandates this shape.
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
            Text(
                text = model.toolName,
                style = KnotworkTextStyles.MonoBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
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
