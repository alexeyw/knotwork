package app.knotwork.design.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.knotwork.design.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import coil3.compose.AsyncImage

/**
 * Full-screen image viewer shown when an attachment thumbnail is tapped.
 *
 * Plain full-bleed image on an always-dark scrim (photo viewing reads the same
 * in light and dark themes) — **no pinch-zoom** in this phase. Dismiss via the
 * close button, system back (the hosting [Dialog]), or a tap on the scrim. The
 * top bar carries the file name, the stored dimensions/size, and an optional
 * share action. When the underlying file is gone ([isMissing]) a calm
 * "no longer available" message replaces the image.
 *
 * **Stateless** — the caller decides when it is shown and owns dismissal.
 *
 * @param model Coil image model (absolute file path) of the stored attachment.
 * @param fileName file name shown in the top bar.
 * @param dimensionsLabel mono dimensions/size label (e.g. `712×1536 · 84 KB`).
 * @param isMissing when `true`, renders the missing-file message instead of the
 *   image.
 * @param onDismiss invoked on close / back / scrim tap.
 * @param onShare optional share handler; the share action is hidden when `null`
 *   or when [isMissing].
 */
@Composable
fun ImageViewer(
    model: Any?,
    fileName: String,
    dimensionsLabel: String,
    isMissing: Boolean,
    onDismiss: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VIEWER_SCRIM)
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            if (isMissing) {
                ViewerMissingState(modifier = Modifier.align(Alignment.Center))
            } else {
                AsyncImage(
                    model = model,
                    contentDescription = stringResource(R.string.knotwork_image_viewer_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(KnotworkTheme.spacing.sp4),
                )
            }
            ViewerTopBar(
                fileName = fileName,
                dimensionsLabel = dimensionsLabel,
                onClose = onDismiss,
                onShare = if (isMissing) null else onShare,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/** Top bar: close (left), file name + dimensions (center), optional share (right). */
@Composable
private fun ViewerTopBar(
    fileName: String,
    dimensionsLabel: String,
    onClose: () -> Unit,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = KnotworkTheme.spacing.sp2, vertical = KnotworkTheme.spacing.sp1),
    ) {
        ViewerIconButton(
            icon = AppIcons.X,
            contentDescription = stringResource(R.string.knotwork_image_viewer_close),
            onClick = onClose,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = KnotworkTextStyles.LabelMd,
                color = VIEWER_ON_SCRIM,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = dimensionsLabel,
                style = KnotworkTextStyles.MonoSm,
                color = VIEWER_ON_SCRIM_DIM,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onShare != null) {
            ViewerIconButton(
                icon = AppIcons.Share,
                contentDescription = stringResource(R.string.knotwork_image_viewer_share),
                onClick = onShare,
            )
        }
    }
}

/** Borderless icon button tinted for the dark scrim. */
@Composable
private fun ViewerIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(VIEWER_BUTTON_SIZE)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = VIEWER_ON_SCRIM,
            modifier = Modifier.size(VIEWER_ICON_SIZE),
        )
    }
}

/** Missing-file message centred on the scrim. */
@Composable
private fun ViewerMissingState(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = modifier
            .widthIn(max = VIEWER_MISSING_MAX_WIDTH)
            .padding(KnotworkTheme.spacing.sp5),
    ) {
        Icon(
            imageVector = AppIcons.ImageOff,
            contentDescription = null,
            tint = VIEWER_ON_SCRIM_DIM,
            modifier = Modifier.size(VIEWER_MISSING_ICON_SIZE),
        )
        Text(
            text = stringResource(R.string.knotwork_image_viewer_missing_title),
            style = KnotworkTextStyles.TitleMd,
            color = VIEWER_ON_SCRIM,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.knotwork_image_viewer_missing_body),
            style = KnotworkTextStyles.BodySm,
            color = VIEWER_ON_SCRIM_DIM,
            textAlign = TextAlign.Center,
        )
    }
}

/** Near-opaque dark scrim — viewer is always dark, in both themes. */
private val VIEWER_SCRIM = Color(red = 0x06, green = 0x08, blue = 0x0A, alpha = 0xF0)

/** Primary on-scrim text/icon colour. */
private val VIEWER_ON_SCRIM = Color(red = 0xF2, green = 0xF4, blue = 0xF6)

/** Muted on-scrim text colour (dimensions, body). */
private val VIEWER_ON_SCRIM_DIM = Color(red = 0xB6, green = 0xBD, blue = 0xC4)

private val VIEWER_BUTTON_SIZE = 44.dp
private val VIEWER_ICON_SIZE = 22.dp
private val VIEWER_MISSING_ICON_SIZE = 40.dp
private val VIEWER_MISSING_MAX_WIDTH = 320.dp
