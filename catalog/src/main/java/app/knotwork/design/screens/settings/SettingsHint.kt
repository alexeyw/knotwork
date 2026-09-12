package app.knotwork.design.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * In-app destination a [KnotworkHintPanel] may point at.
 *
 * A hint never links out of the app — not to `docs/`, not to a URL. The only
 * link it may carry goes to another screen that continues the same action, so
 * the explanation stays readable with no network and no browser.
 *
 * @property label Localised link text.
 * @property onClick Navigates to the in-app destination.
 */
data class KnotworkHintLink(val label: String, val onClick: () -> Unit)

/**
 * Entry point to a setting's explanation: an 18 dp info glyph that sits
 * **after the row label**, inside a 48 dp touch target.
 *
 * Why this shape rather than a tooltip or a trailing column:
 *  - Material 3 forbids a tooltip from carrying information that exists nowhere
 *    else on the screen, and a setting's meaning is exactly that. A tooltip is
 *    also summoned by long-press or hover, neither of which a first-time user
 *    discovers, and neither of which a screen reader can reach.
 *  - A dedicated icon button is a focusable node with its own action, so
 *    TalkBack announces and activates it like any other control.
 *  - The glyph follows the label instead of sitting in a trailing slot because
 *    labels differ in length: eleven trailing glyphs line up into a stripe and
 *    read as a column of buttons, while eleven glyphs after eleven labels read
 *    as punctuation of the label.
 *
 * The glyph sits inside the row's clickable area and stays a control of its own:
 * an `IconButton` consumes the press before the row sees it, and — being a
 * merging semantics node in its own right — it is not swallowed into the row's
 * merged node, so a screen reader reaches it separately. Both halves are
 * asserted (`SettingsHintBehaviourTest`), because either one silently regressing
 * would put the explanation out of reach without changing how the screen looks.
 *
 * @param settingName Row label, spoken as part of the action description.
 * @param expanded Whether this row's [KnotworkHintPanel] is currently open.
 * @param onToggle Opens this hint (and, by the one-at-a-time rule the caller
 *   enforces, closes any other).
 * @param modifier Layout modifier applied to the touch target.
 */
@Composable
fun KnotworkHelpEntry(settingName: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val collapsedState = stringResource(R.string.knotwork_settings_hint_state_collapsed)
    val expandedState = stringResource(R.string.knotwork_settings_hint_state_expanded)
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .size(HelpEntryFootprint)
            .testTag(SETTINGS_HELP_ENTRY_TEST_TAG)
            .semantics { stateDescription = if (expanded) expandedState else collapsedState },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(HelpEntryDisc)
                .clip(MaterialTheme.shapes.small)
                .background(
                    if (expanded) {
                        MaterialTheme.colorScheme.primary.copy(alpha = HELP_ENTRY_DISC_ALPHA)
                    } else {
                        Color.Transparent
                    },
                ),
        ) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = stringResource(R.string.knotwork_settings_hint_open_cd, settingName),
                tint = if (expanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    KnotworkTheme.extended.onSurfaceMuted
                },
                modifier = Modifier.size(HelpEntryGlyph),
            )
        }
    }
}

/**
 * The explanation itself: a tinted panel with a solid accent left edge, drawn
 * in place under the row it explains.
 *
 * Body-size, full-ink text on a tinted surface — deliberately **not** a
 * caption. Small muted type under a label is the slot this app uses for machine
 * state (`NPU · auto`, `v0.9.2`), and a reader learns the slot before reading
 * the sentence; putting meaning there is why the explanations shipped so far
 * went unread.
 *
 * The panel owns its animation **and** its reduced-motion branch, so no caller
 * can get either wrong: expansion runs at `motion.dur2` (180 ms, the
 * `KnotworkA11y` threshold) and collapses to an instant state change when the
 * user has disabled animations. The end state is identical either way.
 *
 * Announced as a polite live region, so a screen reader reads the explanation
 * when it opens without moving focus off the glyph that opened it.
 *
 * @param visible Whether the hint is open; the panel animates its own entry.
 * @param text The explanation. Authored to a 140-character ceiling, which is
 *   what keeps the panel under half the screen at 200 % font scale.
 * @param modifier Layout modifier applied to the animated container.
 * @param link Optional in-app destination; never a URL or a documentation page.
 * @param title Optional lead-in, set apart from [text] in weight. A settings
 *   hint answers a question the user asked by tapping a glyph, so it needs
 *   none; a refusal announces itself unprompted and has to name what is wrong
 *   before explaining it.
 * @param actions Optional verbs offered under the body, left to right. Separate
 *   from [link] because a refusal offers a way out rather than a destination —
 *   copying an address is not navigation — and because it may offer two.
 */
