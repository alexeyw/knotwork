package app.knotwork.design.components.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.chips.Status
import app.knotwork.design.components.chips.StatusPill
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Row visual height. */
private val ToolRowHeight = 64.dp

/** Diameter of the leading tool icon halo. */
private val ToolLeadingSize = 32.dp

/** Alpha applied to the leading-tint halo. */
private const val LEADING_TINT_ALPHA = 0.18f

/**
 * Tool / MCP-server connection status. Maps onto [StatusPill]'s [Status]
 * enum via [toStatus] so the trailing pill stays in sync with the list-row
 * connection indicator.
 */
enum class ConnectionStatus {
    /** Server reachable, tools discovered. */
    Connected,

    /** Server unreachable or auth failure — flagged in red. */
    Disconnected,

    /** Initial discovery / refresh in progress. */
    Syncing,

    /** Connection dropped mid-session — flagged in red. */
    Error,
}

/**
 * Knotwork tool / MCP-server list row.
 *
 * Visual contract:
 *  - 64 dp tall; leading 32 dp tool icon, `TitleMd` title, `BodySm` server
 *    name subtitle, trailing connection-status pill (reuses [StatusPill]).
 *  - Tap on the row body invokes [onClick] (typically opens the tool detail
 *    sheet).
 *
 * @param title tool display name; rendered in `TitleMd`, 1-line ellipsis.
 * @param serverName originating MCP server / namespace; rendered as the
 * subtitle.
 * @param connection trailing connection-status pill driver.
 * @param leadingIcon vector rendered inside the 32 dp tool mark.
 * @param leadingTint hue used for the leading-icon halo and tint.
 * @param onClick invoked when the user taps the row body.
 * @param modifier optional layout modifier applied to the row root.
 */
@Composable
fun ToolListRow(
    title: String,
    serverName: String,
    connection: ConnectionStatus,
    leadingIcon: ImageVector,
    leadingTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier
            .fillMaxWidth()
            .height(ToolRowHeight)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = KnotworkTheme.spacing.sp4),
    ) {
        Box(
            modifier = Modifier
                .size(ToolLeadingSize)
                .background(
                    color = leadingTint.copy(alpha = LEADING_TINT_ALPHA),
                    shape = KnotworkTheme.shapes.sm,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = leadingTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = serverName,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(status = connection.toStatus())
    }
}

/** Maps a [ConnectionStatus] onto the equivalent [Status] for the trailing pill. */
internal fun ConnectionStatus.toStatus(): Status = when (this) {
    ConnectionStatus.Connected -> Status.Success
    ConnectionStatus.Disconnected -> Status.Error
    ConnectionStatus.Syncing -> Status.Running
    ConnectionStatus.Error -> Status.Error
}
