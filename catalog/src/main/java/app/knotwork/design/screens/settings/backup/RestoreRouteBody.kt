package app.knotwork.design.screens.settings.backup

import android.icu.text.ListFormatter
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.controls.KnotworkField
import app.knotwork.design.components.controls.KnotworkPasswordField
import app.knotwork.design.components.dialogs.TypedConfirmDialog
import app.knotwork.design.components.dialogs.TypedConfirmDialogState
import app.knotwork.design.components.misc.KnotworkLoader
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import java.text.NumberFormat

/**
 * The scrolling body of a *Restore* route: a problem with the file, the password, the check, or the summary. The
 * *Restoring…* screen is drawn whole by [BackupContent].
 *
 * @param route The route in front.
 * @param callbacks What the user asks for.
 */
@Composable
internal fun RestoreRouteBody(route: RestoreRoute, callbacks: RestoreCallbacks) {
    when (route) {
        is RestoreProblem -> RestoreProblemBody(route)
        is RestorePassword -> RestorePasswordBody(route, callbacks)
        is RestoreChecking -> RestoreCheckingBody(route)
        is RestoreSummary -> RestoreSummaryBody(route, callbacks)
        Restoring -> Unit
    }
}

/** A file that cannot be restored: why, and that nothing on the phone has changed. */
@Composable
private fun RestoreProblemBody(problem: RestoreProblem) {
    val warn = KnotworkTheme.extended.signalWarn
    val error = KnotworkTheme.extended.signalError
    val (icon, tone, title) = when (problem.kind) {
        RestoreProblemKind.NOT_A_BACKUP -> Triple(AppIcons.File, warn, R.string.knotwork_backup_not_backup_title)
        RestoreProblemKind.NEWER -> Triple(AppIcons.Refresh, warn, R.string.knotwork_backup_newer_title)
        RestoreProblemKind.DAMAGED -> Triple(AppIcons.AlertCircle, error, R.string.knotwork_backup_damaged_title)
        RestoreProblemKind.INCOMPLETE -> Triple(AppIcons.AlertCircle, error, R.string.knotwork_backup_incomplete_title)
        RestoreProblemKind.NO_SPACE -> Triple(AppIcons.AlertCircle, error, R.string.knotwork_backup_space_title)
        RestoreProblemKind.READ_FAILED -> Triple(
            AppIcons.AlertCircle,
            error,
            R.string.knotwork_backup_read_failed_title,
        )
        RestoreProblemKind.PHONE_KEY_UNAVAILABLE ->
            Triple(AppIcons.AlertCircle, error, R.string.knotwork_backup_phone_key_title)
        RestoreProblemKind.NOT_STARTED -> Triple(
            AppIcons.AlertCircle,
            error,
            R.string.knotwork_backup_not_started_title,
        )
    }
    BackupResultHead(icon = icon, tone = tone, title = stringResource(title))
    val strong = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.Medium)
    when (problem.kind) {
        RestoreProblemKind.NOT_A_BACKUP -> {
            // The file's name in mono, as the file rows show it.
            val name = problem.file?.name ?: stringResource(R.string.knotwork_backup_this_file)
            val body = stringResource(R.string.knotwork_backup_not_backup_body, name)
            val start = body.indexOf(name)
            Text(
                text = buildAnnotatedString {
                    append(body.substring(0, start))
                    withStyle(SpanStyle(fontFamily = KnotworkTextStyles.MonoBase.fontFamily)) { append(name) }
                    append(body.substring(start + name.length))
                },
                style = strong,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        else -> Text(text = problemBody(problem), style = strong, color = MaterialTheme.colorScheme.onSurface)
    }
    if (problem.kind == RestoreProblemKind.NEWER) {
        problem.file?.let { RestoreFileRow(it, withSize = false) }
    }
    if (problem.kind !in OPEN_PROBLEMS) {
        BackupNote(icon = AppIcons.Shield, text = stringResource(R.string.knotwork_backup_untouched))
    }
}

/** The body line of a problem other than [RestoreProblemKind.NOT_A_BACKUP]. */
@Composable
private fun problemBody(problem: RestoreProblem): String {
    val context = LocalContext.current
    return when (problem.kind) {
        RestoreProblemKind.NEWER -> stringResource(
            R.string.knotwork_backup_newer_body,
            problem.file?.writerVersion.orEmpty(),
            problem.phoneVersion.orEmpty(),
        )
        RestoreProblemKind.DAMAGED -> stringResource(R.string.knotwork_backup_damaged_body)
        RestoreProblemKind.INCOMPLETE -> stringResource(R.string.knotwork_backup_incomplete_body)
        RestoreProblemKind.NO_SPACE -> if (problem.neededBytes > 0) {
            stringResource(
                R.string.knotwork_backup_space_body,
                Formatter.formatShortFileSize(context, problem.neededBytes),
                Formatter.formatShortFileSize(context, problem.freeBytes),
            )
        } else {
            stringResource(R.string.knotwork_backup_space_ran_out)
        }
        RestoreProblemKind.READ_FAILED -> stringResource(R.string.knotwork_backup_read_failed_body)
        RestoreProblemKind.PHONE_KEY_UNAVAILABLE -> stringResource(R.string.knotwork_backup_phone_key_body)
        RestoreProblemKind.NOT_STARTED -> stringResource(R.string.knotwork_backup_not_started_body)
        RestoreProblemKind.NOT_A_BACKUP -> ""
    }
}

/** *Restore*: the file, what wrote it, and the one password field. */
@Composable
private fun RestorePasswordBody(form: RestorePassword, callbacks: RestoreCallbacks) {
    RestoreFileRow(form.file, withSize = true)
    Text(
        text = stringResource(R.string.knotwork_backup_restore_lead),
        style = KnotworkTextStyles.BodyBase,
        color = KnotworkTheme.extended.onSurface2,
    )
    val wrong = stringResource(R.string.knotwork_backup_wrong_password)
    // A refused password is read out with the field. Focus is not forced back into it: the field holds a plain
    // string, so a programmatic focus puts the caret before the kept text, where a retyped password would land.
    KnotworkField(
        label = stringResource(R.string.knotwork_backup_pw_label),
        isError = form.wrongPassword,
        errorText = wrong,
        modifier = Modifier.semantics {
            if (form.wrongPassword) {
                liveRegion = LiveRegionMode.Polite
                error(wrong)
            }
        },
    ) {
        KnotworkPasswordField(
            value = form.password,
            onValueChange = callbacks.onPasswordChange,
            isError = form.wrongPassword,
            // Done runs the check: one field, and checking changes nothing.
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions(onDone = { if (form.canCheck) callbacks.onCheck() }),
            autofillType = ContentType.Password,
        )
    }
}

/** *Checking the backup*: the file, the bar, the two named steps and that nothing is replaced yet. */
@Composable
private fun RestoreCheckingBody(checking: RestoreChecking) {
    Text(
        text = stringResource(R.string.knotwork_backup_checking_title),
        style = KnotworkTextStyles.TitleLg,
        color = MaterialTheme.colorScheme.onSurface,
    )
    RestoreFileRow(checking.file, withSize = false)
    BackupProgress(
        measuredStep = checking.step == RestoreCheckStep.FILE,
        bytesDone = checking.bytesDone,
        bytesTotal = checking.bytesTotal,
    )
    BackupStepList(
        names = listOf(
            stringResource(R.string.knotwork_backup_step_check_password),
            stringResource(R.string.knotwork_backup_step_check_file),
        ),
        current = checking.step.ordinal,
    )
    BackupNote(icon = AppIcons.Shield, text = stringResource(R.string.knotwork_backup_nothing_yet))
}

/** *Ready to restore*: what comes in, what is not carried, and what this phone loses — then the typed confirm. */
@Composable
private fun RestoreSummaryBody(summary: RestoreSummary, callbacks: RestoreCallbacks) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Text(
            text = stringResource(R.string.knotwork_backup_summary_title),
            style = KnotworkTextStyles.TitleXl,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalSuccess,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.knotwork_backup_summary_checked),
                style = KnotworkTextStyles.BodySm,
                color = KnotworkTheme.extended.signalSuccess,
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
        Text(
            text = stringResource(R.string.knotwork_backup_summary_from).uppercase(),
            style = KnotworkTextStyles.LabelSm,
            color = KnotworkTheme.extended.onSurface2,
            modifier = Modifier.semantics { heading() },
        )
        BackupStats(cells = statCells(summary.fromBackup))
        Column {
            SummaryLine(
                icon = AppIcons.Key,
                text = stringResource(
                    if (summary.keysIncluded) {
                        R.string.knotwork_backup_summary_keys_in
                    } else {
                        R.string.knotwork_backup_summary_keys_out
                    },
                ),
            )
            if (summary.modelsToDownload > 0) {
                SummaryLine(
                    icon = AppIcons.Download2,
                    text = pluralStringResource(
                        R.plurals.knotwork_backup_summary_models,
                        summary.modelsToDownload,
                        summary.modelsToDownload,
                        Formatter.formatShortFileSize(context, summary.modelsBytes),
                    ),
                )
            }
            SummaryLine(
                icon = AppIcons.History,
                text = stringResource(R.string.knotwork_backup_summary_never),
                dim = true,
            )
        }
    }
    WillBeLost(summary.onThisPhone)
    summary.confirmInput?.let { input ->
        val keyword = stringResource(R.string.knotwork_backup_confirm_keyword)
        TypedConfirmDialog(
            state = TypedConfirmDialogState(
                title = stringResource(R.string.knotwork_backup_confirm_title),
                body = stringResource(R.string.knotwork_backup_confirm_body),
                keyword = keyword,
                hint = stringResource(R.string.knotwork_backup_confirm_prompt, keyword),
                pendingInput = input,
            ),
            confirmLabel = stringResource(R.string.knotwork_backup_confirm_action),
            cancelLabel = stringResource(R.string.knotwork_backup_cancel),
            onInputChange = callbacks.onConfirmInputChange,
            onConfirm = callbacks.onConfirmReplace,
            onCancel = callbacks.onConfirmCancel,
            fieldTestTag = CONFIRM_FIELD_TAG,
            confirmTestTag = CONFIRM_BUTTON_TAG,
        )
    }
}

