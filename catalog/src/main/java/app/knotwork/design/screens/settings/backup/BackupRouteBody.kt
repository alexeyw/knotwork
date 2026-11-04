package app.knotwork.design.screens.settings.backup

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.controls.KnotworkField
import app.knotwork.design.components.controls.KnotworkPasswordField
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles
import java.text.NumberFormat

/**
 * The scrolling body of the *Backup & restore* screen for what is in front: the screen itself, or a route.
 *
 * @param route The route in front, or `null` for the screen.
 * @param restoreAvailable Whether the screen offers *Restore from file…*.
 * @param callbacks What the user asks for.
 */
@Composable
internal fun BackupRouteBody(route: BackupRoute?, restoreAvailable: Boolean, callbacks: BackupCallbacks) {
    when (route) {
        null -> BackupHome(restoreAvailable = restoreAvailable, callbacks = callbacks)
        is BackupForm -> BackupFormBody(route, callbacks)
        is BackupWorking -> BackupWorkingBody(route)
        is BackupSaved -> BackupSavedBody(route)
        is BackupFailed -> BackupFailedBody(route)
        is RestoreRoute -> RestoreRouteBody(route, callbacks.restore)
    }
}

/** The screen itself: what a backup holds, the actions, and that nothing is uploaded. */
@Composable
private fun BackupHome(restoreAvailable: Boolean, callbacks: BackupCallbacks) {
    Text(
        text = stringResource(R.string.knotwork_backup_home_intro),
        style = KnotworkTextStyles.BodyBase,
        color = KnotworkTheme.extended.onSurface2,
    )
    BackupActionGroup {
        BackupActionRow(
            icon = AppIcons.ExportFile,
            title = stringResource(R.string.knotwork_backup_action_backup),
            subtitle = stringResource(R.string.knotwork_backup_action_backup_sub),
            onClick = callbacks.onStartBackup,
        )
        if (restoreAvailable) {
            HorizontalDivider(color = KnotworkTheme.extended.divider)
            BackupActionRow(
                icon = AppIcons.ImportFile,
                title = stringResource(R.string.knotwork_backup_action_restore),
                subtitle = stringResource(R.string.knotwork_backup_action_restore_sub),
                onClick = callbacks.onStartRestore,
            )
        }
    }
    BackupNote(icon = AppIcons.Shield, text = stringResource(R.string.knotwork_backup_home_no_server))
}

/** *Back up*: the password, twice, and the secrets checkbox. */
@Composable
private fun BackupFormBody(form: BackupForm, callbacks: BackupCallbacks) {
    BackupNote(icon = AppIcons.Key, text = stringResource(R.string.knotwork_backup_backup_lead), strong = true)
    KnotworkField(
        label = stringResource(R.string.knotwork_backup_pw_label),
        helper = stringResource(R.string.knotwork_backup_pw_rule),
        isError = form.passwordTooShort,
        errorText = stringResource(R.string.knotwork_backup_pw_short),
    ) {
        KnotworkPasswordField(
            value = form.password,
            onValueChange = callbacks.onPasswordChange,
            isError = form.passwordTooShort,
            imeAction = ImeAction.Next,
            autofillType = ContentType.NewPassword,
            modifier = Modifier.onFocusLost(callbacks.onPasswordFocusLost),
        )
    }
    KnotworkField(
        label = stringResource(R.string.knotwork_backup_pw2_label),
        isError = form.confirmationMismatch,
        errorText = stringResource(R.string.knotwork_backup_pw2_mismatch),
    ) {
        KnotworkPasswordField(
            value = form.confirmation,
            onValueChange = callbacks.onConfirmationChange,
            isError = form.confirmationMismatch,
            // Done hides the keyboard; it never opens the picker.
            imeAction = ImeAction.Done,
            keyboardActions = KeyboardActions.Default,
            autofillType = ContentType.NewPassword,
            modifier = Modifier.onFocusLost(callbacks.onConfirmationFocusLost),
        )
    }
    HorizontalDivider(color = KnotworkTheme.extended.divider)
    BackupCheckboxRow(
        checked = form.includeSecrets,
        label = stringResource(R.string.knotwork_backup_secrets_label),
        explanation = stringResource(R.string.knotwork_backup_secrets_sub),
        onToggle = callbacks.onToggleSecrets,
    )
}

/** Calls [onLost] when the field had focus and no longer has it. */
@Composable
private fun Modifier.onFocusLost(onLost: () -> Unit): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { change ->
        if (focused && !change.isFocused) onLost()
        focused = change.isFocused
    }
}

/** *Backing up*: the file, the bar in bytes, the named steps and *Keep Knotwork open*. */
@Composable
private fun BackupWorkingBody(working: BackupWorking) {
    Text(
        text = stringResource(R.string.knotwork_backup_working_title),
        style = KnotworkTextStyles.TitleLg,
        color = MaterialTheme.colorScheme.onSurface,
    )
    BackupFileRow(name = working.fileName, meta = null)
    BackupProgress(
        measuredStep = working.step != BackupWorkStep.PASSWORD,
        bytesDone = working.bytesDone,
        bytesTotal = working.bytesTotal,
    )
    BackupStepList(
        names = listOf(
            stringResource(R.string.knotwork_backup_step_password),
            stringResource(R.string.knotwork_backup_step_db),
            stringResource(R.string.knotwork_backup_step_files),
        ),
        current = working.step.ordinal,
    )
    BackupNote(icon = AppIcons.Info, text = stringResource(R.string.knotwork_backup_keep_open))
}

/**
 * The named steps under the bar — done, now, next — as one polite live region whose text is the current step, so
 * each new step is announced by name; the glyphs are decorative and the bytes are never announced.
 *
 * @param names The steps' names, in order.
 * @param current The index of the step running now.
 */
