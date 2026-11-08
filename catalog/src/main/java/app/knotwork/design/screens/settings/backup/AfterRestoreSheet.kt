package app.knotwork.design.screens.settings.backup

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * What is left to do after a restore, for the sheet shown over the chat once the app has restarted.
 *
 * @property madeOnLabel When the restored backup was made, formatted for the user; `null` when not known.
 * @property models The backup's models whose file this phone does not have.
 * @property enterKeysAgain Whether the sheet asks for the API keys, Hugging Face token and MCP sign-ins to be
 *   entered again; the app decides it from its restore report.
 * @property tasksLeftBehind Whether the phone the backup came from had tasks the agent scheduled; they are never
 *   carried.
 */
@Immutable
data class AfterRestoreState(
    val madeOnLabel: String?,
    val models: List<AfterRestoreModel>,
    val enterKeysAgain: Boolean,
    val tasksLeftBehind: Boolean,
)

/**
 * One model to download again.
 *
 * @property name Its file name.
 * @property sizeBytes Its size.
 * @property sourceKnown Whether the backup recorded where it came from: *Download* when it did, *Discover* when not.
 * @property progress Its download's progress in percent while it runs; `null` when it does not.
 * @property canDownload Whether *Download* is enabled — one download runs at a time.
 * @property failure Why its last download stopped without installing it; `null` when none did, or while one runs.
 */
@Immutable
data class AfterRestoreModel(
    val name: String,
    val sizeBytes: Long,
    val sourceKnown: Boolean,
    val progress: Int? = null,
    val canDownload: Boolean = true,
    val failure: AfterRestoreDownloadFailure? = null,
)

/** Why a model's download stopped without installing it, in the words its row on the after-restore sheet shows. */
enum class AfterRestoreDownloadFailure {
    /**
     * Hugging Face refused it (401 or 403) — what an access-gated repository answers without a token, or with one
     * whose account has not accepted the model's licence.
     */
    NeedsAccess,

    /** Anything else: another HTTP error, a server that did not answer, or a file that could not be stored. */
    Failed,

    /** It paused at the time a download may run in the background. */
    Paused,

    /** The file did not match the checksum Hugging Face published for it, and was deleted. */
    ChecksumMismatch,
}

/**
 * What the after-restore sheet asks the app to do.
 *
 * @property onDownload *Download* was tapped on the model of this file name.
 * @property onCancelDownload The running download of the model of this file name was cancelled.
 * @property onDiscover *Discover* was tapped.
 * @property onDone *Done* was tapped, or the sheet dismissed: the report is not shown again.
 */
@Immutable
data class AfterRestoreCallbacks(
    val onDownload: (String) -> Unit = {},
    val onCancelDownload: (String) -> Unit = {},
    val onDiscover: () -> Unit = {},
    val onDone: () -> Unit = {},
)

/**
 * The after-restore sheet over the chat: the models to download again, and what was not carried. Downloads go on
 * after it closes.
 *
 * @param state What is left to do.
 * @param callbacks What the user asks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfterRestoreSheet(state: AfterRestoreState, callbacks: AfterRestoreCallbacks) {
    ModalBottomSheet(
        onDismissRequest = callbacks.onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AfterRestoreSheetContent(state, callbacks)
    }
}

/**
 * The after-restore sheet's content, without the sheet around it.
 *
 * @param state What is left to do.
 * @param callbacks What the user asks for.
 * @param modifier Layout modifier.
 */
