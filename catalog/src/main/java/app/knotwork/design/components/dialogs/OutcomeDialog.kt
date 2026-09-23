package app.knotwork.design.components.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Size of the leading glyph above an outcome dialog's headline. */
private val OutcomeIconSize = 28.dp

/**
 * How many named items an outcome dialog spells out before summarising.
 *
 * Public because the caller has to pre-resolve the "and N more" line and
 * therefore needs the same number this component cuts at — two independent
 * constants would drift into a dialog that hides entries without saying so.
 */
const val MAX_NAMED_LIST_ITEMS = 8

/**
 * What kind of thing the dialog is saying, which fixes its glyph and colour.
 *
 * The distinction that matters is [GUARD] versus [ERROR]. A limit that did
 * its job is not a failure: when a file asks for something it is not allowed
 * to have and the app refuses, the import still succeeded. Painting that red
 * would teach the user that a working safeguard is a broken app — so [GUARD]
 * draws the shield already used elsewhere for "a limit did its job", and red
 * is reserved for [ERROR], where nothing happened at all.
 */
enum class OutcomeTone {
    /** Something completed with a caveat. Muted info glyph. */
    INFO,

    /** A safeguard held. Shield, deliberately not red. */
    GUARD,

    /** Nothing happened. The only red tone. */
    ERROR,

    /** A question for the user. No glyph, left-aligned headline. */
    QUESTION,
}

/**
 * How prominently an action reads.
 */
enum class OutcomeActionEmphasis {
    /** Ordinary text button. */
    NORMAL,

    /** The action that loses nothing — drawn bold. */
    EMPHASISED,
}

/**
 * One button on an outcome dialog.
 *
 * @property label Button text.
 * @property onClick Invoked on tap.
 * @property emphasis Whether this is the action the user should reach for.
 */
data class OutcomeAction(
    val label: String,
    val onClick: () -> Unit,
    val emphasis: OutcomeActionEmphasis = OutcomeActionEmphasis.NORMAL,
)

/**
 * A named list of things that were left out, rendered under a dialog body.
 *
 * @property heading Section label (e.g. "Left out") — or, on the pipeline
 *   importer, a full sentence naming what is about to be lost. Rendered at
 *   full contrast for that reason.
 * @property items The entries, in the order the reader produced them.
 * @property moreLabel Summary line shown when [items] exceeds the display
 *   cap. Pre-resolved by the caller — which knows how many entries there are
 *   and can therefore pick the plural form — rather than resolved here,
 *   because this data class is not a composition scope. `null` hides the
 *   line, and the caller is responsible for its own count arithmetic
 *   matching [MAX_NAMED_LIST_ITEMS].
 */
data class OutcomeNamedList(val heading: String, val items: List<String>, val moreLabel: String? = null)

/**
 * The one dialog shape every "here is what happened" moment uses.
 *
 * Two layouts in one component, chosen by [tone]:
 *
 * - **An outcome** ([OutcomeTone.INFO], [OutcomeTone.GUARD],
 *   [OutcomeTone.ERROR]) — glyph above a centred headline. It reads as
 *   *"here is what happened, read it"*, and it carries a single dismissing
 *   action.
 * - **A question** ([OutcomeTone.QUESTION]) — no glyph, left-aligned
 *   headline, two or three actions. It reads as *"you have to decide"*.
 *
 * The body scrolls, so at large font scales the buttons never leave the
 * screen. The headline is the accessibility pane title, so a screen reader
 * announces what happened before any detail.
 *
 * @param tone Which of the two layouts, and which glyph and colour.
 * @param headline The one sentence stating the outcome or the question.
 * @param body Supporting text. Optional — a headline that already says
 *   everything does not need a paragraph repeating it.
 * @param namedList Optional list of what was left out.
 * @param confirm The primary action. Always present: every dialog has a way
 *   out that is not the back gesture.
 * @param neutral Optional middle action (the "Replace" of a three-button
 *   question).
 * @param dismiss Optional cancelling action, rendered in the dismiss slot.
 * @param onDismissRequest Invoked on scrim tap / back. Should do what
 *   [dismiss] does, or nothing when the dialog only informs.
 * @param modifier Layout modifier applied to the dialog.
 */
