@file:Suppress(
    "MatchingDeclarationName", // File hosts both `ComposerState` (sealed interface) and the `ChatComposer` composable.
)

package app.knotwork.design.components.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Composer state machine — drives the send / stop morph, the input enabled
 * state, and the inline error banner.
 *
 */
sealed interface ComposerState {

    /** Default. User can type and submit. */
    data object Idle : ComposerState

    /**
     * Assistant is currently generating; the send affordance morphs into a
     * stop button (200 ms cross-fade) and tapping it cancels the run. The
     * input field accepts text so the user can queue the next prompt.
     */
    data object Generating : ComposerState

    /**
     * Last submission errored. An inline banner renders above the input
     * showing [message]; the send affordance returns to its idle visual.
     *
     * @property message user-visible error description.
     */
    data class Error(val message: String) : ComposerState

    /**
     * Recording a voice clip. The recording bar **replaces** the input row
     * (the user can't type mid-record): discard ✕ · pulsing REC dot · mono
     * timer `elapsed / max` · decorative amplitude strip · Stop. In the last
     * [RECORDING_WARN_SECONDS] before [maxSec] the timer and bar outline turn
     * to the warn palette (not an error — it's an expected boundary) with a
     * caption that the clip auto-stops and transcribes at the limit.
     *
     * @property elapsedSec seconds elapsed since recording started.
     * @property maxSec the recording limit, after which capture auto-stops.
     */
    data class Recording(val elapsedSec: Int, val maxSec: Int) : ComposerState {
        /** Whether the elapsed time is within the warn window of the limit. */
        val nearLimit: Boolean get() = maxSec - elapsedSec <= RECORDING_WARN_SECONDS
    }

    /**
     * The recorded/picked clip is being transcribed by the multimodal model.
     * The input row shows a spinner + "Transcribing…" and the send affordance
     * is disabled until the transcript lands in the field.
     */
    data object Transcribing : ComposerState
}

/**
 * A calm, non-alarmist notice shown above the composer input row when a
 * voice-input action cannot proceed. The input row dims while a notice is
 * present. Copy and visuals are owned by the design system per case; the
 * caller only selects the case and supplies the relevant action handler.
 */
enum class ComposerVoiceNotice {
    /** The active model is not marked audio-capable. Offers "Change model". */
    NoAudioModel,

    /** A pipeline run holds the engine; transcription is paused. No action. */
    EngineBusy,

    /** Microphone permission is denied. Offers "Open settings". */
    PermissionDenied,
}

/**
 * State of the image attached to the composer, rendered as a removable strip
 * above the input row.
 */
sealed interface ComposerAttachment {

    /**
     * The picked/captured image is being downscaled and re-encoded. Shows a
     * shimmer thumbnail and a "Processing…" label until it becomes [Ready].
     */
    data object Processing : ComposerAttachment

    /**
     * The image is stored and ready to send.
     *
     * @property model Coil model (absolute file path) for the preview thumbnail.
     * @property detail mono downscale matrix, e.g. `1290×2796 → 712×1536 · 84 KB`.
     */
    data class Ready(val model: Any?, val detail: String) : ComposerAttachment
}

