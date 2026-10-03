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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Row visual height — large enough to host an icon tile + 2 text lines comfortably. */
private val NavRowHeight = 72.dp

/** Side length of the leading icon tile (rounded square). */
private val LeadingTileSize = 48.dp

/** Size of the icon glyph rendered inside the leading tile. */
private val LeadingIconSize = 22.dp

/**
 * Compact navigation list row used by surfaces that primarily route the
 * user to other screens (e.g. the `More` tab).
 *
 * Visual contract:
 *  - 72 dp tall; full-width clickable surface.
 *  - Leading 48 dp rounded-square tile filled with `KnotworkTheme.extended.surface1`,
 *    centred [leadingIcon] tinted `KnotworkTheme.extended.onSurfaceMuted`.
 *  - Title in `TitleMd` (1-line ellipsis); optional subtitle in `MonoSm`
 *    (`KnotworkTheme.extended.onSurfaceMuted`, 1-line ellipsis).
 *  - Optional trailing slot for status pills, badges, etc.
 *  - Permanent trailing chevron-right glyph hinting at navigation.
 *
 * @param title primary label.
 * @param leadingIcon vector rendered inside the leading tile.
 * @param onClick invoked when the user taps the row body.
 * @param modifier optional layout modifier applied to the row root.
 * @param subtitle optional secondary label (rendered when non-null).
 * @param trailing optional composable slot rendered immediately before
 * the chevron — typically a badge or status pill.
 */
@Composable
fun KnotworkNavListRow(
    title: String,
    leadingIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier
            .fillMaxWidth()
            .height(NavRowHeight)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = KnotworkTheme.spacing.sp4),
    ) {
        Box(
            modifier = Modifier
                .size(LeadingTileSize)
                .background(
                    color = KnotworkTheme.extended.surface1,
                    shape = KnotworkTheme.shapes.md,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(LeadingIconSize),
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
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
        Icon(
            imageVector = AppIcons.ArrowR,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}
