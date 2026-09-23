package app.knotwork.design.components.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Fraction of the screen width a user / assistant bubble may consume horizontally. */
private const val USER_BUBBLE_MAX_WIDTH_FRACTION = 0.80f

/** Maximum width of an image-bubble thumbnail's bounding box. */
private val IMAGE_BUBBLE_MAX_WIDTH = 220.dp

/** Maximum height of an image-bubble thumbnail's bounding box. */
private val IMAGE_BUBBLE_MAX_HEIGHT = 260.dp

/** Insets reserved opposite the assistant bubble so it never reaches the screen edge. */
private val AssistantBubbleTrailingInset = 64.dp

/** Press-down scale target (chat surface long-press). */
private const val LONG_PRESS_SCALE_TARGET = 0.98f

/** Long-press scale animation duration in ms. */
private const val LONG_PRESS_SCALE_DURATION_MS = 60

/**
 * Root chat-message renderer. Dispatches on [content] to one of the surface
 * variants for the chat surface, and
 * applies the bubble chrome (background colour, shape, alignment, max-width)
 * matching [role].
 *
 * Long-press on the bubble surfaces a [ChatContextAction] dropdown (copy /
 * re-run / rate). The press itself scales the bubble to 0.98 over 60 ms and
 * fires a `HapticFeedbackType.LongPress`. Under reduced motion
 * (`KnotworkTheme.a11y.reducedMotion()`) the scale is skipped.
 *
 * The composable is stateless — typed-confirm input, tool retries, voice
 * recording, and console interactions are owned by the screen-level
 * ViewModel. Callbacks fire when the user requests an action; nothing
 * happens by default.
 *
 * @param role conversational role; drives bubble palette + alignment.
 * @param content sealed body; see [ChatContent] for the variant matrix.
 * @param metadata footer payload (timestamp, model, tokens, status).
 * @param modifier optional layout modifier applied to the message root.
 * @param onContextAction non-null to enable the long-press context menu.
 * `null` disables long-press entirely (useful for `System` messages and
 * transient placeholder rows).
 * @param onErrorRetry invoked when an [ChatContent.Error] tile's retry CTA
 * is tapped — falls back to `content.retry` when this parameter is `null`.
 * @param onAllowOnce invoked when a [ChatContent.Confirmation] card's
 * "Allow once" CTA is tapped (the screen owns enable-state via
 * [allowOnceEnabled]).
 * @param onAllowAlways invoked when the "Always allow" chip is tapped on a
 * Sensitive confirmation. `null` hides the chip.
 * @param onReject invoked when the confirmation's "Reject" CTA is tapped.
 * @param pendingTypedConfirm typed-confirm state for Destructive
 * confirmations; empty string until the user types.
 * @param onTypedConfirmChange propagates user input to the screen for the
 * Destructive typed-confirm field.
 * @param allowOnceEnabled gates the Allow CTA. The screen decides — typically
 * `true` for Sensitive, `pendingTypedConfirm.trim().equals("yes", true)`
 * for Destructive, `true` for Readonly (auto-allowed display state).
 * @param onClarificationReply invoked when the user taps a quick-reply chip
 * or submits the free-form field on a [ChatContent.Clarification] card.
 * @param onRunResume invoked when the user taps the Resume CTA on a
 * [ChatContent.RunInterrupted] card.
 * @param onRunDiscard invoked when the user taps the Discard CTA on a
 * [ChatContent.RunInterrupted] card.
 * @param onRunContinue invoked when the user taps the continue CTA on a
 * [ChatContent.RunCeilingPause] card, granting the run one more portion of the
 * limit it reached.
 * @param onRunStop invoked when the user taps the stop CTA on a
 * [ChatContent.RunCeilingPause] card.
 */