/**
 * Knotwork chat composer — pill-shaped multiline input + circular brand
 * action button.
 *
 * Visual contract:
 *  - A single pill (`KnotworkTheme.shapes.full`) on `extended.surface1` hosts
 *    the borderless input on the left and a circular filled brand-color
 *    action button on the right.
 *  - The action button morphs Send ↔ Stop via a 200 ms `AnimatedContent`
 *    crossfade; reduced motion collapses the crossfade to instant.
 *  - In `Idle` / `Error` the button shows the paper-plane `Send` icon and
 *    fires [onSend]. In `Generating` it shows `Pause` and fires [onStop].
 *  - When [state] is [ComposerState.Error], an inline banner with
 *    `signalError` accent renders above the pill.
 *
 * **Stateless** — `value` is hoisted to the caller; this composable never
 * stores text. The screen ViewModel owns persistence and history.
 *
 * Trailing action-button state matrix:
 *
 * | composer state                                  | icon  | tint     |
 * |-------------------------------------------------|-------|----------|
 * | [ComposerState.Idle] && value empty && onMic≠null | mic   | surface3 |
 * | [ComposerState.Idle] && value non-empty (or no onMic) | send  | primary  |
 * | [ComposerState.Generating]                       | stop  | primary  |
 * | [ComposerState.Error]                            | retry | error    |
 *
 * @param value current input value.
 * @param onValueChange invoked with each keystroke.
 * @param onSend invoked when the user taps the action button in Idle (with value) / Error.
 * @param onStop invoked when the user taps the action button in Generating.
 * @param state current state of the composer (drives morph + error banner).
 * @param modifier optional layout modifier applied to the composer root.
 * @param onMic optional voice-input handler. When non-null **and** the
 *  composer is [ComposerState.Idle] with an empty [value] **and** no attachment,
 *  the trailing button shows a microphone icon on the muted `surface3` palette
 *  so the affordance reads as "press to talk" rather than "press to send". Pass
 *  `null` to suppress the mic state (the button stays on Send / disabled).
 * @param attachment current image attachment shown as a removable strip above
 *  the input row, or `null` when none. When non-null the send affordance is
 *  active even with empty [value] (image-only messages are allowed).
 * @param onAttach optional handler opening the image-source chooser. When
 *  non-null a leading "add image" button is shown in the pill; pass `null` to
 *  hide the attachment affordance entirely.
 * @param onRemoveAttachment invoked when the user taps the ✕ on the preview.
 * @param onStopRecording invoked when the user taps Stop in [ComposerState.Recording].
 * @param onDiscardRecording invoked when the user taps the discard ✕ in [ComposerState.Recording].
 * @param voiceNotice a calm blocked/permission notice shown above a dimmed input
 *  row when a voice-input action cannot proceed, or `null` when none.
 * @param onChangeModel invoked from the [ComposerVoiceNotice.NoAudioModel] action.
 * @param onOpenSettings invoked from the [ComposerVoiceNotice.PermissionDenied] action.
 */
@Composable
@Suppress("LongParameterList", "LongMethod")
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    state: ComposerState,
    modifier: Modifier = Modifier,
    onMic: (() -> Unit)? = null,
    attachment: ComposerAttachment? = null,
    onAttach: (() -> Unit)? = null,
    onRemoveAttachment: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    onDiscardRecording: () -> Unit = {},
    voiceNotice: ComposerVoiceNotice? = null,
    onChangeModel: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.surface)
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        if (state is ComposerState.Error) {
            ErrorBanner(message = state.message)
        }
        if (voiceNotice != null) {
            VoiceNoticeBanner(
                notice = voiceNotice,
                onChangeModel = onChangeModel,
                onOpenSettings = onOpenSettings,
            )
        }
        if (attachment != null) {
            ComposerAttachmentPreview(attachment = attachment, onRemove = onRemoveAttachment)
        }
        if (state is ComposerState.Recording) {
            // The recording bar replaces the input row outright.
            RecordingBar(
                state = state,
                onStop = onStopRecording,
                onDiscard = onDiscardRecording,
            )
        } else {
            // A blocked voice notice dims the row but keeps it in place.
            val rowAlpha = if (voiceNotice != null) BLOCKED_ROW_ALPHA else 1f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = rowAlpha }
                    .clip(KnotworkTheme.shapes.full)
                    .background(color = KnotworkTheme.extended.surface1)
                    .padding(
                        start = if (onAttach != null) KnotworkTheme.spacing.sp1 else KnotworkTheme.spacing.sp4,
                        end = KnotworkTheme.spacing.sp1,
                        top = KnotworkTheme.spacing.sp1,
                        bottom = KnotworkTheme.spacing.sp1,
                    ),
            ) {
                if (state is ComposerState.Transcribing) {
                    TranscribingIndicator(modifier = Modifier.weight(1f))
                } else {
                    if (onAttach != null) {
                        ComposerAttachButton(onClick = onAttach)
                    }
                    ComposerInput(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f),
                    )
                }
                ActionButton(
                    state = state,
                    value = value,
                    hasAttachment = attachment != null,
                    // Sending is blocked while the image is still downscaling or the
                    // clip is transcribing, so Send renders disabled rather than
                    // firing a silent no-op.
                    sendEnabled = attachment !is ComposerAttachment.Processing &&
                        state !is ComposerState.Transcribing &&
                        voiceNotice == null,
                    onSend = onSend,
                    onStop = onStop,
                    onMic = onMic,
                )
            }
        }
    }
}

