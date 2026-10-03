@file:Suppress("MatchingDeclarationName") // Hosts SkillDeleteDialogContent + helpers.

package app.knotwork.design.screens.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading state icon (trash / warn) on the dialog. */
private val DialogIcon = 40.dp

/**
 * Stateless body of the skill delete-confirmation dialog. The host wraps this
 * in a `Dialog` / `AlertDialog` container; this composable just draws the card.
 *
 * Renders three branches off [SkillDeleteUi.dependentPipelineNames]:
 * - **empty** — the baseline confirm ("no pipeline uses it").
 * - **one** — a single-dependent warning.
 * - **many** — an N-dependent warning with a "will break" list.
 *
 * The 1/N branches are dormant in this task (no node can reference a skill
 * yet) but are fully rendered so the SKILL-node task needs no further design.
 *
 * @param state the skill name + dependent pipelines.
 * @param strings localised display strings.
 * @param onConfirm delete-confirmed callback.
 * @param onCancel dismiss callback.
 * @param modifier optional layout modifier applied to the card.
 */
@Composable
fun SkillDeleteDialogContent(
    state: SkillDeleteUi,
    modifier: Modifier = Modifier,
    strings: SkillDeleteStrings = SkillDeleteStrings(),
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val hasDependents = state.dependentPipelineNames.isNotEmpty()
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
                        .background(
                            if (hasDependents) {
                                KnotworkTheme.extended.riskDestructive.copy(alpha = 0.14f)
                            } else {
                                KnotworkTheme.extended.surface2
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (hasDependents) AppIcons.Warn else AppIcons.Trash,
                        contentDescription = null,
                        tint = KnotworkTheme.extended.riskDestructive,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = strings.titleFormat.format(state.skillName),
                    style = KnotworkTextStyles.TitleMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Box(modifier = Modifier.padding(top = KnotworkTheme.spacing.sp3)) {
                if (hasDependents) {
                    DependentsBody(state = state, strings = strings)
                } else {
                    Text(
                        text = strings.bodyNoDependents,
                        style = KnotworkTextStyles.BodySm,
                        color = KnotworkTheme.extended.onSurface2,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = KnotworkTheme.spacing.sp5),
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KnotworkTextButton(
                    text = if (hasDependents) strings.keep else strings.cancel,
                    onClick = onCancel,
                )
                DestructiveButton(
                    text = if (hasDependents) strings.deleteAnyway else strings.delete,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun DependentsBody(state: SkillDeleteUi, strings: SkillDeleteStrings) {
    val count = state.dependentPipelineNames.size
    Column {
        Text(
            text = if (count == 1) strings.bodyOneDependent else strings.bodyManyFormat.format(count),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurface2,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = KnotworkTheme.spacing.sp3)
                .clip(KnotworkTheme.shapes.sm)
                .border(1.dp, MaterialTheme.colorScheme.outline, KnotworkTheme.shapes.sm),
        ) {
            Text(
                text = strings.willBreakFormat.format(count),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KnotworkTheme.extended.surface1)
                    .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
            )
            state.dependentPipelineNames.forEachIndexed { index, name ->
                HorizontalDivider(color = KnotworkTheme.extended.divider)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                ) {
                    Icon(
                        imageVector = AppIcons.Flow,
                        contentDescription = null,
                        tint = KnotworkTheme.extended.onSurface2,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = name,
                        style = KnotworkTextStyles.MonoSm,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Filled destructive (red) button used for the confirm action — the delete is
 * irreversible, so it carries the risk colour rather than the brand accent.
 */
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
            color = androidx.compose.ui.graphics.Color.White,
        )
    }
}

/** Localised string bundle threaded into [SkillDeleteDialogContent]. */
data class SkillDeleteStrings(
    val titleFormat: String = "Delete \"%1\$s\"?",
    val bodyNoDependents: String =
        "This permanently removes the skill. No pipeline uses it. This can't be undone.",
    val bodyOneDependent: String =
        "1 pipeline runs this skill through a SKILL node. Deleting it leaves that node with a dangling " +
            "reference — the pipeline will show a validation error until you repoint or remove it.",
    val bodyManyFormat: String =
        "%1\$d pipelines run this skill through a SKILL node. Deleting it leaves each with a dangling " +
            "reference — they'll show a validation error until you repoint or remove the node.",
    val willBreakFormat: String = "WILL BREAK · %1\$d",
    val cancel: String = "Cancel",
    val keep: String = "Keep",
    val delete: String = "Delete",
    val deleteAnyway: String = "Delete anyway",
)
