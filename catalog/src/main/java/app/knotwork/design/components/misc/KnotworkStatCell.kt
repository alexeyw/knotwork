package app.knotwork.design.components.misc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Single stat-grid cell used by the Settings → Memory card's 4-up
 * counter row (CHUNKS / SIZE / THREADS / AVG SCORE).
 *
 * @param value Pre-formatted primary value (e.g. `"1 248"`, `"14.2 MB"`).
 * @param label SCREAMING_SNAKE_CASE-style label below the value.
 * @param modifier Layout modifier — typically `Modifier.weight(1f)` when
 *   used inside a Row.
 */
@Composable
fun KnotworkStatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
    ) {
        Text(
            text = value,
            style = KnotworkTextStyles.TitleLg.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}