/** One line of the summary under the counts, with a divider above. */
@Composable
private fun SummaryLine(icon: ImageVector, text: String, dim: Boolean = false) {
    HorizontalDivider(color = KnotworkTheme.extended.divider)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = KnotworkTheme.spacing.sp2),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = text,
            style = KnotworkTextStyles.BodyBase,
            color = if (dim) KnotworkTheme.extended.onSurface2 else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** *Will be lost*: everything this phone holds now, in error ink, read whole. */
@Composable
private fun WillBeLost(onThisPhone: RestoreCounts) {
    val error = KnotworkTheme.extended.signalError
    val numbers = NumberFormat.getIntegerInstance()
    val parts = listOfNotNull(
        onThisPhone.chats.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.knotwork_backup_n_chats, it, numbers.format(it))
        },
        onThisPhone.memories.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.knotwork_backup_n_memories, it, numbers.format(it))
        },
        onThisPhone.pipelines.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.knotwork_backup_n_pipelines, it, numbers.format(it))
        },
        onThisPhone.files.takeIf { it > 0 }?.let {
            pluralStringResource(R.plurals.knotwork_backup_n_files, it, numbers.format(it))
        },
    )
    val body = if (parts.isEmpty()) {
        stringResource(R.string.knotwork_backup_summary_lost_settings)
    } else {
        stringResource(R.string.knotwork_backup_summary_lost_body, ListFormatter.getInstance().format(parts))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(error.copy(alpha = TINT_ALPHA), KnotworkTheme.shapes.md)
            .border(1.dp, error.copy(alpha = OUTLINE_ALPHA), KnotworkTheme.shapes.md)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(imageVector = AppIcons.Warn, contentDescription = null, tint = error, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.knotwork_backup_summary_lost),
                style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.Bold),
                color = error,
            )
        }
        Text(text = body, style = KnotworkTextStyles.BodyBase, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The picked file: its name, and what wrote it and when — with its size where [withSize]. */
@Composable
private fun RestoreFileRow(file: RestoreFile, withSize: Boolean) {
    val context = LocalContext.current
    val made = if (file.writerVersion != null && file.createdLabel != null) {
        stringResource(R.string.knotwork_backup_made_with, file.writerVersion, file.createdLabel)
    } else {
        null
    }
    val size = file.sizeBytes?.takeIf { withSize }?.let { Formatter.formatShortFileSize(context, it) }
    BackupFileRow(name = file.name, meta = listOfNotNull(made, size).joinToString(" · ").ifEmpty { null })
}

/** The four counts as stat cells: the number, and the noun under it. */
@Composable
private fun statCells(counts: RestoreCounts): List<Pair<String, String>> {
    val numbers = NumberFormat.getIntegerInstance()
    return listOf(
        numbers.format(counts.chats) to pluralStringResource(R.plurals.knotwork_backup_stat_chats, counts.chats),
        numbers.format(counts.memories) to
            pluralStringResource(R.plurals.knotwork_backup_stat_memories, counts.memories),
        numbers.format(counts.pipelines) to
            pluralStringResource(R.plurals.knotwork_backup_stat_pipelines, counts.pipelines),
        numbers.format(counts.files) to pluralStringResource(R.plurals.knotwork_backup_stat_files, counts.files),
    )
}

/**
 * *Restoring…*: the loader and one line, read once — the restore is committed and the app restarts.
 *
 * @param modifier Layout modifier.
 */
@Composable
internal fun RestoringScreen(modifier: Modifier = Modifier) {
    val title = stringResource(R.string.knotwork_backup_restoring)
    val line = stringResource(R.string.knotwork_backup_restoring_sub)
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp)
            // Read once, as the two lines; the loader's own label is decoration here.
            .clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "$title $line"
            },
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KnotworkLoader()
        Text(
            text = title,
            style = KnotworkTextStyles.TitleLg,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = line,
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurface2,
            textAlign = TextAlign.Center,
        )
    }
}

/** Problems with the file itself, found before anything was checked: they need no "nothing changed" note. */
private val OPEN_PROBLEMS = setOf(RestoreProblemKind.NOT_A_BACKUP, RestoreProblemKind.NEWER)

/** The *Will be lost* container: the error colour at 14 %, its outline at 40 %. */
private const val TINT_ALPHA = 0.14f
private const val OUTLINE_ALPHA = 0.4f

/** Test tag of the confirm dialog's field. */
internal const val CONFIRM_FIELD_TAG = "restore-confirm-field"

/** Test tag of the confirm dialog's *Replace* button. */
internal const val CONFIRM_BUTTON_TAG = "restore-confirm-button"
