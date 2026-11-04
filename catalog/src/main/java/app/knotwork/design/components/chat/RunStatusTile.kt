package app.knotwork.design.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Card horizontal/vertical padding inside the container. */
private val TilePadding = 16.dp

/** Diameter of the leading status glyph rendered next to the header label. */
private val HeaderIconSize = 18.dp

/**
 * Shared chrome of the chat stream's **run-status tiles** — the cards that
 * report on the run itself rather than on anything the agent said.
 *
 * Rounded `surface1` container with a muted outline, a glyph-and-label header
 * row, and a caller-supplied body. The outline is what separates this family
 * from the two cards it sits beside: the cream clarification card and the
 * risk-tinted HITL card are both the *agent asking about the work*, while a
 * status tile is the app reporting on the run's lifecycle.
 *
 * Extracted rather than copied. There are two tiles today — a run that died
 * with its process, and a run paused at one of its own limits — and they must
 * read as one family; two independent copies of a container drift the first
 * time a token moves, and the drift is invisible until the two are seen side by
 * side, which in the chat stream they never are.
 *
 * @param icon Leading status glyph, tinted `onSurfaceMuted`.
 * @param header Bold label naming what happened.
 * @param modifier Optional layout modifier applied to the tile root.
 * @param content Body and action rows, laid out in the tile's own column.
 */
@Composable
internal fun RunStatusTile(
    icon: ImageVector,
    header: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .border(
                border = BorderStroke(width = 1.dp, color = KnotworkTheme.extended.outlineStrong),
                shape = KnotworkTheme.shapes.md,
            )
            .padding(TilePadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(HeaderIconSize),
            )
            Text(
                text = header,
                style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        content()
    }
}
