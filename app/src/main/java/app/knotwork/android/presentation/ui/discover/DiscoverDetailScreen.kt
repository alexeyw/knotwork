package app.knotwork.android.presentation.ui.discover

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.android.domain.models.DiscoverableModelDetail
import app.knotwork.android.domain.models.DiscoverableModelFile
import app.knotwork.android.presentation.ui.common.readPlainClipboardText
import app.knotwork.android.presentation.ui.models.gigabyteOrMegabyteLabel
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.screens.discover.DiscoverDetailCallbacks
import app.knotwork.design.screens.discover.DiscoverDetailContent
import app.knotwork.design.screens.discover.DiscoverDetailViewState
import app.knotwork.design.screens.discover.DiscoverDetailVisualState
import app.knotwork.design.screens.discover.DiscoverFileRow
import app.knotwork.design.screens.discover.DiscoverFileStatus

/**
 * App-side Discover detail screen. Binds [DiscoverDetailViewModel] to the
 * [repoId], folds its state into the catalog [DiscoverDetailViewState]
 * (formatting sizes/stats), shows install outcomes as snackbars and opens the
 * model card in the browser.
 *
 * @param repoId fully-qualified Hugging Face repository id to display.
 * @param onBack navigation callback for the top-bar back arrow.
 * @param viewModel Hilt-injected [DiscoverDetailViewModel].
 * @param modifier optional layout modifier.
 */
@Composable
fun DiscoverDetailScreen(
    repoId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiscoverDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(repoId) { viewModel.bind(repoId) }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val installedTemplate = stringResource(R.string.discover_install_success)
    val gatedError = stringResource(R.string.discover_install_gated)
    val genericError = stringResource(R.string.discover_install_failed)
    val detailLoadError = stringResource(R.string.discover_detail_load_error)

    LaunchedEffect(Unit) {
        viewModel.installEvents.collect { event ->
            val message = when (event) {
                is DiscoverInstallEvent.Success -> installedTemplate.format(event.fileName)
                is DiscoverInstallEvent.Failed -> if (event.gated) gatedError else genericError
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    val viewState = remember(uiState, detailLoadError) { uiState.toViewState(errorText = detailLoadError) }

    Box(modifier = modifier.fillMaxSize()) {
        DiscoverDetailContent(
            state = viewState,
            callbacks = DiscoverDetailCallbacks(
                onBack = onBack,
                onRetry = viewModel::onRetry,
                onOpenModelCard = { uiState.detail?.modelCardUrl?.let { openUrl(context, it) } },
                onInstallClick = viewModel::onInstallClick,
                onLicenseConfirm = viewModel::onLicenseConfirm,
                onLicenseDismiss = viewModel::onLicenseDismiss,
                onCancelInstall = viewModel::onCancelInstall,
                onTokenChange = viewModel::onTokenChange,
                onTokenPaste = { viewModel.onTokenChange(readPlainClipboardText(context)) },
                onToggleTokenReveal = viewModel::onToggleTokenReveal,
            ),
            snackbarHost = { KnotworkSnackbarHost(hostState = snackbarHostState) },
        )
    }
}

/** Folds the app detail UI state onto the catalog detail view state. */
private fun DiscoverDetailUiState.toViewState(errorText: String): DiscoverDetailViewState = when (status) {
    DiscoverDetailStatus.Loading -> DiscoverDetailViewState(
        visualState = DiscoverDetailVisualState.Loading,
        title = detail?.displayName.orEmpty(),
        repoId = detail?.repoId.orEmpty(),
    )
    DiscoverDetailStatus.Error -> DiscoverDetailViewState(
        visualState = DiscoverDetailVisualState.Error,
        title = detail?.displayName.orEmpty(),
        repoId = detail?.repoId.orEmpty(),
        errorMessage = errorText,
    )
    DiscoverDetailStatus.Loaded -> {
        val loaded = requireNotNull(detail)
        DiscoverDetailViewState(
            visualState = DiscoverDetailVisualState.Loaded,
            title = loaded.displayName,
            repoId = loaded.repoId,
            metaLine = loaded.metaLine(),
            license = loaded.license,
            gated = loaded.gated,
            files = loaded.files.map { file -> file.toRow(this) },
            pendingLicenseFileName = pendingLicenseFileName,
            tokenInput = tokenInput,
            tokenRevealed = tokenRevealed,
        )
    }
}

/** `"↓ 12.4k · ♥ 86"` stats line (license is shown on its own line). */
private fun DiscoverableModelDetail.metaLine(): String = "↓ ${formatHfCount(downloads)} · ♥ ${formatHfCount(likes)}"

/** Maps one domain file onto a catalog file row, resolving its install status. */
private fun DiscoverableModelFile.toRow(state: DiscoverDetailUiState): DiscoverFileRow {
    val status = when {
        isInstalled || fileName in state.installed -> DiscoverFileStatus.Installed
        state.progress.containsKey(fileName) -> DiscoverFileStatus.Downloading(state.progress.getValue(fileName))
        else -> DiscoverFileStatus.Idle
    }
    return DiscoverFileRow(
        fileName = fileName,
        sizeLabel = sizeBytes.takeIf { it > 0L }?.let { gigabyteOrMegabyteLabel(it) },
        status = status,
    )
}

/** Opens [url] in the user's browser. */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    context.startActivity(intent)
}
