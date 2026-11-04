package app.knotwork.design.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import app.knotwork.design.R
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import java.text.NumberFormat

/** Height of the fade over the end of a preview: two and a half lines of body text, so it grows with the font. */
private val FadeHeight = 56.sp

/** Space between the preview's text and the action under it. */
private val ActionTopGap = 4.dp

/** Smallest height of the action, a touch target. */
private val ActionMinHeight = 48.dp

/** The action's arrow, and the box it sits in. */
private val ArrowSize = 18.dp
private val ArrowBox = 36.dp

/** Test tag of the *Read whole message* action under a preview. */
const val CHAT_READ_WHOLE_TAG: String = "chat_read_whole"

/**
 * A long message's [ChatMessagePreview] inside its bubble: the preview's text, a fade
 * over its last lines into the bubble's own colour, then *Read whole message · N%
 * shown* →, which calls [onReadWhole]. The footer under the bubble stays as it is.
 *
 * The fade is decoration and is not announced. The action is one TalkBack stop, a
 * button that reads its label and the share shown. It reaches the bubble's sides, so
 * its ripple fills the bubble's width.
 *
 * @param role whose bubble it is: the user's draws the action in its own text colour,
 *   since the primary colour is too faint on it.
 * @param preview the preview the text was drawn from, for the share it shows.
 * @param onReadWhole opens the whole message.
 * @param text the preview's text, drawn as the bubble draws a message.
 */
@Composable
internal fun LongMessagePreview(
    role: ChatRole,
    preview: ChatMessagePreview,
    onReadWhole: () -> Unit,
    text: @Composable () -> Unit,
) {
    val background = chatBubbleBackground(role)
    val fade = with(LocalDensity.current) { FadeHeight.toDp() }
    Column {
        Box {
            text()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(fade)
                    .background(Brush.verticalGradient(listOf(background.copy(alpha = 0f), background)))
                    .clearAndSetSemantics { },
            )
        }
        ReadWholeAction(role = role, shownPercent = preview.shownPercent, onClick = onReadWhole)
    }
}

/** *Read whole message*, the share shown, and an arrow: one button, as wide as the bubble. */
@Composable
private fun ReadWholeAction(role: ChatRole, shownPercent: Int, onClick: () -> Unit) {
    val user = role == ChatRole.User
    val ink = if (user) KnotworkTheme.extended.chatUserFg else MaterialTheme.colorScheme.primary
    val muted = if (user) KnotworkTheme.extended.chatUserFg else KnotworkTheme.extended.onSurfaceMuted
    val bleed = KnotworkTheme.spacing.sp3
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = ActionTopGap)
            .bleedHorizontally(bleed)
            .fillMaxWidth()
            .heightIn(min = ActionMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = bleed, end = KnotworkTheme.spacing.sp1)
            .testTag(CHAT_READ_WHOLE_TAG),
    ) {
        // The label and the share wrap at a large font; the arrow keeps its place.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.knotwork_chat_long_read_whole),
                style = KnotworkTextStyles.ButtonLabel,
                color = ink,
            )
            Text(text = shownLabel(shownPercent), style = KnotworkTextStyles.BodySm, color = muted)
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ArrowBox)) {
            Icon(
                imageVector = AppIcons.ArrowR,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(ArrowSize),
            )
        }
    }
}

/** *35% shown*, the percent in the locale's format; *Less than 1% shown* under 1 %. */
@Composable
private fun shownLabel(shownPercent: Int): String {
    val percent = NumberFormat.getPercentInstance()
    return if (shownPercent < 1) {
        stringResource(R.string.knotwork_chat_long_shown_less, percent.format(ONE_PERCENT))
    } else {
        stringResource(R.string.knotwork_chat_long_shown, percent.format(shownPercent / PERCENT_IN_WHOLE))
    }
}

/** One percent, and percent in one whole, as fractions [NumberFormat.getPercentInstance] formats. */
private const val ONE_PERCENT = 0.01
private const val PERCENT_IN_WHOLE = 100.0

/** Widens the element by [amount] on each side past its parent's padding, keeping its content where it was. */
private fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * px))
    layout(placeable.width - 2 * px, placeable.height) { placeable.place(-px, 0) }
}
