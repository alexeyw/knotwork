package app.knotwork.design.components.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler

/**
 * Composable harness exercising the **image-attachment** components in a single
 * scrollable column: the [ImageThumbnail] ready / loading / missing states, the
 * [ChatContent.Image] bubble (with caption and image-only), the [ChatComposer]
 * attachment affordance (idle / preview / processing), and the
 * [SourceChooserSheetContent] rows.
 *
 * Every Coil-loaded image is rendered deterministically: the whole tree is
 * wrapped in a [LocalAsyncImagePreviewHandler] that resolves any request to a
 * flat [ColorImage], so the Roborazzi baseline never depends on decoding a real
 * file. The full-screen [ImageViewer] is a `Dialog` (separate window) and is not
 * boarded here.
 *
 * Renders inside the parent [KnotworkTheme]; callers (preview / test) pin
 * `darkTheme` deterministically.
 *
 * `LocalInspectionMode` is forced on so Coil consults the preview handler (it
 * only does so under inspection); `@Preview` sets this automatically, the
 * snapshot test does not, so the harness pins it for both. This composable
 * exists solely for previews / snapshots.
 */
@OptIn(ExperimentalCoilApi::class)
@Composable
fun ImageAttachmentCatalogContent() {
    val previewHandler = AsyncImagePreviewHandler {
        ColorImage(color = PREVIEW_IMAGE_COLOR.toArgb(), width = PREVIEW_IMAGE_PX, height = PREVIEW_IMAGE_PX)
    }
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides previewHandler,
    ) {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(KnotworkTheme.spacing.sp4),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
            ) {
                AttachmentSectionLabel(text = "ImageThumbnail — ready / loading / missing")
                Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
                    ImageThumbnail(
                        model = "preview://ready",
                        contentDescription = null,
                        modifier = Modifier.size(THUMB_SIZE),
                    )
                    ImageThumbnailLoadingState(
                        modifier = Modifier
                            .size(THUMB_SIZE)
                            .clip(KnotworkTheme.shapes.md),
                    )
                    ImageThumbnailMissingState(modifier = Modifier.size(MISSING_SIZE))
                }

                AttachmentSectionLabel(text = "ChatMessage — Image (caption / image-only)")
                ChatMessage(
                    role = ChatRole.User,
                    content = ChatContent.Image(
                        model = "preview://shot",
                        caption = "What's going on in this screenshot?",
                        aspectRatio = PORTRAIT_RATIO,
                        onTap = {},
                    ),
                    metadata = ChatMetadata(timestamp = "09:14"),
                )
                ChatMessage(
                    role = ChatRole.User,
                    content = ChatContent.Image(
                        model = "preview://wide",
                        caption = null,
                        aspectRatio = LANDSCAPE_RATIO,
                        onTap = {},
                    ),
                    metadata = ChatMetadata(timestamp = "09:15"),
                )

                AttachmentSectionLabel(text = "ChatComposer — attachment affordance")
                ChatComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = {},
                    state = ComposerState.Idle,
                    onAttach = {},
                )
                ChatComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = {},
                    state = ComposerState.Idle,
                    attachment = ComposerAttachment.Ready(
                        model = "preview://ready",
                        detail = "1290×2796 → 712×1536 · 84 KB",
                    ),
                    onAttach = {},
                    onRemoveAttachment = {},
                )
                ChatComposer(
                    value = "",
                    onValueChange = {},
                    onSend = {},
                    onStop = {},
                    state = ComposerState.Idle,
                    attachment = ComposerAttachment.Processing,
                    onAttach = {},
                    onRemoveAttachment = {},
                )

                AttachmentSectionLabel(text = "SourceChooserSheet — content")
                SourceChooserSheetContent(onPickPhotoLibrary = {}, onPickCamera = {})
            }
        }
    }
}

/** Section title rendered above each variant group. */
@Composable
private fun AttachmentSectionLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelMd,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

/** Light-theme preview of the image-attachment catalog. */
@Preview(name = "Image attachments — Light", showBackground = true, heightDp = 1400)
@Composable
private fun ImageAttachmentCatalogLightPreview() {
    KnotworkTheme(darkTheme = false) { ImageAttachmentCatalogContent() }
}

/** Dark-theme preview of the image-attachment catalog. */
@Preview(name = "Image attachments — Dark", showBackground = true, heightDp = 1400)
@Composable
private fun ImageAttachmentCatalogDarkPreview() {
    KnotworkTheme(darkTheme = true) { ImageAttachmentCatalogContent() }
}

/** Neutral fill colour used by the preview image handler in place of a decoded photo. */
private val PREVIEW_IMAGE_COLOR = Color(red = 0x90, green = 0xA4, blue = 0xAE)

/** Intrinsic pixel size of the stand-in preview image. */
private const val PREVIEW_IMAGE_PX = 480

/** Thumbnail side length in the states row. */
private val THUMB_SIZE = 96.dp

/** Missing-state tile side length. */
private val MISSING_SIZE = 120.dp

/** Portrait aspect ratio (a typical phone screenshot) for the captioned bubble. */
private const val PORTRAIT_RATIO = 0.46f

/** Landscape aspect ratio for the image-only bubble. */
private const val LANDSCAPE_RATIO = 1.5f
