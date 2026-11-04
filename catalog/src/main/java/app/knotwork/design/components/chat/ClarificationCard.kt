package app.knotwork.design.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.chips.ChipStyle
import app.knotwork.design.components.chips.KnotworkChip
import app.knotwork.design.components.text.Text
import app.knotwork.design.components.text.WholeText
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkPalette
import app.knotwork.design.tokens.KnotworkTextStyles

/** Card horizontal/vertical padding inside the cream container. */
private val CardPadding = 16.dp

/** Diameter of the leading spinner glyph rendered next to the header label. */
private val HeaderIconSize = 18.dp

/** Minimum height of the free-form input pill — keeps the placeholder vertically centered. */
private val FreeformInputMinHeight = 40.dp

/**
 * Knotwork clarification card — pinned-prompt surface rendered inline in
 * the assistant message stream while the agent is waiting on a typed
 * answer:
 *
 *  - **Container.** Rounded `Accent50` tile so the prompt stands apart
 *    from the surrounding assistant bubble without competing with the
 *    risk-coloured HITL surface. The container colour is theme-fixed
 *    (always cream); body / header text therefore uses fixed dark
 *    palette tones instead of `MaterialTheme.colorScheme.onSurface` —
 *    otherwise the light cream container collides with the white
 *    `onSurface` of the dark theme and the text disappears.
 *  - **Header row.** Spinner-shaped glyph (`AutoAwesome`) tinted brand
 *    primary, followed by the bold "Quick question from the agent"
 *    label.
 *  - **Question.** `BodyBase`, full-width, no clamp.
 *  - **Quick-reply chips.** `FlowRow` of `KnotworkChip(style = Tonal)`.
 *  - **Free-form row.** Pill-shaped (`KnotworkTheme.shapes.full`)
 *    borderless [BasicTextField] with `ImeAction.Send` and a trailing
 *    circular brand action button — same pattern as the chat composer
 *    so all in-chat inputs share one visual contract.
 *
 * Once the user replies the card collapses to the existing
 * `Replied: …` summary so the conversation history reads cleanly.
 *
 * @param model immutable card payload.
 * @param onReply invoked with the chosen quick-reply label.
 * @param modifier optional layout modifier applied to the card root.
 */
@Composable
fun ClarificationCard(model: ClarificationCardModel, onReply: (String) -> Unit, modifier: Modifier = Modifier) {
    if (model.replied != null) {
        RepliedSummary(text = model.replied, modifier = modifier)
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkPalette.Accent50)
            .padding(CardPadding),
    ) {
        HeaderRow()
        // The user answers this question, so all of it shows: when the model's reply does not
        // parse, the question is that whole reply.
        WholeText(
            text = model.question,
            style = KnotworkTextStyles.BodyBase,
            color = ClarificationCardForeground,
        )
        if (model.quickReplies.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            ) {
                model.quickReplies.forEach { reply ->
                    KnotworkChip(
                        label = reply,
                        onClick = { onReply(reply) },
                        style = ChipStyle.Tonal,
                    )
                }
            }
        }
        val placeholder = model.freeformPlaceholder
            ?: stringResource(R.string.knotwork_clarification_freeform_default)
        FreeformRow(placeholder = placeholder, onSubmit = onReply)
    }
}

/**
 * Foreground colour for body / header text rendered on the fixed-cream
 * `Accent50` container. Theme-fixed because the container itself does not
 * flip with the system theme — using `MaterialTheme.colorScheme.onSurface`
 * would blow out the contrast under dark theme (white-on-cream).
 */
private val ClarificationCardForeground = KnotworkPalette.Accent800

/**
 * Free-form input row — pill-shaped borderless text field + circular
 * brand-color action button. Mirrors `ChatComposer` so the user sees one
 * visual idiom for any in-chat input affordance.
 */
@Composable
private fun FreeformRow(placeholder: String, onSubmit: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    val canSubmit = draft.isNotBlank()
    val submit: () -> Unit = {
        if (canSubmit) {
            onSubmit(draft.trim())
            draft = ""
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .clip(KnotworkTheme.shapes.full)
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(
                start = KnotworkTheme.spacing.sp4,
                end = KnotworkTheme.spacing.sp1,
                top = KnotworkTheme.spacing.sp1,
                bottom = KnotworkTheme.spacing.sp1,
            ),
    ) {
        val textStyle: TextStyle = KnotworkTextStyles.BodyBase.copy(
            color = ClarificationCardForeground,
        )
        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = FreeformInputMinHeight)
                .wrapContentHeight(Alignment.CenterVertically),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = KnotworkTextStyles.BodyBase,
                            color = KnotworkTheme.extended.onSurfaceDim,
                        )
                    }
                    innerTextField()
                }
            },
        )
        ClarificationSendButton(enabled = canSubmit, onClick = submit)
    }
}

/** Diameter of the circular send action button inside the clarification pill. */
private val ClarificationActionButtonSize = 48.dp

/** Size of the glyph rendered inside the circular action button. */
private val ClarificationActionIconSize = 20.dp

/**
 * Circular filled brand-color send button used inside the clarification
 * free-form row. Mirrors the composer's `ComposerActionButton` so the two
 * input pills behave identically; disabled state desaturates to
 * `extended.surface3` / `extended.onSurfaceDim`.
 */
@Composable
private fun ClarificationSendButton(enabled: Boolean, onClick: () -> Unit) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        KnotworkTheme.extended.surface3
    }
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        KnotworkTheme.extended.onSurfaceDim
    }
    val interactionSource = remember { MutableInteractionSource() }
    val description = stringResource(R.string.knotwork_clarification_send)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(ClarificationActionButtonSize)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color = containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Icon(
            imageVector = AppIcons.ArrowUpLine,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ClarificationActionIconSize),
        )
    }
}

/** Spinner glyph + header label aligned at the top of the card. */
@Composable
private fun HeaderRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(HeaderIconSize),
        ) {
            Icon(
                imageVector = AppIcons.Spark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(HeaderIconSize),
            )
        }
        Text(
            text = stringResource(R.string.knotwork_clarification_header),
            style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
            color = ClarificationCardForeground,
        )
    }
}

/** Post-reply summary — `"Replied: …"` with a check glyph, no input affordance. */
@Composable
private fun RepliedSummary(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = modifier.padding(vertical = KnotworkTheme.spacing.sp1),
    ) {
        Icon(
            imageVector = AppIcons.Check,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(KnotworkTheme.spacing.sp4),
        )
        Text(
            text = stringResource(R.string.knotwork_clarification_replied, text),
            style = KnotworkTextStyles.BodySm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
    }
}