/**
 * Borderless multiline text field used inside the composer pill. Wraps a
 * [BasicTextField] (instead of `OutlinedTextField`) so the input visually
 * dissolves into the pill background — only the placeholder + caret + text
 * are visible, no outline or container.
 */
@Composable
private fun ComposerInput(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val placeholder = stringResource(R.string.knotwork_composer_placeholder)
    val textStyle: TextStyle = KnotworkTextStyles.BodyBase.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = textStyle,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = COMPOSER_MAX_VISIBLE_LINES,
        modifier = modifier
            .heightIn(min = COMPOSER_INPUT_MIN_HEIGHT)
            .wrapContentHeight(Alignment.CenterVertically),
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
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
}

/** Maximum visible rows in the composer before internal scroll kicks in. */
private const val COMPOSER_MAX_VISIBLE_LINES = 6

/**
 * Minimum height of the composer input row. Picked so the pill stays at the
 * canonical 48 dp visual height when the action button (48 dp circle)
 * dominates vertical layout — keeps the placeholder centered.
 */
private val COMPOSER_INPUT_MIN_HEIGHT = 40.dp

/**
 * Leading "add image" button inside the composer pill. 48 × 48 touch target
 * with a 20 dp glyph, mirroring the trailing action button's geometry.
 */
@Composable
private fun ComposerAttachButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val description = stringResource(R.string.knotwork_attachment_add)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(COMPOSER_ACTION_BUTTON_SIZE)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = description
                this.role = Role.Button
            },
    ) {
        Icon(
            imageVector = AppIcons.Image,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceDim,
            modifier = Modifier.size(COMPOSER_ACTION_ICON_SIZE),
        )
    }
}

/**
 * Removable preview strip shown above the input row once an image is attached.
 * A 56 dp squircle thumbnail (shimmer while [ComposerAttachment.Processing])
 * sits beside the title + mono detail; a ✕ overlaps its top-right corner.
 */