@Composable
internal fun BackupStepList(names: List<String>, current: Int) {
    BackupSteps(
        steps = names.mapIndexed { index, name ->
            name to when {
                index < current -> StepMark.DONE
                index == current -> StepMark.NOW
                else -> StepMark.NEXT
            }
        },
        modifier = Modifier.clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = names[current]
        },
    )
}

/**
 * The bar: indeterminate while the password is worked on (about a second, nothing to measure — an empty bar under
 * reduced motion, the step label carries it), then determinate in bytes, easing to each new value.
 *
 * @param measuredStep Whether the step running now is measured in bytes.
 * @param bytesDone Bytes done so far.
 * @param bytesTotal Bytes in all; 0 while not known.
 */
@Composable
internal fun BackupProgress(measuredStep: Boolean, bytesDone: Long, bytesTotal: Long) {
    val context = LocalContext.current
    val reduced = KnotworkTheme.a11y.reducedMotion()
    val measured = measuredStep && bytesTotal > 0
    val bytesText = if (measured) {
        stringResource(
            R.string.knotwork_backup_bytes,
            Formatter.formatShortFileSize(context, bytesDone),
            Formatter.formatShortFileSize(context, bytesTotal),
        )
    } else {
        null
    }
    val fraction by animateFloatAsState(
        targetValue = if (measured) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f,
        animationSpec = if (reduced) snap() else tween(BAR_EASE_MS),
        label = "backup-bytes",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        val barModifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .semantics { if (bytesText != null) stateDescription = bytesText }
        if (measured || reduced) {
            LinearProgressIndicator(progress = { fraction }, modifier = barModifier)
        } else {
            LinearProgressIndicator(modifier = barModifier)
        }
        Text(
            text = bytesText ?: " ",
            style = KnotworkTextStyles.MonoBase,
            color = KnotworkTheme.extended.onSurface2,
            // Holds the line's height before the size is known; nothing to read then.
            modifier = if (bytesText == null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/** *Backup saved*: the file, what is in it, whether the keys are, and the password reminder. */
@Composable
private fun BackupSavedBody(saved: BackupSaved) {
    val context = LocalContext.current
    val numbers = NumberFormat.getIntegerInstance()
    BackupResultHead(
        icon = AppIcons.Check,
        tone = KnotworkTheme.extended.signalSuccess,
        title = stringResource(R.string.knotwork_backup_saved_title),
    )
    BackupFileRow(name = saved.fileName, meta = Formatter.formatShortFileSize(context, saved.sizeBytes))
    BackupStats(
        cells = listOf(
            numbers.format(saved.chats) to pluralStringResource(R.plurals.knotwork_backup_stat_chats, saved.chats),
            numbers.format(saved.memories) to
                pluralStringResource(R.plurals.knotwork_backup_stat_memories, saved.memories),
            numbers.format(saved.pipelines) to
                pluralStringResource(R.plurals.knotwork_backup_stat_pipelines, saved.pipelines),
            numbers.format(saved.files) to pluralStringResource(R.plurals.knotwork_backup_stat_files, saved.files),
        ),
    )
    if (saved.skippedFiles > 0) {
        BackupNote(
            icon = AppIcons.Info,
            text = pluralStringResource(
                R.plurals.knotwork_backup_saved_skipped,
                saved.skippedFiles,
                numbers.format(saved.skippedFiles),
            ),
        )
    }
    BackupNote(
        icon = AppIcons.Key,
        text = stringResource(
            if (saved.keysIncluded) R.string.knotwork_backup_saved_keys_in else R.string.knotwork_backup_saved_keys_out,
        ),
    )
    BackupNote(icon = AppIcons.Lock, text = stringResource(R.string.knotwork_backup_saved_reminder), strong = true)
}

/** *Backup not saved*: why, and whether the unfinished file is gone — or which one to delete. */
@Composable
private fun BackupFailedBody(failed: BackupFailed) {
    val context = LocalContext.current
    BackupResultHead(
        icon = AppIcons.AlertCircle,
        tone = KnotworkTheme.extended.signalError,
        title = stringResource(R.string.knotwork_backup_failed_title),
    )
    Text(
        text = when (failed.reason) {
            BackupFailReason.NO_SPACE -> stringResource(
                R.string.knotwork_backup_failed_space,
                Formatter.formatShortFileSize(context, failed.neededBytes),
            )
            BackupFailReason.WRITE_FAILED -> stringResource(R.string.knotwork_backup_failed_write)
            BackupFailReason.PHONE_FULL -> stringResource(R.string.knotwork_backup_failed_phone_full)
            BackupFailReason.READ_FAILED -> stringResource(R.string.knotwork_backup_failed_read)
        },
        style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurface,
    )
    // The provider refused the delete: the file is named, as the cancel snackbar names it.
    val leftBehind = failed.fileName?.takeUnless { failed.fileDeleted }
    if (failed.fileDeleted || leftBehind != null) {
        Text(
            text = if (leftBehind == null) {
                stringResource(R.string.knotwork_backup_failed_deleted)
            } else {
                stringResource(R.string.knotwork_backup_failed_left, leftBehind)
            },
            style = KnotworkTextStyles.BodyBase,
            color = KnotworkTheme.extended.onSurface2,
        )
    }
}

/** *Cancel*, while the work runs. */
@Composable
internal fun BackupCancelButton(onClick: () -> Unit) {
    KnotworkSecondaryButton(
        text = stringResource(R.string.knotwork_backup_cancel),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** How long the bar eases to a new byte count. */
private const val BAR_EASE_MS = 280
