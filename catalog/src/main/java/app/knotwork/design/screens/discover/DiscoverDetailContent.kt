@file:Suppress("MatchingDeclarationName") // File hosts DiscoverDetailContent and its helper composables.

package app.knotwork.design.screens.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.knotwork.design.R
import app.knotwork.design.components.buttons.KnotworkPrimaryButton
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.components.controls.KnotworkTextField
import app.knotwork.design.components.misc.EmptyState
import app.knotwork.design.components.misc.StripedPlaceholder
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Number of skeleton rows rendered while the detail loads. */
private const val LOADING_SKELETON_ROWS = 3

/** Height of a loading skeleton row. */
private val SkeletonRowHeight = 64.dp

/**
 * Stateless model-discovery detail surface: repository header, access-gated
 * notice with an inline token field, the installable `.litertlm` file list, a
 * "View on Hugging Face" link and the license-confirmation dialog shown before
 * any download starts.
 *
 * @param state immutable view state.
 * @param callbacks user-action sink.
 * @param modifier optional layout modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverDetailContent(
    state: DiscoverDetailViewState,
    modifier: Modifier = Modifier,
    callbacks: DiscoverDetailCallbacks = noopDiscoverDetailCallbacks(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            app.knotwork.design.components.topbar.KnotworkTopAppBarShell {
                DetailTopBar(title = state.title, callbacks = callbacks)
            }
        },
        // The host `AppShellScaffold` already absorbs the system bars and the
        // in-app bottom-nav strip via its inner padding; letting this Scaffold
        // default to `safeDrawing` would mis-inset the body relative to the nav.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (state.visualState) {
                DiscoverDetailVisualState.Loading -> DetailLoadingState()
                DiscoverDetailVisualState.Error -> DetailErrorState(state = state, callbacks = callbacks)
                DiscoverDetailVisualState.Loaded -> DetailBody(state = state, callbacks = callbacks)
            }
        }
    }
    state.pendingLicenseFileName?.let { fileName ->
        LicenseConfirmDialog(license = state.license, fileName = fileName, callbacks = callbacks)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTopBar(title: String, callbacks: DiscoverDetailCallbacks) {
    TopAppBar(
        title = {
            Text(
                text = title.ifBlank { stringResource(R.string.knotwork_discover_detail_title_fallback) },
                style = KnotworkTextStyles.TitleMd,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = callbacks.onBack) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = stringResource(R.string.knotwork_discover_back_cd),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun DetailBody(state: DiscoverDetailViewState, callbacks: DiscoverDetailCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(KnotworkTheme.spacing.sp4),
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
    ) {
        DetailHeader(state = state)
        if (state.gated) {
            GatedNotice(state = state, callbacks = callbacks)
        }
        SectionHeader(text = stringResource(R.string.knotwork_discover_detail_files_header))
        state.files.forEach { file ->
            FileRow(file = file, callbacks = callbacks)
            HorizontalDivider(color = KnotworkTheme.extended.divider)
        }
        ModelCardLink(onClick = callbacks.onOpenModelCard)
    }
}

@Composable
private fun DetailHeader(state: DiscoverDetailViewState) {
    Column(verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1)) {
        Text(
            text = state.repoId,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        Text(
            text = state.metaLine,
            style = KnotworkTextStyles.MonoSm,
            color = KnotworkTheme.extended.onSurfaceMuted,
        )
        if (state.license != null) {
            Text(
                text = stringResource(R.string.knotwork_discover_detail_license, state.license),
                style = KnotworkTextStyles.MonoSm,
                color = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun GatedNotice(state: DiscoverDetailViewState, callbacks: DiscoverDetailCallbacks) {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KnotworkTheme.spacing.sp1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalWarn,
            )
            Text(
                text = stringResource(R.string.knotwork_discover_gated_notice),
                style = KnotworkTextStyles.BodySm,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        KnotworkTextField(
            value = state.tokenInput,
            onValueChange = callbacks.onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.knotwork_discover_token_placeholder),
            leadingIcon = AppIcons.Key,
            trailingIcon = if (state.tokenRevealed) AppIcons.EyeOff else AppIcons.Eye,
            onTrailingClick = callbacks.onToggleTokenReveal,
            visualTransformation = if (state.tokenRevealed) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            monospace = true,
            contentDescription = stringResource(R.string.knotwork_discover_token_placeholder),
        )
        KnotworkTextButton(
            text = stringResource(R.string.knotwork_discover_token_paste),
            onClick = callbacks.onTokenPaste,
        )
    }
}

@Composable
private fun FileRow(file: DiscoverFileRow, callbacks: DiscoverDetailCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = KnotworkTheme.spacing.sp2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.fileName,
                style = KnotworkTextStyles.MonoBase,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.sizeLabel != null) {
                Text(
                    text = file.sizeLabel,
                    style = KnotworkTextStyles.MonoSm,
                    color = KnotworkTheme.extended.onSurfaceMuted,
                )
            }
            (file.status as? DiscoverFileStatus.Downloading)?.let { downloading ->
                Spacer(modifier = Modifier.height(KnotworkTheme.spacing.sp1))
                LinearProgressIndicator(
                    progress = { downloading.progress / PERCENT_FULL },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FileTrailingAction(file = file, callbacks = callbacks)
    }
}

@Composable
private fun FileTrailingAction(file: DiscoverFileRow, callbacks: DiscoverDetailCallbacks) {
    when (file.status) {
        DiscoverFileStatus.Idle -> KnotworkPrimaryButton(
            text = stringResource(R.string.knotwork_discover_install),
            onClick = { callbacks.onInstallClick(file.fileName) },
            leadingIcon = AppIcons.Download,
        )
        DiscoverFileStatus.Installed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        ) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = KnotworkTheme.extended.signalSuccess,
            )
            Text(
                text = stringResource(R.string.knotwork_discover_installed),
                style = KnotworkTextStyles.LabelLg,
                color = KnotworkTheme.extended.signalSuccess,
            )
        }
        is DiscoverFileStatus.Downloading -> IconButton(onClick = { callbacks.onCancelInstall(file.fileName) }) {
            Icon(
                imageVector = AppIcons.X,
                contentDescription = stringResource(R.string.knotwork_discover_cancel_cd),
                tint = KnotworkTheme.extended.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun ModelCardLink(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp1),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = KnotworkTheme.spacing.sp2),
    ) {
        Icon(
            imageVector = AppIcons.External,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.knotwork_discover_view_on_hf),
            style = KnotworkTextStyles.LabelLg,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LicenseConfirmDialog(license: String?, fileName: String, callbacks: DiscoverDetailCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onLicenseDismiss,
        title = { Text(text = stringResource(R.string.knotwork_discover_license_title)) },
        text = {
            Text(
                text = if (license != null) {
                    stringResource(R.string.knotwork_discover_license_body, license)
                } else {
                    stringResource(R.string.knotwork_discover_license_body_unknown)
                },
            )
        },
        confirmButton = {
            KnotworkPrimaryButton(
                text = stringResource(R.string.knotwork_discover_license_accept),
                onClick = { callbacks.onLicenseConfirm(fileName) },
            )
        },
        dismissButton = {
            KnotworkSecondaryButton(
                text = stringResource(R.string.knotwork_discover_license_cancel),
                onClick = callbacks.onLicenseDismiss,
            )
        },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.MonoSm,
        color = KnotworkTheme.extended.onSurfaceMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = KnotworkTheme.spacing.sp2),
    )
}

@Composable
private fun DetailLoadingState() {
    Column(
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxSize()
            .padding(KnotworkTheme.spacing.sp4),
    ) {
        repeat(LOADING_SKELETON_ROWS) {
            StripedPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SkeletonRowHeight),
            )
        }
    }
}

@Composable
private fun DetailErrorState(state: DiscoverDetailViewState, callbacks: DiscoverDetailCallbacks) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2),
        modifier = Modifier
            .fillMaxSize()
            .padding(KnotworkTheme.spacing.sp6),
    ) {
        Icon(
            imageVector = AppIcons.Warn,
            contentDescription = null,
            tint = KnotworkTheme.extended.signalError,
            modifier = Modifier.size(KnotworkTheme.spacing.sp16),
        )
        EmptyState(
            title = stringResource(R.string.knotwork_discover_error_title),
            subtitle = state.errorMessage.orEmpty(),
            illustration = { /* Icon already drawn above. */ },
            ctaLabel = stringResource(R.string.knotwork_discover_error_retry),
            onCtaClick = callbacks.onRetry,
        )
    }
}

/** Float denominator converting a 0..100 percentage to a 0f..1f progress. */
private const val PERCENT_FULL = 100f