@Composable
@Suppress("LongParameterList") // Documented public API; one dialog family, shared by the importers.
fun OutcomeDialog(
    tone: OutcomeTone,
    headline: String,
    confirm: OutcomeAction,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    namedList: OutcomeNamedList? = null,
    neutral: OutcomeAction? = null,
    dismiss: OutcomeAction? = null,
) {
    val centred = tone != OutcomeTone.QUESTION
    AlertDialog(
        modifier = modifier.semantics { paneTitle = headline },
        onDismissRequest = onDismissRequest,
        icon = glyphFor(tone)?.let { (icon, tint) ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(OutcomeIconSize),
                )
            }
        },
        title = {
            Text(
                text = headline,
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
                // The body is the only part that can grow without bound (a
                // long name, a 200 % font scale, a list of refused tools), so
                // it is the part that scrolls. The action row stays put.
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (body != null) {
                    Text(
                        text = body,
                        style = KnotworkTextStyles.BodyBase,
                        color = KnotworkTheme.extended.onSurface2,
                        textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                    )
                }
                if (namedList != null) {
                    NamedList(list = namedList)
                }
            }
        },
        confirmButton = { ActionButton(action = confirm) },
        dismissButton = when {
            // Material's AlertDialog offers two slots for up to three
            // actions, so the middle one rides along with the dismiss.
            neutral != null && dismiss != null -> {
                {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
                    ) {
                        ActionButton(action = dismiss)
                        ActionButton(action = neutral)
                    }
                }
            }

            dismiss != null -> {
                { ActionButton(action = dismiss) }
            }

            neutral != null -> {
                { ActionButton(action = neutral) }
            }

            else -> null
        },
    )
}

/**
 * Renders the "left out" block: a heading, the entries, and a summary line
 * when there are more than the cap.
 *
 * Capped because the entries can come from a file this app did not write —
 * an unbounded list turns a dialog into a scroll the user dismisses without
 * reading, which is the same as not telling them.
 */
@Composable
private fun NamedList(list: OutcomeNamedList) {
    if (list.items.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // `BodySm` at full contrast, not a muted caption. The heading is a
        // label on this surface ("Left out"), but the pipeline importer —
        // which shares this component — puts a full sentence here warning
        // that settings are about to be lost. Shrinking and dimming that is
        // exactly the wrong direction for the one line that says data goes
        // away.
        Text(
            text = list.heading,
            style = KnotworkTextStyles.BodySm,
            color = MaterialTheme.colorScheme.onSurface,
        )
        list.items.take(MAX_NAMED_LIST_ITEMS).forEach { item ->
            Text(text = "— $item", style = KnotworkTextStyles.MonoSm, color = MaterialTheme.colorScheme.onSurface)
        }
        if (list.items.size > MAX_NAMED_LIST_ITEMS && list.moreLabel != null) {
            Text(
                text = list.moreLabel,
                style = KnotworkTextStyles.Caption,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

/** One dialog action, bolded when it is the one that loses nothing. */
@Composable
private fun ActionButton(action: OutcomeAction) {
    TextButton(onClick = action.onClick) {
        Text(
            text = action.label,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = if (action.emphasis == OutcomeActionEmphasis.EMPHASISED) FontWeight.Bold else null,
        )
    }
}

/** The glyph and tint for a tone, or `null` when the tone draws none. */
@Composable
private fun glyphFor(tone: OutcomeTone): Pair<ImageVector, Color>? = when (tone) {
    OutcomeTone.INFO -> AppIcons.Info to KnotworkTheme.extended.onSurfaceMuted
    OutcomeTone.GUARD -> AppIcons.Shield to KnotworkTheme.extended.signalWarn
    OutcomeTone.ERROR -> AppIcons.AlertCircle to KnotworkTheme.extended.signalError
    OutcomeTone.QUESTION -> null
}