@Composable
fun AfterRestoreSheetContent(
    state: AfterRestoreState,
    callbacks: AfterRestoreCallbacks,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.knotwork_backup_after_title)
    Column(modifier = modifier.fillMaxWidth().semantics { paneTitle = title }) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = KnotworkTheme.spacing.sp2,
                    bottom = KnotworkTheme.spacing.sp3,
                ),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
                Text(
                    text = title,
                    style = KnotworkTextStyles.TitleXl,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                state.madeOnLabel?.let {
                    Text(
                        text = stringResource(R.string.knotwork_backup_after_sub, it),
                        style = KnotworkTextStyles.BodySm,
                        color = KnotworkTheme.extended.onSurface2,
                    )
                }
            }
            if (state.models.isNotEmpty()) {
                Column {
                    Text(
                        text = stringResource(R.string.knotwork_backup_after_models).uppercase(),
                        style = KnotworkTextStyles.LabelSm,
                        color = KnotworkTheme.extended.onSurface2,
                        modifier = Modifier.padding(bottom = KnotworkTheme.spacing.sp1).semantics { heading() },
                    )
                    state.models.forEach { AfterRestoreModelRow(it, callbacks) }
                }
            }
            if (state.enterKeysAgain || state.tasksLeftBehind) {
                HorizontalDivider(color = KnotworkTheme.extended.divider)
                if (state.enterKeysAgain) {
                    BackupNote(icon = AppIcons.Key, text = stringResource(R.string.knotwork_backup_after_keys))
                }
                if (state.tasksLeftBehind) {
                    BackupNote(icon = AppIcons.History, text = stringResource(R.string.knotwork_backup_after_tasks))
                }
            }
        }
        HorizontalDivider(color = KnotworkTheme.extended.divider)
        Box(modifier = Modifier.fillMaxWidth().padding(KnotworkTheme.spacing.sp4)) {
            KnotworkPrimaryButton(
                text = stringResource(R.string.knotwork_backup_done),
                onClick = callbacks.onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A model to download again: its tile, name and size — and why its last download failed — then *Download*, *Discover*
 * or the running download.
 */
@Composable
private fun AfterRestoreModelRow(model: AfterRestoreModel, callbacks: AfterRestoreCallbacks) {
    val context = LocalContext.current
    // At large text the action goes under the name, so the name keeps the row's width.
    val stacked = KnotworkTheme.a11y.fontScale() >= STACKED_FONT_SCALE
    HorizontalDivider(color = KnotworkTheme.extended.divider)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = KnotworkTheme.spacing.sp3),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp).background(KnotworkTheme.extended.surface2, KnotworkTheme.shapes.sm),
        ) {
            Icon(
                imageVector = AppIcons.Chip,
                contentDescription = null,
                tint = KnotworkTheme.extended.onSurface2,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = model.name,
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = Formatter.formatShortFileSize(context, model.sizeBytes),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurface2,
            )
            if (!model.sourceKnown) {
                Text(
                    text = stringResource(R.string.knotwork_backup_model_unknown),
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurface2,
                )
            }
            model.failure?.let { failure ->
                Text(
                    text = stringResource(
                        when (failure) {
                            AfterRestoreDownloadFailure.NeedsAccess -> R.string.knotwork_backup_download_needs_access
                            AfterRestoreDownloadFailure.Failed -> R.string.knotwork_backup_download_failed
                            AfterRestoreDownloadFailure.Paused -> R.string.knotwork_backup_download_paused
                            AfterRestoreDownloadFailure.ChecksumMismatch -> R.string.knotwork_backup_download_mismatch
                        },
                    ),
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.signalError,
                )
            }
            model.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress / PERCENT },
                    modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = KnotworkTheme.spacing.sp1),
                )
            }
            if (stacked && model.progress == null) {
                Box(modifier = Modifier.padding(top = KnotworkTheme.spacing.sp1)) { ModelAction(model, callbacks) }
            }
        }
        when {
            model.progress != null -> IconButton(onClick = { callbacks.onCancelDownload(model.name) }) {
                Icon(
                    imageVector = AppIcons.X,
                    contentDescription = stringResource(R.string.knotwork_backup_cancel_download),
                    tint = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            !stacked -> ModelAction(model, callbacks)
        }
    }
}

/** *Download* for a model whose source is known, *Discover* for one whose is not. */
@Composable
private fun ModelAction(model: AfterRestoreModel, callbacks: AfterRestoreCallbacks) {
    if (model.sourceKnown) {
        KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_backup_download),
            onClick = { callbacks.onDownload(model.name) },
            enabled = model.canDownload,
            leadingIcon = AppIcons.Download,
            size = KnotworkButtonSize.Sm,
        )
    } else {
        KnotworkSecondaryButton(
            text = stringResource(R.string.knotwork_backup_discover),
            onClick = callbacks.onDiscover,
            size = KnotworkButtonSize.Sm,
        )
    }
}

/** From this font scale up a model's action goes under its name. */
private const val STACKED_FONT_SCALE = 1.5f

/** Percent in a whole, for the progress bar. */
private const val PERCENT = 100f