@Composable
@Suppress("LongParameterList") // Public chat-message API — collapsing into a single config object hides intent.
fun ChatMessage(
    role: ChatRole,
    content: ChatContent,
    metadata: ChatMetadata,
    modifier: Modifier = Modifier,
    onContextAction: ((ChatContextAction) -> Unit)? = null,
    onErrorRetry: (() -> Unit)? = null,
    onAllowOnce: () -> Unit = {},
    onAllowAlways: (() -> Unit)? = null,
    onReject: () -> Unit = {},
    pendingTypedConfirm: String = "",
    onTypedConfirmChange: (String) -> Unit = {},
    allowOnceEnabled: Boolean = true,
    onClarificationReply: (String) -> Unit = {},
    onRunResume: () -> Unit = {},
    onRunDiscard: () -> Unit = {},
    onRunContinue: () -> Unit = {},
    onRunStop: () -> Unit = {},
    markdownRenderer: (@Composable (String) -> Unit)? = null,
) {
    when (role) {
        ChatRole.System -> SystemMessage(content = content, metadata = metadata, modifier = modifier)
        ChatRole.User -> BubbleMessage(
            role = role,
            content = content,
            metadata = metadata,
            onContextAction = onContextAction,
            onErrorRetry = onErrorRetry,
            onAllowOnce = onAllowOnce,
            onAllowAlways = onAllowAlways,
            onReject = onReject,
            pendingTypedConfirm = pendingTypedConfirm,
            onTypedConfirmChange = onTypedConfirmChange,
            allowOnceEnabled = allowOnceEnabled,
            onClarificationReply = onClarificationReply,
            onRunResume = onRunResume,
            onRunDiscard = onRunDiscard,
            onRunContinue = onRunContinue,
            onRunStop = onRunStop,
            markdownRenderer = markdownRenderer,
            modifier = modifier,
        )
        ChatRole.Assistant, ChatRole.Tool -> BubbleMessage(
            role = role,
            content = content,
            metadata = metadata,
            onContextAction = onContextAction,
            onErrorRetry = onErrorRetry,
            onAllowOnce = onAllowOnce,
            onAllowAlways = onAllowAlways,
            onReject = onReject,
            pendingTypedConfirm = pendingTypedConfirm,
            onTypedConfirmChange = onTypedConfirmChange,
            allowOnceEnabled = allowOnceEnabled,
            onClarificationReply = onClarificationReply,
            onRunResume = onRunResume,
            onRunDiscard = onRunDiscard,
            onRunContinue = onRunContinue,
            onRunStop = onRunStop,
            markdownRenderer = markdownRenderer,
            modifier = modifier,
        )
    }
}

/** Bubble-style renderer for [ChatRole.User], [ChatRole.Assistant], and [ChatRole.Tool]. */
@Composable
@Suppress("LongParameterList")
private fun BubbleMessage(
    role: ChatRole,
    content: ChatContent,
    metadata: ChatMetadata,
    onContextAction: ((ChatContextAction) -> Unit)?,
    onErrorRetry: (() -> Unit)?,
    onAllowOnce: () -> Unit,
    onAllowAlways: (() -> Unit)?,
    onReject: () -> Unit,
    pendingTypedConfirm: String,
    onTypedConfirmChange: (String) -> Unit,
    allowOnceEnabled: Boolean,
    onClarificationReply: (String) -> Unit,
    onRunResume: () -> Unit,
    onRunDiscard: () -> Unit,
    onRunContinue: () -> Unit,
    onRunStop: () -> Unit,
    markdownRenderer: (@Composable (String) -> Unit)?,
    modifier: Modifier,
) {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val screenWidthDp = with(density) { containerSize.width.toDp() }
    val maxBubbleWidth = when (role) {
        ChatRole.User -> screenWidthDp * USER_BUBBLE_MAX_WIDTH_FRACTION
        else -> screenWidthDp - AssistantBubbleTrailingInset
    }
    val rowAlignment = if (role == ChatRole.User) Arrangement.End else Arrangement.Start
    Row(
        horizontalArrangement = rowAlignment,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            horizontalAlignment = if (role == ChatRole.User) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = maxBubbleWidth),
        ) {
            BubbleBody(
                role = role,
                content = content,
                onContextAction = onContextAction,
                onErrorRetry = onErrorRetry,
                onAllowOnce = onAllowOnce,
                onAllowAlways = onAllowAlways,
                onReject = onReject,
                pendingTypedConfirm = pendingTypedConfirm,
                onTypedConfirmChange = onTypedConfirmChange,
                allowOnceEnabled = allowOnceEnabled,
                onClarificationReply = onClarificationReply,
                onRunResume = onRunResume,
                onRunDiscard = onRunDiscard,
                onRunContinue = onRunContinue,
                onRunStop = onRunStop,
                markdownRenderer = markdownRenderer,
            )
            // Clarification / HITL confirmation cards are self-contained
            // panels with their own internal status indicators; rendering
            // the standard timestamp + model footer underneath them would
            // clash. Every other variant keeps the footer.
            if (content !is ChatContent.Clarification &&
                content !is ChatContent.Confirmation &&
                content !is ChatContent.RunInterrupted &&
                content !is ChatContent.RunCeilingPause
            ) {
                BubbleFooter(role = role, metadata = metadata)
            }
        }
    }
}

