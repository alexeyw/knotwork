package app.knotwork.design.components.lists

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Minimum header height, so a collapsible header still offers a 48 dp target. */
private val HeaderMinHeight = 48.dp

/** Size of the chevron and warning glyphs. */
private val HeaderIconSize = 18.dp

/**
 * Rotation applied to the chevron when the section is **collapsed**.
 *
 * The base glyph is [AppIcons.ArrowUp], so expanded reads "fold me up" at 0°
 * and collapsed reads "unfold me" at 180° — the same direction convention the
 * MCP server row already used for its own expand toggle.
 */
private const val CHEVRON_COLLAPSED_DEGREES = 180f

/**
 * The one section header for list surfaces: a monospace title, an optional
 * count line, an optional warning, an optional trailing slot, and — when the
 * section can be folded away — a chevron that toggles it.
 *
 * ### Why this is a catalog component
 *
 * Three screens wanted the same object and each had grown its own private copy
 * (`ToolsContent`, `ModelsContent`, and the section headings the More tab
 * needed). Three private copies of one header is how a count and a chevron
 * drift apart: the moment one of them starts formatting `3 tools` differently,
 * or animates its chevron on a different curve, the product has two section
 * headers that only look like one.
 *
 * ### The two rules it encodes
 *
 * 1. **The count describes the rows the section contains** — not something
 *    derived from them. Built-in tools count tools; MCP servers count servers,
 *    and each server row carries its own tool count. A header whose number
 *    matches no row beneath it is a number the reader cannot check.
 * 2. **A collapsed section may not hide a problem.** [warning] is rendered on
 *    the header itself as glyph *and* words, so folding a section away never
 *    conceals a failure inside it. Expanded, the caller passes `null` — the
 *    rows say it themselves.
 *
 * [countLabel] and [warning] arrive already formatted rather than as an
 * `Int` + unit: plural rules belong to the host's `pluralStringResource`, not
 * to a catalog component that would have to guess them.
 *
 * @param title section title; rendered uppercase-ish in `MonoSm` per the
 *        surface convention, never truncated below one line.
 * @param modifier layout modifier applied to the header row.
 * @param countLabel optional formatted count of the rows in the section
 *        (e.g. `"3 tools"`, `"3 servers"`), rendered under the title.
 * @param warning optional problem summary for a **collapsed** section
 *        (e.g. `"1 disconnected"`), rendered with [AppIcons.Warn] in the
 *        signal-warning colour beside [countLabel]. Pass `null` when expanded.
 * @param collapsible whether the header owns a chevron and toggles [collapsed].
 * @param collapsed current fold state; ignored unless [collapsible].
 * @param onToggleCollapsed invoked when the header is tapped; only wired when
 *        [collapsible].
 * @param trailing optional slot rendered at the end of the title line — used by
 *        surfaces that hang a short status string or action off the header.
 * @param showDivider whether to draw the hairline under the header, matching
 *        the surfaces whose rows are divider-separated.
 */
@Composable
@Suppress("LongParameterList") // Documented public slot API; collapsing hurts call-site clarity.
fun KnotworkSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    countLabel: String? = null,
    warning: String? = null,
    collapsible: Boolean = false,
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
) {
    val expandLabel = stringResource(R.string.knotwork_section_header_expand)
    val collapseLabel = stringResource(R.string.knotwork_section_header_collapse)
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    // Reduced motion flips the chevron instead of rotating it: a 0 ms tween
    // still lands on the same angle, so the state stays readable.
    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) CHEVRON_COLLAPSED_DEGREES else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else KnotworkTheme.motion.dur3,
            easing = KnotworkTheme.motion.easeEmph,
        ),
        label = "sectionHeaderChevron",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (collapsible) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onToggleCollapsed)
                        // The state, not just the title: TalkBack announces the
                        // group as expandable / collapsible and offers the
                        // action, so a folded group reports that it is folded.
                        .semantics {
                            if (collapsed) {
                                expand(label = expandLabel) {
                                    onToggleCollapsed()
                                    true
                                }
                            } else {
                                collapse(label = collapseLabel) {
                                    onToggleCollapsed()
                                    true
                                }
                            }
                        }
                } else {
                    Modifier
                },
            )
            .heightIn(min = if (collapsible) HeaderMinHeight else 0.dp)
            // The heading lives on the header row itself, not on an inner
            // column. An inner `mergeDescendants` block would be a second
            // merged node inside the one `clickable` already creates, so the
            // header announced twice — as a button, and separately as a
            // heading — and the count and warning landed on the half that
            // carries no action.
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp3),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (countLabel != null || warning != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                ) {
                    if (countLabel != null) {
                        Text(
                            text = countLabel,
                            style = KnotworkTextStyles.MonoSm,
                            color = KnotworkTheme.extended.onSurfaceMuted,
                        )
                    }
                    if (warning != null) {
                        Icon(
                            imageVector = AppIcons.Warn,
                            contentDescription = null,
                            tint = KnotworkTheme.extended.signalWarn,
                            modifier = Modifier.size(HeaderIconSize),
                        )
                        Text(
                            text = warning,
                            style = KnotworkTextStyles.MonoSm,
                            color = KnotworkTheme.extended.signalWarn,
                        )
                    }
                }
            }
        }
        if (trailing != null) trailing()
        if (collapsible) {
            Icon(
                imageVector = AppIcons.ArrowUp,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurfaceMuted,
                modifier = Modifier.size(HeaderIconSize).rotate(chevronRotation),
            )
        }
    }
    if (showDivider) HorizontalDivider(color = KnotworkTheme.extended.divider)
}
