package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.knotwork.design.components.chat.ChatComposer
import app.knotwork.design.components.chat.ComposerAttachment
import app.knotwork.design.components.chat.ComposerState
import app.knotwork.design.components.chat.ComposerVoiceNotice
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the chat composer's multimodal affordances: the
 * image-attachment chip (preview / remove / processing) and the voice-input
 * notice banners. Each test wires the stateless catalog [ChatComposer] with a
 * synthetic state and records the callbacks the user produces — the same
 * direct-component harness used by [HitlIntegrationTest].
 */
class ChatComposerAttachmentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun readyAttachment_rendersPreviewDetail_andRemoveDispatchesCallback() {
        var removeCount = 0
        composeTestRule.setContent {
            KnotworkTheme {
                ChatComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = {},
                    state = ComposerState.Idle,
                    attachment = ComposerAttachment.Ready(model = "/abs/photo.jpg", detail = ATTACHMENT_DETAIL),
                    onRemoveAttachment = { removeCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(ATTACHMENT_DETAIL).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(REMOVE_LABEL).performClick()

        assertEquals("Remove callback should fire once", 1, removeCount)
    }

    @Test
    fun processingAttachment_rendersProcessingState() {
        composeTestRule.setContent {
            KnotworkTheme {
                ChatComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = {},
                    state = ComposerState.Idle,
                    attachment = ComposerAttachment.Processing,
                )
            }
        }

        composeTestRule.onNodeWithText(PROCESSING_LABEL).assertIsDisplayed()
    }

    @Test
    fun permissionDeniedNotice_rendersBannerWithOpenSettingsAction() {
        var openSettingsCount = 0
        composeTestRule.setContent {
            KnotworkTheme {
                ChatComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = {},
                    state = ComposerState.Idle,
                    voiceNotice = ComposerVoiceNotice.PermissionDenied,
                    onOpenSettings = { openSettingsCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(PERMISSION_DENIED_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(OPEN_SETTINGS_ACTION).performClick()

        assertEquals("Open-settings action should fire once", 1, openSettingsCount)
    }

    @Test
    fun noAudioModelNotice_rendersBannerWithChangeModelAction() {
        var changeModelCount = 0
        composeTestRule.setContent {
            KnotworkTheme {
                ChatComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = {},
                    state = ComposerState.Idle,
                    voiceNotice = ComposerVoiceNotice.NoAudioModel,
                    onChangeModel = { changeModelCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(NO_AUDIO_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(CHANGE_MODEL_ACTION).performClick()

        assertEquals("Change-model action should fire once", 1, changeModelCount)
    }

    private companion object {
        // Mirrors the catalog string resources (knotwork_attachment_* / knotwork_voice_*).
        // Literals are stable; if they drift the matcher catches it as a regression.
        const val ATTACHMENT_DETAIL: String = "712×1536 · 84 KB"
        const val REMOVE_LABEL: String = "Remove image"
        const val PROCESSING_LABEL: String = "Processing…"
        const val PERMISSION_DENIED_TITLE: String = "Microphone access is off"
        const val OPEN_SETTINGS_ACTION: String = "Open settings"
        const val NO_AUDIO_TITLE: String = "This model can't transcribe audio"
        const val CHANGE_MODEL_ACTION: String = "Change model"
    }
}