@Composable
private fun ComposerAttachmentPreview(attachment: ComposerAttachment, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KnotworkTheme.spacing.sp2),
    ) {
        Box {
            val thumbModifier = Modifier
                .size(COMPOSER_PREVIEW_THUMB_SIZE)
                .clip(KnotworkTheme.shapes.md)
            when (attachment) {
                is ComposerAttachment.Processing -> ImageThumbnailLoadingState(modifier = thumbModifier)
                is ComposerAttachment.Ready -> ImageThumbnail(
                    model = attachment.model,
                    contentDescription = stringResource(R.string.knotwork_attachment_thumbnail),
                    contentScale = ContentScale.Crop,
                    modifier = thumbModifier,
                )
            }
            RemoveAttachmentButton(
                onRemove = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = REMOVE_OFFSET, y = -REMOVE_OFFSET),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
            val title = when (attachment) {
                is ComposerAttachment.Processing -> stringResource(R.string.knotwork_attachment_processing)
                is ComposerAttachment.Ready -> stringResource(R.string.knotwork_attachment_attached)
            }
            val detail = when (attachment) {
                is ComposerAttachment.Processing -> stringResource(R.string.knotwork_attachment_processing_detail)
                is ComposerAttachment.Ready -> attachment.detail
            }
            Text(
                text = title,
                style = KnotworkTextStyles.LabelMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Circular ✕ button overlapping the preview thumbnail's top-right corner. */
@Composable
private fun RemoveAttachmentButton(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val description = stringResource(R.string.knotwork_attachment_remove)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(REMOVE_BUTTON_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.inverseSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onRemove,
            )
            .semantics {
                this.contentDescription = description
                this.role = Role.Button
            },
    ) {
        Icon(
            imageVector = AppIcons.X,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.size(REMOVE_ICON_SIZE),
        )
    }
}

/** Side length of the composer preview thumbnail (squircle). */
private val COMPOSER_PREVIEW_THUMB_SIZE = 56.dp

/** Diameter of the circular ✕ remove button overlapping the preview. */
private val REMOVE_BUTTON_SIZE = 22.dp

/** Glyph size inside the ✕ remove button. */
private val REMOVE_ICON_SIZE = 13.dp

/** Outward offset so the ✕ hugs the thumbnail's top-right corner. */
private val REMOVE_OFFSET = 6.dp

/**
 * Trailing action button — 200 ms cross-fades between mic / send / stop /
 * retry depending on the [ComposerState] × [value] cross-product. The
 * morph respects reduced motion (zero-duration fade when on).
 */
@Composable
@Suppress("LongParameterList")
private fun ActionButton(
    state: ComposerState,
    value: String,
    hasAttachment: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: (() -> Unit)?,
) {
    val reducedMotion = KnotworkTheme.a11y.reducedMotion()
    val durationMs = if (reducedMotion) 0 else COMPOSER_MORPH_MS
    val target = resolveActionTarget(state = state, value = value, hasAttachment = hasAttachment, onMic = onMic)
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            fadeIn(androidx.compose.animation.core.tween(durationMs)) togetherWith
                fadeOut(androidx.compose.animation.core.tween(durationMs))
        },
        label = "composer_action_morph",
    ) { current ->
        // Send is the only target gated by [sendEnabled] (blocked while an
        // attachment is still processing); the others are always actionable.
        val disabled = current == ActionTarget.Send && !sendEnabled
        val icon: ImageVector
        val descriptionRes: Int
        val onClick: () -> Unit
        val container: Color
        val content: Color
        when (current) {
            ActionTarget.Mic -> {
                icon = AppIcons.Mic
                descriptionRes = R.string.knotwork_composer_mic
                onClick = onMic ?: {}
                container = KnotworkTheme.extended.surface3
                content = MaterialTheme.colorScheme.onSurface
            }
            ActionTarget.Send -> {
                icon = AppIcons.Send
                descriptionRes = R.string.knotwork_composer_send
                onClick = if (disabled) ({ }) else onSend
                container = if (disabled) KnotworkTheme.extended.surface3 else MaterialTheme.colorScheme.primary
                content = if (disabled) KnotworkTheme.extended.onSurfaceDim else MaterialTheme.colorScheme.onPrimary
            }
            ActionTarget.Stop -> {
                icon = AppIcons.Pause
                descriptionRes = R.string.knotwork_composer_stop
                onClick = onStop
                container = MaterialTheme.colorScheme.primary
                content = MaterialTheme.colorScheme.onPrimary
            }
            ActionTarget.Retry -> {
                icon = AppIcons.Refresh
                descriptionRes = R.string.knotwork_composer_send
                onClick = onSend
                container = KnotworkTheme.extended.riskDestructive
                content = MaterialTheme.colorScheme.onPrimary
            }
        }
        ComposerActionButton(
            icon = icon,
            contentDescription = stringResource(descriptionRes),
            onClick = onClick,
            container = container,
            content = content,
            enabled = !disabled,
        )
    }
}

/** Discrete target state for the trailing action button. */
private enum class ActionTarget { Mic, Send, Stop, Retry }

private fun resolveActionTarget(
    state: ComposerState,
    value: String,
    hasAttachment: Boolean,
    onMic: (() -> Unit)?,
): ActionTarget = when (state) {
    is ComposerState.Generating -> ActionTarget.Stop
    is ComposerState.Error -> ActionTarget.Retry
    // Transcribing shows a disabled Send (gated by `sendEnabled`); Recording
    // replaces the row entirely, so its target is never actually rendered.
    is ComposerState.Transcribing, is ComposerState.Recording -> ActionTarget.Send
    // An attachment alone enables send (image-only is allowed); the mic only
    // shows when there is neither text nor an attachment.
    is ComposerState.Idle ->
        if (value.isEmpty() && !hasAttachment && onMic != null) ActionTarget.Mic else ActionTarget.Send
}

