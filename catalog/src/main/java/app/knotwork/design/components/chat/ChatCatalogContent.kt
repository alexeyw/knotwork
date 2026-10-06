package app.knotwork.design.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Composable harness exercising every chat-surface variant in a single
 * scrollable column. Used by the Android Studio preview pane and by the
 * Roborazzi snapshot baseline so chat-surface regressions surface in one
 * diff.
 *
 * Renders inside the parent [KnotworkTheme]; callers (preview / test) pin
 * `darkTheme` deterministically.
 */
@Composable
fun ChatCatalogContent() {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        ) {
            SectionLabel(text = "ChatMessage — User / Assistant / Tool")
            ChatMessage(
                role = ChatRole.User,
                content = ChatContent.Text(text = "Hey, can you summarise yesterday's deploy?"),
                metadata = ChatMetadata(timestamp = "09:14"),
            )
            ChatMessage(
                role = ChatRole.Assistant,
                content = ChatContent.Markdown(
                    source = "Sure — three PRs landed. The pipeline editor refactor is the biggest change.",
                ),
                metadata = ChatMetadata(timestamp = "09:14", model = "Gemma 2 · 2B", tokens = 42),
            )
            ChatMessage(
                role = ChatRole.Tool,
                content = ChatContent.ToolCall(
                    toolName = "fs.read_file",
                    argsJson = "{\"path\":\"/tmp/log\"}",
                    result = "1.2 KB, 24 lines",
                    status = ToolCallStatus.Success,
                ),
                metadata = ChatMetadata(timestamp = "09:15"),
            )
            ChatMessage(
                role = ChatRole.Tool,
                content = ChatContent.ToolCall(
                    toolName = "search.web",
                    argsJson = "{\"q\":\"litert release\"}",
                    result = null,
                    status = ToolCallStatus.Running,
                ),
                metadata = ChatMetadata(timestamp = "09:15", status = ChatMessageStatus.Pending),
            )
            ChatMessage(
                role = ChatRole.System,
                content = ChatContent.Text(text = "Model unloaded to save memory"),
                metadata = ChatMetadata(timestamp = "09:16"),
            )

            SectionLabel(text = "ChatMessage — Error")
            ChatMessage(
                role = ChatRole.Assistant,
                content = ChatContent.Error(
                    message = "Inference timed out after 30 s",
                    retry = {},
                ),
                metadata = ChatMetadata(timestamp = "09:17", status = ChatMessageStatus.Failed),
            )

            SectionLabel(text = "HitlConfirmationCard — Sensitive")
            ChatMessage(
                role = ChatRole.Assistant,
                content = ChatContent.Confirmation(
                    model = HitlConfirmationModel(
                        risk = Risk.Sensitive,
                        toolName = "calendar.create_event",
                        summary = "Add a 30-minute meeting \"Team sync\" to your work calendar tomorrow at 10:00.",
                        arguments = mapOf(
                            "title" to "\"Team sync\"",
                            "duration" to "30",
                            "calendar" to "\"work\"",
                        ),
                        timestamp = "09:18",
                        request = HitlRequestContext(
                            source = HitlRequestSource.Chat,
                            request = "Book a team sync for tomorrow morning",
                            shortened = false,
                            hadImage = false,
                        ),
                    ),
                ),
                metadata = ChatMetadata(timestamp = "09:18"),
                onAllowAlways = {},
            )

            SectionLabel(text = "HitlConfirmationCard — Destructive (typing)")
            ChatMessage(
                role = ChatRole.Assistant,
                content = ChatContent.Confirmation(
                    model = HitlConfirmationModel(
                        risk = Risk.Destructive,
                        toolName = "fs.delete_file",
                        summary = "Permanently remove /Users/me/old-notes.md (4.2 KB).",
                        arguments = mapOf(
                            "path" to "\"/Users/me/old-notes.md\"",
                            "recursive" to "false",
                        ),
                        timestamp = "09:19",
                    ),
                ),
                metadata = ChatMetadata(timestamp = "09:19"),
                pendingTypedConfirm = "ye",
                allowOnceEnabled = false,
            )

            SectionLabel(text = "ClarificationCard")
            ChatMessage(
                role = ChatRole.Assistant,
                content = ChatContent.Clarification(
                    model = ClarificationCardModel(
                        question = "Which calendar should I use?",
                        quickReplies = listOf("Work", "Personal", "Family"),
                    ),
                ),
                metadata = ChatMetadata(timestamp = "09:20"),
            )

            SectionLabel(text = "ClarificationCard — replied")
            ChatMessage(
                role = ChatRole.Assistant,
                content = ChatContent.Clarification(
                    model = ClarificationCardModel(
                        question = "Which calendar should I use?",
                        quickReplies = listOf("Work", "Personal", "Family"),
                        replied = "Work",
                    ),
                ),
                metadata = ChatMetadata(timestamp = "09:21"),
            )

            SectionLabel(text = "ChatComposer — states")
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Idle,
            )
            ChatComposer(
                value = "Generate a one-paragraph summary…",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Generating,
            )
            ChatComposer(
                value = "Retry that",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Error(message = "Network unreachable"),
            )

            SectionLabel(text = "ChatComposer — voice input")
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Idle,
                onMic = {},
            )
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Recording(elapsedSec = 7, maxSec = 30),
            )
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Recording(elapsedSec = 27, maxSec = 30),
            )
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Transcribing,
            )
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Idle,
                onMic = {},
                voiceNotice = ComposerVoiceNotice.NoAudioModel,
            )
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Idle,
                onMic = {},
                voiceNotice = ComposerVoiceNotice.EngineBusy,
            )
            ChatComposer(
                value = "",
                onValueChange = {},
                onSend = {},
                onStop = {},
                state = ComposerState.Idle,
                onMic = {},
                voiceNotice = ComposerVoiceNotice.PermissionDenied,
            )

            SectionLabel(text = "AudioSourceChooserSheet")
            AudioSourceChooserSheetContent(
                maxDurationSec = 30,
                onPickRecord = {},
                onPickFile = {},
            )
        }
    }
}

/** Section title rendered above each variant group. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelMd,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

/** Light-theme preview of the chat catalog. */
@Preview(name = "Chat — Light", showBackground = true, heightDp = 2200)
@Composable
private fun ChatCatalogLightPreview() {
    KnotworkTheme(darkTheme = false) { ChatCatalogContent() }
}

/** Dark-theme preview of the chat catalog. */
@Preview(name = "Chat — Dark", showBackground = true, heightDp = 2200)
@Composable
private fun ChatCatalogDarkPreview() {
    KnotworkTheme(darkTheme = true) { ChatCatalogContent() }
}
