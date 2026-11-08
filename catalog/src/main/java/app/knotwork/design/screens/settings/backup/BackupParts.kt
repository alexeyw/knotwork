package app.knotwork.design.screens.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.misc.KnotworkStatCell
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** A glyph and a line to read: plain, or [strong] for a fact the user must not miss. */
@Composable
internal fun BackupNote(icon: ImageVector, text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (strong) MaterialTheme.colorScheme.onSurface else KnotworkTheme.extended.onSurface2,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Text(
            text = text,
            style = if (strong) {
                KnotworkTextStyles.BodyBase.copy(
                    fontWeight = FontWeight.Medium,
                )
            } else {
                KnotworkTextStyles.BodyBase
            },
            color = if (strong) MaterialTheme.colorScheme.onSurface else KnotworkTheme.extended.onSurface2,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One of the screen's two actions: a tinted tile, a title and what the action does. */
@Composable
internal fun BackupActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, KnotworkTheme.shapes.sm),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = subtitle, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
        }
    }
}

/** A rounded card that groups [BackupActionRow]s, one divider between each. */
@Composable
internal fun BackupActionGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KnotworkTheme.extended.surface1, KnotworkTheme.shapes.md)
            .border(1.dp, KnotworkTheme.extended.divider, KnotworkTheme.shapes.md),
    ) {
        content()
    }
}

/** The secrets checkbox: the whole row toggles, and TalkBack reads one checkbox with its explanation. */
@Composable
internal fun BackupCheckboxRow(
    checked: Boolean,
    label: String,
    explanation: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = KnotworkTheme.spacing.sp1),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            Checkbox(checked = checked, onCheckedChange = null)
        }
        Column(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
            Text(text = label, style = KnotworkTextStyles.BodyBase, color = MaterialTheme.colorScheme.onSurface)
            Text(text = explanation, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
        }
    }
}

/** The file the backup is written to: a lock tile, its name in full and what is known about it. */
@Composable
internal fun BackupFileRow(name: String?, meta: String?, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).background(KnotworkTheme.extended.surface3, KnotworkTheme.shapes.sm),
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            if (name != null) {
                // Never ellipsised: the date is at the end of the name.
                Text(text = name, style = KnotworkTextStyles.MonoBase, color = MaterialTheme.colorScheme.onSurface)
            }
            if (meta != null) {
                Text(text = meta, style = KnotworkTextStyles.BodySm, color = KnotworkTheme.extended.onSurface2)
            }
        }
    }
}

/** Where a step of the work is. */
internal enum class StepMark { DONE, NOW, NEXT }

/** The named steps under the progress bar: done, now and next. */
@Composable
internal fun BackupSteps(steps: List<Pair<String, StepMark>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
        steps.forEach { (name, mark) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StepMarkGlyph(mark)
                Text(
                    text = name,
                    style = KnotworkTextStyles.BodySm,
                    color = if (mark ==
                        StepMark.NEXT
                    ) {
                        KnotworkTheme.extended.onSurfaceMuted
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun StepMarkGlyph(mark: StepMark) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
        when (mark) {
            StepMark.DONE -> Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalSuccess,
                modifier = Modifier.size(18.dp),
            )
            StepMark.NOW -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            StepMark.NEXT -> Box(Modifier.size(10.dp).border(1.5.dp, KnotworkTheme.extended.onSurfaceDim, CircleShape))
        }
    }
}

/**
 * The head of a result: a tile in the signal colour and the title, read as a heading — and announced as the pane's
 * new title when the result replaces what was on screen.
 */
@Composable
internal fun BackupResultHead(icon: ImageVector, tone: Color, title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).background(tone.copy(alpha = 0.14f), KnotworkTheme.shapes.md),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tone, modifier = Modifier.size(24.dp))
        }
        Text(
            text = title,
            style = KnotworkTextStyles.TitleLg,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Counts, two by two — one column at large font scales — each cell one stop for TalkBack. */
@Composable
internal fun BackupStats(cells: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val oneColumn = KnotworkTheme.a11y.fontScale() >= LARGE_FONT_SCALE
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
        cells.chunked(if (oneColumn) 1 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
                row.forEach { (value, label) ->
                    KnotworkStatCell(
                        value = value,
                        label = label,
                        modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                    )
                }
                if (row.size == 1 && !oneColumn) Box(Modifier.weight(1f))
            }
        }
    }
}

/** The action pinned under the scroll, with a divider above; the app shell keeps it above the keyboard. */
@Composable
internal fun BackupBottomAction(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Only the sides: the app shell lifts the whole screen over the keyboard and its bottom navigation sits on the
    // system bar below — padding those again left a gap the height of the bar. A bar or cutout on the side
    // (landscape) is this screen's to clear, as the body does.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(backupSideInsets),
    ) {
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        Box(
            modifier = Modifier.fillMaxWidth().padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp4,
                top = KnotworkTheme.spacing.sp3,
                bottom = KnotworkTheme.spacing.sp4,
            ),
        ) {
            content()
        }
    }
}

/** The system bars and cutouts on the screen's sides, which the body and the pinned action both clear. */
internal val backupSideInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/** From this font scale up the counts stack in one column. */
private const val LARGE_FONT_SCALE = 1.75f