/** Diameter of the circular send / stop action button (matches Knotwork primary-button visual height). */
private val COMPOSER_ACTION_BUTTON_SIZE = 48.dp

/** Size of the glyph rendered inside the circular action button. */
private val COMPOSER_ACTION_ICON_SIZE = 20.dp

/**
 * Circular filled brand-color action button used inside the composer pill
 * for Send / Stop. Stays at 48 × 48 so the touch target meets a11y minimums
 * even though the visual is a tight circle.
 */
@Composable
@Suppress("LongParameterList")
private fun ComposerActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(COMPOSER_ACTION_BUTTON_SIZE)
            .clip(CircleShape)
            .background(color = container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
                if (!enabled) disabled()
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(COMPOSER_ACTION_ICON_SIZE),
        )
    }
}

/** Duration of the send ↔ stop cross-fade. */
private const val COMPOSER_MORPH_MS = 200

/** Inline error banner stacked above the input row. */
@Composable
private fun ErrorBanner(message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = KnotworkTheme.extended.signalError.copy(alpha = ERROR_BANNER_BG_ALPHA),
                shape = KnotworkTheme.shapes.sm,
            )
            .padding(
                horizontal = KnotworkTheme.spacing.sp3,
                vertical = KnotworkTheme.spacing.sp2,
            ),
    ) {
        Icon(
            imageVector = AppIcons.AlertCircle,
            contentDescription = null,
            tint = KnotworkTheme.extended.signalError,
        )
        Text(
            text = message,
            style = KnotworkTextStyles.BodySm,
            color = errorBannerForeground(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Alpha applied to the error banner background tint. */
private const val ERROR_BANNER_BG_ALPHA = 0.12f

/** Foreground colour for the error banner label — keeps body legible on the tinted strip. */
@Composable
private fun errorBannerForeground(): Color = MaterialTheme.colorScheme.onSurface

/**
 * The recording bar that replaces the input row while a clip is being captured.
 * Layout L→R: discard ✕ · pulsing REC dot · mono `elapsed / max` timer ·
 * decorative amplitude strip · Stop. Near the limit the timer + bar outline turn
 * to the warn palette and a caption announces the imminent auto-stop. The pulse
 * and amplitude animation collapse to static under reduced motion.
 */
@Composable
private fun RecordingBar(state: ComposerState.Recording, onStop: () -> Unit, onDiscard: () -> Unit) {
    val warn = KnotworkTheme.extended.signalWarn
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
            modifier = Modifier
                .fillMaxWidth()
                .clip(KnotworkTheme.shapes.full)
                .background(color = KnotworkTheme.extended.surface2)
                .then(
                    if (state.nearLimit) {
                        Modifier.border(BorderStroke(1.dp, warn), shape = KnotworkTheme.shapes.full)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = KnotworkTheme.spacing.sp1,
                    end = KnotworkTheme.spacing.sp1,
                    top = KnotworkTheme.spacing.sp1,
                    bottom = KnotworkTheme.spacing.sp1,
                ),
        ) {
            RecordingCircleButton(
                icon = AppIcons.X,
                contentDescription = stringResource(R.string.knotwork_composer_recording_discard),
                container = Color.Transparent,
                content = KnotworkTheme.extended.onSurface2,
                onClick = onDiscard,
            )
            RecDot()
            Text(
                text = stringResource(
                    R.string.knotwork_composer_recording_timer,
                    formatClock(state.elapsedSec),
                    formatClock(state.maxSec),
                ),
                style = KnotworkTextStyles.MonoSm,
                color = if (state.nearLimit) warn else MaterialTheme.colorScheme.onSurface,
            )
            Amplitude(dim = state.nearLimit, modifier = Modifier.weight(1f))
            RecordingCircleButton(
                icon = AppIcons.Stop,
                contentDescription = stringResource(R.string.knotwork_composer_recording_stop),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onStop,
            )
        }
        if (state.nearLimit) {
            Text(
                text = stringResource(
                    R.string.knotwork_composer_recording_limit_caption,
                    formatClock(state.maxSec),
                ),
                style = KnotworkTextStyles.MonoSm,
                color = warn,
                modifier = Modifier.padding(start = KnotworkTheme.spacing.sp2),
            )
        }
    }
}

/** A 40 dp circular button used inside the recording bar (discard / stop). */
@Composable
private fun RecordingCircleButton(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(RECORDING_BUTTON_SIZE)
            .clip(CircleShape)
            .background(color = container)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(COMPOSER_ACTION_ICON_SIZE),
        )
    }
}

