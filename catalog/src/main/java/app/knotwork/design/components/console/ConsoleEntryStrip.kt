package app.knotwork.design.components.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Strip height. One line, at every font scale — only the status zone gives ground. */
private val StripHeight = 40.dp

/** Width of the hairline separating the fixed label from the live status. */
private val HairlineWidth = 1.dp

/** Opacity of the hairline against the console foreground. */
private const val HAIRLINE_ALPHA = 0.26f

/** Chevron glyph size. */
private val ChevronSize = 18.dp

/** Rotation applied to the chevron when the console is open. */
private const val CHEVRON_OPEN_DEGREES = 180f

/**
 * The console's own entry point: a dark, one-line strip that says what it is,
 * shows what the agent is doing, and points at where it goes.
 *
 * ### Why it says a word
 *
 * The strip was already a button — `Role.Button`, a content description, the
 * whole TalkBack contract — and it still could not be found by the first
 * outsider to use the app. The line that settled the design is not "I didn't
 * know I could tap it" but what he said *after* being told where to tap:
 * still unclear what the button was. A leading glyph would not have answered
 * that; a name does. So the strip carries the literal word `console`, and the
 * chevron carries the direction.
 *
 * A leading `AppIcons.Terminal` was considered and dropped: the status line
 * already opens with the mono tag `[NODE]`, so a glyph puts a third mark on the
 * left edge, and it spends horizontal room on a line that has to ellipsize
 * gracefully at 200 % font scale.
 *
 * ### One element, two positions
 *
 * The same composable renders in the chat body (closed, chevron up, rounded)
 * and as the console sheet's own header (open, chevron down, squared). It is
 * not duplicated and it does not disappear when the console opens — which is
 * what stops "where did the strip go" from being the next question.
 *
 * ### Three zones, one line
 *
 * 1. **label** — `console`, mono, accent ink. Fixed, never truncates.
 * 2. **status** — the live agent line. Flexes and ellipsizes; its tag is
 *    coloured by state, and the tag is never the only signal because the words
 *    carry the state too.
 * 3. **chevron** — up when closed, down when open. Fixed.
 *
 * @param statusLine the live agent-status line, e.g. `"[NODE]  idle · ready"`.
 *        Rendered verbatim; a leading `[TAG]` segment is tinted by the caller's
 *        text, not re-parsed here.
 * @param open whether the console pane is currently showing. Drives the chevron
 *        direction, the corner treatment and the content description.
 * @param onClick invoked on tap — opens the console when closed, closes it when
 *        open.
 * @param modifier layout modifier applied to the strip.
 * @param leadingStatus optional slot rendered before [statusLine], used by the
 *        host to tint the `[TAG]` segment separately.
 * @param trailingStatus optional slot rendered after [statusLine] — the
 *        generating loader lives here.
 */
@Composable
fun ConsoleEntryStrip(
    statusLine: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingStatus: (@Composable () -> Unit)? = null,
    trailingStatus: (@Composable () -> Unit)? = null,
) {
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val chevronRotation by animateFloatAsState(
        targetValue = if (open) CHEVRON_OPEN_DEGREES else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else KnotworkTheme.motion.dur3,
            easing = KnotworkTheme.motion.easeEmph,
        ),
        label = "consoleStripChevron",
    )
    val description = if (open) {
        stringResource(R.string.knotwork_console_strip_close_cd)
    } else {
        // The description speaks the state, so TalkBack gets what the eye gets.
        stringResource(R.string.knotwork_console_strip_open_cd, statusLine)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .fillMaxWidth()
            .height(StripHeight)
            // Squared as the sheet header, rounded as a control in the chat
            // body: the same element, shaped by where it currently sits.
            .then(if (open) Modifier else Modifier.clip(KnotworkTheme.shapes.sm))
            .background(color = KnotworkTheme.extended.consoleBg)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = KnotworkTheme.spacing.sp2),
    ) {
        Text(
            text = stringResource(R.string.knotwork_console_strip_label),
            style = KnotworkTextStyles.MonoSm.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .width(HairlineWidth)
                .fillMaxHeight()
                .padding(vertical = KnotworkTheme.spacing.sp2)
                .background(color = KnotworkTheme.extended.consoleFg.copy(alpha = HAIRLINE_ALPHA)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier.weight(1f),
        ) {
            if (leadingStatus != null) leadingStatus()
            // Start ellipsis. The line is `state · where · N tok`, and the two
            // ends are not equally worth keeping: the state is already visible
            // in the chat bubble above, while the backend and the token count
            // exist nowhere else. Trailing ellipsis cut both; middle ellipsis
            // then ate the backend from the inside — `generating (…U) · 56 tok`
            // — because the token count grows and pushes the cut leftwards.
            // Dropping the head keeps the whole tail at any count.
            Text(
                text = statusLine,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.consoleFg,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailingStatus != null) trailingStatus()
        }
        Icon(
            imageVector = AppIcons.ArrowUp,
            contentDescription = null,
            tint = KnotworkTheme.extended.consoleFg,
            modifier = Modifier.size(ChevronSize).rotate(chevronRotation),
        )
    }
}