/** Footer row beneath each bubble — timestamp + optional model + status glyph. */
@Composable
private fun BubbleFooter(role: ChatRole, metadata: ChatMetadata) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier.padding(
            top = KnotworkTheme.spacing.sp1,
            start = KnotworkTheme.spacing.sp2,
            end = KnotworkTheme.spacing.sp2,
        ),
    ) {
        Text(
            text = metadata.timestamp,
            style = KnotworkTextStyles.Caption,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        if (role == ChatRole.Assistant && metadata.model != null) {
            Text(
                text = "·",
                style = KnotworkTextStyles.Caption,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            Text(
                text = metadata.model,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (metadata.tokens != null) {
            Text(
                text = stringResource(R.string.knotwork_chat_message_token_count, metadata.tokens),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
        StatusGlyph(status = metadata.status)
    }
}

/** Trailing micro-glyph reflecting [ChatMessageStatus]. */
@Composable
private fun StatusGlyph(status: ChatMessageStatus) {
    val (icon, tint, descriptionRes) = when (status) {
        ChatMessageStatus.Pending -> Triple(
            AppIcons.Hourglass,
            KnotworkTheme.extended.onSurfaceMuted,
            R.string.knotwork_chat_message_status_pending,
        )
        ChatMessageStatus.Sent -> Triple(
            AppIcons.Check,
            KnotworkTheme.extended.onSurfaceMuted,
            R.string.knotwork_chat_message_status_sent,
        )
        ChatMessageStatus.Failed -> Triple(
            AppIcons.AlertCircle,
            KnotworkTheme.extended.signalError,
            R.string.knotwork_chat_message_status_failed,
        )
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descriptionRes),
        tint = tint,
        modifier = Modifier.size(KnotworkTheme.spacing.sp3),
    )
}

/** Dispatch over [ChatContent] variants, applying the bubble chrome where appropriate. */
@Composable
@Suppress("LongParameterList", "LongMethod")
private fun BubbleBody(
    role: ChatRole,
    content: ChatContent,
    onContextAction: ((ChatContextAction) -> Unit)?,
    onErrorRetry: (() -> Unit)?,
    onAllowOnce: () -> Unit,
    onAllowAlways: (() -> Unit)?,
    onReject: () -> Unit,
    pendingTypedConfirm: String,
    onTypedConfirmChange: (String) -> Unit,
    allowOnceEnabled: Boolean,
    onClarificationReply: (String) -> Unit,
    onRunResume: () -> Unit,
    onRunDiscard: () -> Unit,
    onRunContinue: () -> Unit,
    onRunStop: () -> Unit,
    markdownRenderer: (@Composable (String) -> Unit)?,
) {
    when (content) {
        is ChatContent.Text -> TextBubble(
            role = role,
            text = content.text,
            onContextAction = onContextAction,
        )
        is ChatContent.Markdown -> MarkdownBubble(
            role = role,
            source = content.source,
            renderer = markdownRenderer,
            onContextAction = onContextAction,
        )
        is ChatContent.Error -> ErrorTile(
            message = content.message,
            onRetry = onErrorRetry ?: content.retry,
        )
        is ChatContent.ToolCall -> ToolCallTile(content = content)
        is ChatContent.Confirmation -> HitlConfirmationCard(
            model = content.model,
            pendingTypedConfirm = pendingTypedConfirm,
            onTypedConfirmChange = onTypedConfirmChange,
            allowOnceEnabled = allowOnceEnabled,
            onAllowOnce = onAllowOnce,
            onAllowAlways = onAllowAlways,
            onReject = onReject,
        )
        is ChatContent.Clarification -> ClarificationCard(
            model = content.model,
            onReply = onClarificationReply,
        )
        is ChatContent.RunInterrupted -> InterruptedRunCard(
            model = content.model,
            onResume = onRunResume,
            onDiscard = onRunDiscard,
        )
        is ChatContent.RunCeilingPause -> RunCeilingPauseCard(
            model = content.model,
            onContinue = onRunContinue,
            onStop = onRunStop,
        )
        is ChatContent.Image -> ImageBubble(
            role = role,
            content = content,
            onContextAction = onContextAction,
        )
    }
}

/**
 * Image bubble — an attached image rendered inside the user bubble, aspect-fit
 * inside a bounding box (never square-cropped), with an optional caption below.
 * Tapping the thumbnail opens the full-screen viewer via [ChatContent.Image.onTap].
 */
@Composable
private fun ImageBubble(role: ChatRole, content: ChatContent.Image, onContextAction: ((ChatContextAction) -> Unit)?) {
    val textColor = chatBubbleTextColor(role)
    ChatBubbleChrome(role = role, onContextAction = onContextAction) {
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
            val ratio = content.aspectRatio.takeIf { it > 0f } ?: 1f
            ImageThumbnail(
                model = content.model,
                contentDescription = stringResource(R.string.knotwork_attachment_thumbnail),
                contentScale = ContentScale.Fit,
                shape = KnotworkTheme.shapes.md,
                onClick = content.onTap,
                modifier = Modifier
                    .widthIn(max = IMAGE_BUBBLE_MAX_WIDTH)
                    .heightIn(max = IMAGE_BUBBLE_MAX_HEIGHT)
                    .aspectRatio(ratio),
            )
            if (!content.caption.isNullOrEmpty()) {
                Text(text = content.caption, style = KnotworkTextStyles.BodyBase, color = textColor)
            }
        }
    }
}

/** Text bubble + long-press context menu — the bread-and-butter chat-message variant. */
@Composable
private fun TextBubble(role: ChatRole, text: String, onContextAction: ((ChatContextAction) -> Unit)?) {
    val textColor = chatBubbleTextColor(role)
    ChatBubbleChrome(role = role, onContextAction = onContextAction) {
        Text(text = text, style = KnotworkTextStyles.BodyBase, color = textColor)
    }
}

/**
 * Renders [ChatContent.Markdown] through the host-supplied [renderer]. When
 * [renderer] is `null` the catalog falls back to plain text so the bubble
 * still reads correctly without forcing every catalog consumer to pull in
 * a markdown library.
 */
@Composable
private fun MarkdownBubble(
    role: ChatRole,
    source: String,
    renderer: (@Composable (String) -> Unit)?,
    onContextAction: ((ChatContextAction) -> Unit)?,
) {
    if (renderer == null) {
        TextBubble(role = role, text = source, onContextAction = onContextAction)
        return
    }
    ChatBubbleChrome(role = role, onContextAction = onContextAction) {
        renderer(source)
    }
}

/** Resolves the per-role text colour used by [TextBubble] — paired with the
 * matching bubble background in [ChatBubbleChrome] (chat pairs). */
@Composable
private fun chatBubbleTextColor(role: ChatRole): androidx.compose.ui.graphics.Color = when (role) {
    ChatRole.User -> KnotworkTheme.extended.chatUserFg
    ChatRole.Tool -> KnotworkTheme.extended.chatToolFg
    else -> KnotworkTheme.extended.chatAgentFg
}

/**
 * Bubble chrome shared by [TextBubble] and [MarkdownBubble] — handles the
 * role-keyed background / shape, the long-press press-scale animation,
 * and the context-action [DropdownMenu]. The bubble body is supplied as a
 * composable slot so plain text and rendered-markdown variants stay in
 * lockstep on chrome changes.
 */
@Composable
private fun ChatBubbleChrome(
    role: ChatRole,
    onContextAction: ((ChatContextAction) -> Unit)?,
    body: @Composable () -> Unit,
) {
    val (bubbleColor, shape) = when (role) {
        ChatRole.User -> KnotworkTheme.extended.chatUserBg to ChatBubbleShapes.User
        ChatRole.Tool -> KnotworkTheme.extended.chatToolBg to ChatBubbleShapes.Assistant
        else -> KnotworkTheme.extended.chatAgentBg to ChatBubbleShapes.Assistant
    }
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    var menuExpanded by remember { mutableStateOf(false) }

    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (menuExpanded && !reducedMotion) LONG_PRESS_SCALE_TARGET else 1f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 0 else LONG_PRESS_SCALE_DURATION_MS,
        ),
        label = "chat_message_press_scale",
    )

    val clickableModifier = if (onContextAction != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = true,
            onClick = {},
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                menuExpanded = true
            },
        )
    } else {
        Modifier
    }

    Box {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(color = bubbleColor)
                .then(clickableModifier)
                .padding(horizontal = KnotworkTheme.spacing.sp3, vertical = KnotworkTheme.spacing.sp2),
        ) {
            body()
        }
        if (onContextAction != null) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.knotwork_chat_message_action_copy)) },
                    leadingIcon = { Icon(AppIcons.Copy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onContextAction(ChatContextAction.Copy)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.knotwork_chat_message_action_rerun)) },
                    leadingIcon = { Icon(AppIcons.Refresh, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onContextAction(ChatContextAction.Rerun)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.knotwork_chat_message_action_save_to_memory)) },
                    leadingIcon = { Icon(AppIcons.BookmarkAdd, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onContextAction(ChatContextAction.SaveToMemory)
                    },
                )
                // Reporting applies to model output, so the row is absent on the
                // user's own bubbles: there is nobody to report your own text to.
                if (role != ChatRole.User) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.knotwork_chat_message_action_report)) },
                        leadingIcon = { Icon(AppIcons.Warn, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onContextAction(ChatContextAction.Report)
                        },
                    )
                }
            }
        }
    }
}