/** Pulsing recording-indicator dot (static under reduced motion). */
@Composable
private fun RecDot() {
    val reduced = KnotworkTheme.a11y.reducedMotion()
    val transition = rememberInfiniteTransition(label = "rec-dot")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = REC_DOT_MIN_ALPHA,
        animationSpec = infiniteRepeatable(tween(REC_DOT_PULSE_MS), RepeatMode.Reverse),
        label = "rec-dot-alpha",
    )
    val description = stringResource(R.string.knotwork_composer_recording_indicator)
    Box(
        modifier = Modifier
            .size(REC_DOT_SIZE)
            .graphicsLayer { alpha = if (reduced) 1f else pulse }
            .clip(CircleShape)
            .background(KnotworkTheme.extended.riskDestructive)
            .semantics { contentDescription = description },
    )
}

/** Decorative amplitude strip — staggered bars, static under reduced motion. */
@Composable
private fun Amplitude(dim: Boolean, modifier: Modifier = Modifier) {
    val reduced = KnotworkTheme.a11y.reducedMotion()
    val transition = rememberInfiniteTransition(label = "amp")
    val color = if (dim) KnotworkTheme.extended.onSurfaceDim else MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AMPLITUDE_BAR_GAP),
        modifier = modifier.height(AMPLITUDE_STRIP_HEIGHT),
    ) {
        AMPLITUDE_BAR_HEIGHTS.forEachIndexed { index, heightDp ->
            val scale by transition.animateFloat(
                initialValue = AMPLITUDE_BAR_MIN_SCALE,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(AMPLITUDE_BAR_PERIOD_MS),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * AMPLITUDE_BAR_STAGGER_MS),
                ),
                label = "amp-bar-$index",
            )
            Box(
                modifier = Modifier
                    .width(AMPLITUDE_BAR_WIDTH)
                    .height(heightDp)
                    .graphicsLayer { scaleY = if (reduced) 1f else scale }
                    .clip(RoundedCornerShape(percent = 100))
                    .background(color),
            )
        }
    }
}

/** Spinner + "Transcribing…" shown inside the pill while a clip is transcribed. */
@Composable
private fun TranscribingIndicator(modifier: Modifier = Modifier) {
    val reduced = KnotworkTheme.a11y.reducedMotion()
    val transition = rememberInfiniteTransition(label = "transcribe")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = FULL_ROTATION_DEGREES,
        animationSpec = infiniteRepeatable(tween(TRANSCRIBE_SPIN_MS, easing = LinearEasing)),
        label = "transcribe-spin",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier.heightIn(min = COMPOSER_INPUT_MIN_HEIGHT),
    ) {
        Icon(
            imageVector = AppIcons.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(COMPOSER_ACTION_ICON_SIZE)
                .graphicsLayer { rotationZ = if (reduced) 0f else rotation },
        )
        Text(
            text = stringResource(R.string.knotwork_composer_transcribing),
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

/**
 * Calm, non-alarmist notice shown above the (dimmed) input row when a
 * voice-input action is blocked. Maps each [ComposerVoiceNotice] case to its
 * icon, copy, tone, and optional action.
 */
@Composable
private fun VoiceNoticeBanner(notice: ComposerVoiceNotice, onChangeModel: () -> Unit, onOpenSettings: () -> Unit) {
    val warn = notice == ComposerVoiceNotice.PermissionDenied
    val borderColor = if (warn) KnotworkTheme.extended.signalWarn else KnotworkTheme.extended.outlineStrong
    val icon = when (notice) {
        ComposerVoiceNotice.NoAudioModel -> AppIcons.Chip
        ComposerVoiceNotice.EngineBusy -> AppIcons.Hourglass
        ComposerVoiceNotice.PermissionDenied -> AppIcons.Mic
    }
    val title = when (notice) {
        ComposerVoiceNotice.NoAudioModel -> R.string.knotwork_voice_no_model_title
        ComposerVoiceNotice.EngineBusy -> R.string.knotwork_voice_busy_title
        ComposerVoiceNotice.PermissionDenied -> R.string.knotwork_voice_permission_title
    }
    val body = when (notice) {
        ComposerVoiceNotice.NoAudioModel -> R.string.knotwork_voice_no_model_body
        ComposerVoiceNotice.EngineBusy -> R.string.knotwork_voice_busy_body
        ComposerVoiceNotice.PermissionDenied -> R.string.knotwork_voice_permission_body
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, borderColor), shape = KnotworkTheme.shapes.md)
            .padding(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (warn) KnotworkTheme.extended.signalWarn else KnotworkTheme.extended.onSurface2,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(title),
                style = KnotworkTextStyles.LabelMd,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(body),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.onSurface2,
            )
            when (notice) {
                ComposerVoiceNotice.NoAudioModel -> KnotworkSecondaryButton(
                    text = stringResource(R.string.knotwork_voice_no_model_action),
                    onClick = onChangeModel,
                    size = KnotworkButtonSize.Sm,
                    leadingIcon = AppIcons.ArrowR,
                )
                ComposerVoiceNotice.PermissionDenied -> KnotworkSecondaryButton(
                    text = stringResource(R.string.knotwork_voice_permission_action),
                    onClick = onOpenSettings,
                    size = KnotworkButtonSize.Sm,
                    leadingIcon = AppIcons.Cog,
                )
                ComposerVoiceNotice.EngineBusy -> Unit
            }
        }
    }
}

