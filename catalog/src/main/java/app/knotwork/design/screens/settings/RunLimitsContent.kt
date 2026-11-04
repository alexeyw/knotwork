package app.knotwork.design.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.controls.KnotworkCompactSlider
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * The run-limits screen: every ceiling one autonomous run is held to, plus the
 * one axis the app deliberately does not measure.
 *
 * Stateless, like every other settings surface in this module — the host
 * resolves the copy and owns the values.
 *
 * The screen exists because the limits shipped before any way to see them: the
 * engine has been stopping runs against four numbers of which exactly one was
 * visible, and the only prose about it inside the app promised a pause that
 * never happens.
 *
 * @param state Values, copy and states to render.
 * @param callbacks Value-change handlers.
 * @param modifier Optional layout modifier.
 * @param onBack Invoked by the scaffold's back affordance.
 */
@Composable
fun RunLimitsContent(
    state: RunLimitsViewState,
    callbacks: RunLimitsCallbacks,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    CategoryScaffold(
        title = stringResource(R.string.knotwork_settings_run_limits_title),
        subtitle = stringResource(R.string.knotwork_settings_count, CONFIGURABLE_LIMITS),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = state.intro,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )

        SettingsGroupLabel(text = state.stepsGroupLabel)
        LimitSliderRow(
            state = state.steps,
            onValueChange = callbacks.onStepsChange,
            onValueChangeFinished = callbacks.onStepsCommit,
        )
        LimitSliderRow(
            state = state.stepsBackground,
            onValueChange = callbacks.onStepsBackgroundChange,
            onValueChangeFinished = callbacks.onStepsBackgroundCommit,
        )

        SettingsGroupLabel(text = state.tokensGroupLabel)
        LimitSliderRow(
            state = state.tokens,
            onValueChange = callbacks.onTokensChange,
            onValueChangeFinished = callbacks.onTokensCommit,
        )
        LimitSliderRow(
            state = state.tokensBackground,
            onValueChange = callbacks.onTokensBackgroundChange,
            onValueChangeFinished = callbacks.onTokensBackgroundCommit,
        )

        SettingsGroupLabel(text = state.spendGroupLabel)
        StatementRow(state = state.spend)

        Text(
            text = state.softNote,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}

/**
 * How many limits this screen actually lets you change.
 *
 * Four, not five: the spend row is a statement, and counting it would promise a
 * control the app cannot offer.
 */
private const val CONFIGURABLE_LIMITS: Int = 4

/** Section heading inside the run-limits screen. */
@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = KnotworkTextStyles.LabelSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

/**
 * One configurable limit: a labelled slider with an explanation and, when the
 * value is not independently set, a qualifier saying so.
 *
 * Deliberately **not** [KnotworkParamSlider] with extra parameters. Two things
 * this row needs are load-bearing here and absent there: a description (without
 * one, the only prose about a limit lived in the settings-search index, where
 * it went stale unnoticed) and a state qualifier.
 *
 * The title, qualifier and value share a [FlowRow] rather than a `Row`. That is
 * the whole reason this component exists rather than a wider `Row`: at a 200 %
 * font scale "Steps per background run" and its qualifier cannot both fit one
 * line, and a `Row` resolves that by clipping the qualifier off the screen
 * edge. Wrapping keeps every part readable at every scale.
 *
 * @param state Copy, value and range for this row.
 * @param onValueChange Invoked on every frame of a drag; move the displayed
 *   value, do not persist from here.
 * @param onValueChangeFinished Invoked once the gesture ends. This is the
 *   commit point, and the distinction is load-bearing for the inherited rows:
 *   persisting a background limit is what stops it following the interactive
 *   one, so it must happen when the user decides, not when they touch.
 */
@Composable
fun LimitSliderRow(
    state: LimitSliderRowState,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Text(
                text = state.label,
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.qualifier?.let { QualifierChip(text = it) }
            Text(
                text = state.valueLabel,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        KnotworkCompactSlider(
            value = state.value,
            onValueChange = onValueChange,
            valueRange = state.valueRange,
            onValueChangeFinished = onValueChangeFinished,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = state.minLabel,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            Text(
                text = state.maxLabel,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        Text(
            text = state.description,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

/**
 * A value that follows another rather than being set on its own.
 *
 * Rendered as a word, not a colour: a dimmed number alone cannot say *why* it
 * is dimmed, and "this is inherited" is exactly the sort of thing a user needs
 * told rather than hinted.
 */
@Composable
private fun QualifierChip(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = Modifier
            .background(color = KnotworkTheme.extended.surface2, shape = RoundedCornerShape(percent = 50))
            .border(
                width = 1.dp,
                color = KnotworkTheme.extended.divider,
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    )
}

/**
 * An axis the app states rather than controls.
 *
 * This is the spending limit, and its shape is the point. It is **not** a
 * disabled slider: a disabled control says "not available right now", which
 * invites the user to look for the thing that would enable it. Nothing will.
 * The app is bring-your-own-key, never sees an invoice, and a bundled price
 * table would go stale between releases and show a wrong number *as money* —
 * so the honest surface is a sentence saying so, and the token limit above is
 * the control that actually bounds spend.
 *
 * @param state Label, state word and explanation.
 */
@Composable
fun StatementRow(state: StatementRowState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Text(
                text = state.label,
                style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            QualifierChip(text = state.stateWord)
        }
        Text(
            text = state.body,
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}