/** Inline error tile with optional retry CTA — used for [ChatContent.Error]. */
@Composable
private fun ErrorTile(message: String, onRetry: (() -> Unit)?) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .clip(KnotworkTheme.shapes.md)
            .background(color = KnotworkTheme.extended.surface1)
            .border(
                border = BorderStroke(width = 1.dp, color = KnotworkTheme.extended.signalError),
                shape = KnotworkTheme.shapes.md,
            )
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            Icon(
                imageVector = AppIcons.AlertCircle,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalError,
                modifier = Modifier.size(KnotworkTheme.spacing.sp4),
            )
            Text(
                text = message,
                style = KnotworkTextStyles.BodyBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (onRetry != null) {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_chat_message_error_retry),
                onClick = onRetry,
            )
        }
    }
}

/** Compact mono tile rendering an inline [ChatContent.ToolCall]. */
@Composable
private fun ToolCallTile(content: ChatContent.ToolCall) {
    val accent: Color = when (content.status) {
        ToolCallStatus.Running -> MaterialTheme.colorScheme.primary
        ToolCallStatus.Success -> KnotworkTheme.extended.signalSuccess
        ToolCallStatus.Failed -> KnotworkTheme.extended.signalError
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            // Match the strip to the content's intrinsic height so the leading accent
            // stays visible without bleeding into unbounded parent containers.
            .height(IntrinsicSize.Min)
            .clip(KnotworkTheme.shapes.sm)
            .background(color = KnotworkTheme.extended.surface2)
            .border(
                border = BorderStroke(width = 1.dp, color = KnotworkTheme.extended.divider),
                shape = KnotworkTheme.shapes.sm,
            ),
    ) {
        Spacer(
            modifier = Modifier
                .width(KnotworkTheme.spacing.sp1)
                .fillMaxHeight()
                .background(color = accent),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.padding(KnotworkTheme.spacing.sp3),
        ) {
            Text(
                text = "${content.toolName}(${content.argsJson})",
                style = KnotworkTextStyles.MonoBase,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val resultLine = when (content.status) {
                ToolCallStatus.Running -> stringResource(R.string.knotwork_chat_message_tool_running)
                ToolCallStatus.Failed -> stringResource(
                    R.string.knotwork_chat_message_tool_arrow,
                    content.result ?: stringResource(R.string.knotwork_chat_message_tool_error),
                )
                ToolCallStatus.Success -> stringResource(
                    R.string.knotwork_chat_message_tool_arrow,
                    content.result ?: stringResource(R.string.knotwork_chat_message_tool_ok),
                )
            }
            Text(
                text = resultLine,
                style = KnotworkTextStyles.MonoSm,
                color = if (content.status == ToolCallStatus.Failed) accent else KnotworkTheme.extended.onSurface2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Centred system message — no bubble, no avatar. */
@Composable
private fun SystemMessage(content: ChatContent, metadata: ChatMetadata, modifier: Modifier) {
    val text = when (content) {
        is ChatContent.Text -> content.text
        is ChatContent.Markdown -> content.source
        is ChatContent.Error -> content.message
        else -> stringResource(R.string.knotwork_chat_message_system_default)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = KnotworkTheme.spacing.sp1),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
            Text(
                text = metadata.timestamp,
                style = KnotworkTextStyles.Caption,
                color = KnotworkTheme.extended.onSurfaceDim,
            )
        }
    }
}