/**
 * Formats a whole-second count as `m:ss` (e.g. 7 → `0:07`, 90 → `1:30`). Shared
 * by the recording-bar timer and the audio-source chooser's duration label so a
 * second is formatted identically everywhere.
 */
internal fun formatClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / SECONDS_PER_MINUTE
    val seconds = safe % SECONDS_PER_MINUTE
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** Seconds before the recording limit at which the bar enters its warn state. */
const val RECORDING_WARN_SECONDS = 5

private const val SECONDS_PER_MINUTE = 60

/** Diameter of the discard / stop buttons in the recording bar. */
private val RECORDING_BUTTON_SIZE = 40.dp

/** Diameter of the pulsing REC dot. */
private val REC_DOT_SIZE = 10.dp

/** Lowest alpha the REC dot fades to at the bottom of its pulse. */
private const val REC_DOT_MIN_ALPHA = 0.35f

/** Period of one REC-dot pulse half-cycle, in ms. */
private const val REC_DOT_PULSE_MS = 1100

/** Heights of the decorative amplitude bars (px-equivalent dp from the mockup). */
private val AMPLITUDE_BAR_HEIGHTS = listOf(
    6.dp, 11.dp, 18.dp, 9.dp, 22.dp, 14.dp, 26.dp, 12.dp, 20.dp, 8.dp, 15.dp, 10.dp, 7.dp,
)

/** Overall height of the amplitude strip. */
private val AMPLITUDE_STRIP_HEIGHT = 26.dp

/** Width of a single amplitude bar. */
private val AMPLITUDE_BAR_WIDTH = 2.5.dp

/** Gap between amplitude bars. */
private val AMPLITUDE_BAR_GAP = 2.5.dp

/** Lowest scale an amplitude bar shrinks to. */
private const val AMPLITUDE_BAR_MIN_SCALE = 0.35f

/** Period of one amplitude-bar oscillation half-cycle, in ms. */
private const val AMPLITUDE_BAR_PERIOD_MS = 900

/** Per-bar animation start stagger, in ms. */
private const val AMPLITUDE_BAR_STAGGER_MS = 70

/** Period of one full transcribe-spinner rotation, in ms. */
private const val TRANSCRIBE_SPIN_MS = 900

/** A full rotation in degrees. */
private const val FULL_ROTATION_DEGREES = 360f

/** Alpha applied to the input row while a blocked voice notice is shown. */
private const val BLOCKED_ROW_ALPHA = 0.6f