@Composable
fun KnotworkHintPanel(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    link: KnotworkHintLink? = null,
    title: String? = null,
    actions: List<KnotworkHintLink> = emptyList(),
) {
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val duration = KnotworkTheme.motion.dur2
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) {
            EnterTransition.None
        } else {
            expandVertically(tween(duration, easing = KnotworkTheme.motion.easeDecel)) +
                fadeIn(tween(durationMillis = duration / 2, delayMillis = duration / 2))
        },
        exit = if (reducedMotion) {
            ExitTransition.None
        } else {
            shrinkVertically(tween(duration, easing = KnotworkTheme.motion.easeStd)) +
                fadeOut(tween(duration / 2))
        },
        modifier = modifier,
    ) {
        HintPanelSurface(text = text, link = link, title = title, actions = actions)
    }
}

/**
 * Static body of [KnotworkHintPanel]; split out so previews and snapshots can
 * render it directly.
 *
 * @param text The explanation.
 * @param link Optional in-app destination.
 * @param title Optional lead-in above [text].
 * @param actions Optional verbs offered under the body.
 */
@Composable
private fun HintPanelSurface(
    text: String,
    link: KnotworkHintLink?,
    title: String? = null,
    actions: List<KnotworkHintLink> = emptyList(),
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp2)
            .height(IntrinsicSize.Min)
            .clip(KnotworkTheme.shapes.sm)
            .background(accent.copy(alpha = HINT_PANEL_FILL_ALPHA))
            .border(HintPanelHairline, accent.copy(alpha = HINT_PANEL_BORDER_ALPHA), KnotworkTheme.shapes.sm)
            .testTag(SETTINGS_HINT_PANEL_TEST_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(
            modifier = Modifier
                .width(HintPanelAccentEdge)
                .fillMaxHeight()
                .background(accent)
                // The edge is decoration: the panel's own text carries the meaning.
                .clearAndSetSemantics { },
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.padding(
                start = KnotworkTheme.spacing.sp3,
                end = KnotworkTheme.spacing.sp3,
                top = KnotworkTheme.spacing.sp2,
                bottom = KnotworkTheme.spacing.sp2,
            ),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = text,
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (actions.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                    modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1),
                ) {
                    actions.forEach { action ->
                        Text(
                            text = action.label,
                            style = KnotworkTextStyles.BodySm.copy(fontWeight = FontWeight.SemiBold),
                            color = accent,
                            modifier = Modifier
                                .clip(KnotworkTheme.shapes.sm)
                                .clickable(onClick = action.onClick)
                                .padding(vertical = KnotworkTheme.spacing.sp1),
                        )
                    }
                }
            }
            if (link != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                    modifier = Modifier
                        .clip(KnotworkTheme.shapes.sm)
                        .clickable(onClick = link.onClick),
                ) {
                    Icon(
                        imageVector = AppIcons.ArrowR,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(HintPanelLinkGlyph),
                    )
                    Text(
                        text = link.label,
                        style = KnotworkTextStyles.BodySm,
                        color = accent,
                    )
                }
            }
        }
    }
}

/** Test tag of the help-entry glyph, so instrumented tests can assert its touch bounds. */
const val SETTINGS_HELP_ENTRY_TEST_TAG: String = "settings-help-entry"

/** Test tag of the expanded hint panel. */
const val SETTINGS_HINT_PANEL_TEST_TAG: String = "settings-hint-panel"

/**
 * Layout footprint of the entry point.
 *
 * Deliberately smaller than the 48 dp minimum touch target: `IconButton` applies
 * `minimumInteractiveComponentSize`, so the *touchable* bounds stay 48 dp while
 * the space the glyph takes from the label does not. Sizing the layout box at
 * 48 dp instead pushed the glyph onto a second line on any row with a label of
 * ordinary length, which read as a broken row rather than as an affordance.
 * Asserted on `touchBoundsInRoot`, not on the node size.
 */
private val HelpEntryFootprint = 28.dp

/** The tonal disc drawn behind the glyph while its hint is open. */
private val HelpEntryDisc = 26.dp

/** Glyph size: large enough to read as a control, small enough not to compete with the label. */
private val HelpEntryGlyph = 18.dp

/** Hairline around the panel. */
private val HintPanelHairline = 1.dp

/** Solid accent edge that ties the panel to the row above it. */
private val HintPanelAccentEdge = 2.dp

/** Leading glyph of an in-app hint link. */
private val HintPanelLinkGlyph = 14.dp

/** Tonal disc opacity behind an open help glyph. */
private const val HELP_ENTRY_DISC_ALPHA = 0.14f

/** Panel fill opacity over the category surface. */
private const val HINT_PANEL_FILL_ALPHA = 0.07f

