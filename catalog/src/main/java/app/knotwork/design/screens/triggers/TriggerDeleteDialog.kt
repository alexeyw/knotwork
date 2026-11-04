@file:Suppress("MatchingDeclarationName") // Hosts TriggerDeleteDialogContent + helpers.

package app.knotwork.design.screens.triggers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading trash icon on the dialog. */
private val DialogIcon = 40.dp

/**
 * Stateless body of the trigger delete-confirmation dialog. The host wraps this
 * in a `Dialog` / `AlertDialog` container; this composable just draws the card.
 *
 * A trigger owns nothing downstream (the bound pipeline is independent), so
 * there is a single confirm branch — no dependent-resource warning.
 *
 * @param state the trigger name being deleted.
 * @param strings localised display strings.
 * @param onConfirm delete-confirmed callback.
 * @param onCancel dismiss callback.
 * @param modifier optional layout modifier applied to the card.
 */
@Composable
fun TriggerDeleteDialogContent(
    state: TriggerDeleteUi,
    modifier: Modifier = Modifier,
    strings: TriggerDeleteStrings = TriggerDeleteStrings(),
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KnotworkTheme.shapes.lg,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(KnotworkTheme.spacing.sp5)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            ) {
                Box(
                    modifier = Modifier
                        .size(DialogIcon)
                        .clip(KnotworkTheme.shapes.full)
                        .background(KnotworkTheme.extended.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Trash,
                        contentDescription = null,
                        tint = KnotworkTheme.extended.riskDestructive,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = strings.title,
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = strings.bodyFormat.format(state.triggerName),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp3),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = KnotworkTheme.spacing.sp5),
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KnotworkTextButton(text = strings.cancel, onClick = onCancel)
                DestructiveButton(text = strings.delete, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun DestructiveButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(KnotworkTheme.shapes.full)
            .background(KnotworkTheme.extended.riskDestructive)
            .clickable(onClick = onClick)
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = KnotworkTextStyles.LabelMd.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
    }
}

/** Localised string bundle threaded into [TriggerDeleteDialogContent]. */
data class TriggerDeleteStrings(
    val title: String = "Delete this trigger?",
    val bodyFormat: String =
        "\"%1\$s\" and its background schedule will be removed. The bound pipeline isn't affected. " +
            "This can't be undone.",
    val cancel: String = "Cancel",
    val delete: String = "Delete",
)
