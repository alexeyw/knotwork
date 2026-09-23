package app.knotwork.design.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.knotwork.design.components.controls.KnotworkCompactSlider
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Branded labelled slider used for every numeric parameter row inside
 * `SettingsContent` (LLM parameters, the memory tuning sliders, and the
 * auto-summarize threshold), so all of them render at identical density.
 *
 * Visual contract:
 *  - `BodySm` SemiBold label on the left, muted `MonoSm` value label on the
 *    right edge.
 *  - `KnotworkCompactSlider` beneath (compact pill thumb, 4 dp track).
 *
 * The composable is stateless: callers provide both the current [value]
 * and the [valueLabel] string (so the row can pluralise or format the
 * displayed number).
 *
 * @param label row title rendered in `BodySm` SemiBold.
 * @param valueLabel pre-formatted current value (e.g. `"0.7"`, `"4096 tok"`).
 * @param value current slider position.
 * @param onValueChange callback invoked while the user drags.
 * @param valueRange inclusive value range.
 * @param modifier optional modifier applied to the wrapping `Column`.
 * @param steps number of discrete intermediate steps; `0` (default) keeps
 *  the slider continuous.
 * @param enabled `false` puts the slider in a read-only state during a
 *  `PendingChange` transition.
 * @param errorText optional validation message rendered beneath the slider
 *  (e.g. for the pipeline node-config fields); `null` (default) shows nothing.
 *
 * Sliders had no description slot at all before this — which is why the controls
 * whose meaning is least guessable (`Top-K`, thresholds, the run ceilings) were
 * the ones with nothing on screen to explain them. The explanation is summoned
 * from the enclosing `SettingsAnchor`, and appears under the track so the
 * label-to-value pair never moves while the reader drags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongParameterList") // Stable public API; each parameter maps to a row attribute.
@Composable
fun KnotworkParamSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    errorText: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SettingsHintGlyph(settingName = label)
            }
            Text(
                text = valueLabel,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        KnotworkCompactSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
        if (errorText != null) {
            Text(
                text = errorText,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.signalError,
            )
        }
        // Under the track, so the label-to-value pair the reader is watching
        // while dragging never moves.
        SettingsHintBody()
    }
}