/** Panel hairline opacity. */
private const val HINT_PANEL_BORDER_ALPHA = 0.24f

// ─── One-at-a-time hint host ─────────────────────────────────────────────────

/**
 * One row's explanation, resolved for rendering.
 *
 * @property text The explanation itself.
 * @property link Optional in-app destination that continues the same action.
 */
data class SettingsHint(val text: String, val link: KnotworkHintLink? = null)

/**
 * Owns which hint is open on a settings sub-screen, and resolves a row's
 * explanation from its anchor key.
 *
 * **One at a time is the rule that makes inline expansion safe.** Opening a
 * hint closes the one already open, so a category screen is never taller than
 * "itself plus one panel" no matter how many rows carry a hint — the fifteen-row
 * Memory screen included. Without it, inline hints would recreate exactly the
 * wall of text the closed test complained about.
 *
 * Deliberately **not** saved across navigation: a hint is a sentence you read
 * once, not a workspace you arrange, so returning to the screen finds everything
 * closed. (Collapsible row *groups* persist, for the opposite reason.)
 *
 * @param lookup Resolves a row's hint from its anchor key; `null` for the rows
 *   that carry none by criterion.
 */
@Stable
class SettingsHintController(private val lookup: (String) -> SettingsHint?) {

    /** Anchor key of the row whose hint is open, or `null` when none is. */
    var expandedAnchor: String? by mutableStateOf(null)
        private set

    /** Resolves [anchorKey]'s explanation, or `null` when the row carries none. */
    fun hintFor(anchorKey: String?): SettingsHint? = anchorKey?.let(lookup)

    /** Whether [anchorKey]'s hint is the open one. */
    fun isExpanded(anchorKey: String?): Boolean = anchorKey != null && expandedAnchor == anchorKey

    /** Opens [anchorKey]'s hint, closing whichever was open; tapping the open one closes it. */
    fun toggle(anchorKey: String) {
        expandedAnchor = if (expandedAnchor == anchorKey) null else anchorKey
    }
}

/**
 * The hint controller in scope. Defaults to a controller that resolves nothing,
 * so a row rendered outside a settings screen simply shows no glyph.
 */
val LocalSettingsHints: ProvidableCompositionLocal<SettingsHintController> =
    compositionLocalOf { SettingsHintController { null } }

/**
 * The help glyph for a row, or nothing when the row carries no hint.
 *
 * Placed by the row **immediately after its label**, inside the label's own
 * line, so it reads as punctuation of the label rather than as a column of
 * buttons down the trailing edge.
 *
 * The anchor comes from the enclosing [LocalSettingsRowAnchor] rather than a
 * parameter, so a row can never be given a hint belonging to another row, and a
 * control reused outside settings simply renders nothing.
 *
 * @param settingName Row label, spoken as part of the glyph's action.
 * @param modifier Layout modifier applied to the glyph's touch target.
 */
@Composable
fun SettingsHintGlyph(settingName: String, modifier: Modifier = Modifier) {
    val hints = LocalSettingsHints.current
    val anchorKey = LocalSettingsRowAnchor.current ?: return
    if (hints.hintFor(anchorKey) == null) return
    KnotworkHelpEntry(
        settingName = settingName,
        expanded = hints.isExpanded(anchorKey),
        onToggle = { hints.toggle(anchorKey) },
        modifier = modifier,
    )
}

/**
 * The hint panel for a row, or nothing when the row carries no hint.
 *
 * Placed by the row **under itself**, inside the list's own scroll, so opening
 * a hint never moves the row out from under the reader's thumb — which is the
 * whole reason this is not a bottom sheet: a slider is used by moving it and
 * watching what changes, and a modal covers the thing it is explaining.
 *
 * @param modifier Layout modifier applied to the animated container.
 */
@Composable
fun SettingsHintBody(modifier: Modifier = Modifier) {
    val hints = LocalSettingsHints.current
    val anchorKey = LocalSettingsRowAnchor.current
    val hint = hints.hintFor(anchorKey) ?: return
    KnotworkHintPanel(
        visible = hints.isExpanded(anchorKey),
        text = hint.text,
        link = hint.link,
        modifier = modifier,
    )
}

/**
 * Anchor key of the settings row currently being composed, provided by
 * `SettingsAnchor` around every row.
 *
 * Reading the anchor from the tree rather than from a parameter is what keeps a
 * row's hint attached to that row: there is no call site that could pass a
 * mismatched key, and a control reused outside a settings screen (the slider in
 * the node editor, for instance) sees `null` and renders no glyph at all.
 */
val LocalSettingsRowAnchor: ProvidableCompositionLocal<String?> = compositionLocalOf { null }
