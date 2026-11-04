package app.knotwork.design.screens.settings.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.text.Text
import app.knotwork.design.components.topbar.KnotworkTopAppBarShell
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Settings → Privacy → *Backup & restore*, and the *Back up* route over it.
 *
 * The screen says what a backup holds and offers the two actions. A route is full screen; its state changes in
 * place, crossfading (instant under reduced motion), so Back always means one thing: leave the route. While the
 * work runs there is no navigation icon — the screen's Back is the route's *Cancel*.
 *
 * @param state What to show.
 * @param callbacks What the user asks for.
 * @param modifier Layout modifier.
 * @param snackbarHost The app's snackbar host, shown at the foot of the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupContent(
    state: BackupViewState,
    callbacks: BackupCallbacks,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
) {
    val route = state.route
    if (route is Restoring) {
        // Committed: no bar, no action, no Back — the app restarts in a moment.
        RestoringScreen(modifier = modifier.fillMaxSize())
        return
    }
    val (title, nav) = when (route) {
        null -> stringResource(R.string.knotwork_backup_home_title) to BarNav.BACK
        is BackupWorking -> stringResource(R.string.knotwork_backup_backup_title) to BarNav.NONE
        is RestoreChecking -> stringResource(R.string.knotwork_backup_restore_title) to BarNav.NONE
        is RestoreRoute -> stringResource(R.string.knotwork_backup_restore_title) to BarNav.CLOSE
        else -> stringResource(R.string.knotwork_backup_backup_title) to BarNav.CLOSE
    }
    val onNav = when (route) {
        null -> callbacks.onBack
        is BackupSaved -> callbacks.onDone
        else -> callbacks.onCloseRoute
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { KnotworkTopAppBarShell { BackupTopBar(title = title, nav = nav, onNav = onNav) } },
        snackbarHost = snackbarHost,
        bottomBar = { route?.let { BackupRouteAction(it, callbacks) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        val reduced = KnotworkTheme.a11y.reducedMotion()
        // Keyed by the kind of state: a new state crossfades, an update of the same one (bytes) does not.
        AnimatedContent(
            targetState = route,
            contentKey = { it?.javaClass },
            transitionSpec = {
                if (reduced) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(tween(CROSSFADE_MS)) togetherWith fadeOut(tween(CROSSFADE_MS))
                }
            },
            label = "backup-route",
            // A system bar or cutout on the side (landscape) is this screen's to clear, as the pinned action does.
            modifier = Modifier.padding(padding).windowInsetsPadding(backupSideInsets),
        ) { shown ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = KnotworkTheme.spacing.sp4, vertical = KnotworkTheme.spacing.sp4),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp5),
            ) {
                BackupRouteBody(route = shown, restoreAvailable = state.restoreAvailable, callbacks = callbacks)
            }
        }
    }
}

/** What the top bar's navigation icon is. */
private enum class BarNav { BACK, CLOSE, NONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupTopBar(title: String, nav: BarNav, onNav: () -> Unit) {
    TopAppBar(
        title = { Text(text = title, style = KnotworkTextStyles.TitleMd, color = MaterialTheme.colorScheme.onSurface) },
        navigationIcon = {
            if (nav != BarNav.NONE) {
                IconButton(onClick = onNav) {
                    Icon(
                        imageVector = if (nav == BarNav.BACK) AppIcons.Back else AppIcons.X,
                        contentDescription = stringResource(
                            if (nav == BarNav.BACK) R.string.knotwork_settings_back else R.string.knotwork_backup_close,
                        ),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** The action pinned under each route's scroll. */
@Composable
private fun BackupRouteAction(route: BackupRoute, callbacks: BackupCallbacks) {
    BackupBottomAction {
        when (route) {
            is BackupForm -> KnotworkPrimaryButton(
                text = stringResource(R.string.knotwork_backup_choose_where),
                onClick = callbacks.onChooseWhere,
                enabled = route.canContinue,
                modifier = Modifier.fillMaxWidth(),
            )
            is BackupWorking -> BackupCancelButton(onClick = callbacks.onCancel)
            is BackupSaved -> KnotworkPrimaryButton(
                text = stringResource(R.string.knotwork_backup_done),
                onClick = callbacks.onDone,
                modifier = Modifier.fillMaxWidth(),
            )
            is BackupFailed -> KnotworkPrimaryButton(
                text = stringResource(R.string.knotwork_backup_try_again),
                onClick = callbacks.onTryAgain,
                modifier = Modifier.fillMaxWidth(),
            )
            is RestoreRoute -> RestoreRouteAction(route, callbacks)
        }
    }
}

/** The action pinned under a *Restore* route. */
@Composable
private fun RestoreRouteAction(route: RestoreRoute, callbacks: BackupCallbacks) {
    val restore = callbacks.restore
    when (route) {
        is RestoreProblem -> {
            val (label, action) = when (route.kind) {
                RestoreProblemKind.NO_SPACE -> R.string.knotwork_backup_try_again to restore.onTryAgain
                RestoreProblemKind.PHONE_KEY_UNAVAILABLE, RestoreProblemKind.NOT_STARTED ->
                    R.string.knotwork_backup_close to callbacks.onCloseRoute
                else -> R.string.knotwork_backup_choose_another to restore.onChooseAnother
            }
            KnotworkPrimaryButton(text = stringResource(label), onClick = action, modifier = Modifier.fillMaxWidth())
        }
        is RestorePassword -> KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_backup_check),
            onClick = restore.onCheck,
            enabled = route.canCheck,
            modifier = Modifier.fillMaxWidth(),
        )
        is RestoreChecking -> BackupCancelButton(onClick = restore.onCancelCheck)
        is RestoreSummary -> KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_backup_replace),
            onClick = restore.onReplace,
            modifier = Modifier.fillMaxWidth(),
        )
        Restoring -> Unit
    }
}

/** How long a state change inside a route crossfades. */
private const val CROSSFADE_MS = 180
