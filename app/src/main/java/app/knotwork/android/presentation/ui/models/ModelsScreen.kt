package app.knotwork.android.presentation.ui.models

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.android.data.network.AndroidModelDownloadManager.DownloadError
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.usecases.BenchmarkRunPhase
import app.knotwork.android.presentation.ui.common.readPlainClipboardText
import app.knotwork.design.components.misc.KnotworkSnackbarHost
import app.knotwork.design.screens.models.ActiveModelRow
import app.knotwork.design.screens.models.BenchmarkPhase
import app.knotwork.design.screens.models.ModelsCallbacks
import app.knotwork.design.screens.models.ModelsContent
import app.knotwork.design.screens.models.ModelsStrings
import app.knotwork.design.screens.models.ModelsViewState
import app.knotwork.design.screens.models.ModelsVisualState
import app.knotwork.design.screens.models.PerformanceCardState
import app.knotwork.design.screens.models.PresetRow
import app.knotwork.design.screens.models.PresetStatus

/**
 * Slim app-side Models mapper. Subscribes to [ModelsViewModel.uiState],
 * folds it into the catalog [ModelsViewState], and renders [ModelsContent].
 *
 * @param viewModel Hilt-injected [ModelsViewModel].
 * @param onBack Navigation callback invoked when the user taps the
 * TopAppBar back arrow.
 * @param modifier Optional layout modifier.
 */
@Composable
fun ModelsScreen(
    modifier: Modifier = Modifier,
    viewModel: ModelsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenDiscover: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val unknownErrorText = stringResource(R.string.models_error_unknown)
    val defaultCustomFilename = stringResource(R.string.models_default_custom_filename)
    val benchmarkFailedText = stringResource(R.string.models_benchmark_failed)
    val benchmarkShareSubject = stringResource(R.string.models_benchmark_share_subject)
    val strings = modelsStrings()

    LaunchedEffect(uiState.downloadError) {
        uiState.downloadError?.let { error ->
            val errorMessage = when (error) {
                is DownloadError -> error.message
                else -> unknownErrorText
            }
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.clearError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.benchmarkShareEvents.collect { shareText ->
            shareBenchmarkText(context, shareText, benchmarkShareSubject)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.benchmarkErrorEvents.collect {
            snackbarHostState.showSnackbar(benchmarkFailedText)
        }
    }

    val viewState = remember(uiState) { uiState.toViewState(strings.subtitleFormat) }

    ModelsContent(
        state = viewState,
        modifier = modifier,
        strings = strings.content,
        callbacks = ModelsCallbacks(
            onBack = onBack,
            onDiscover = onOpenDiscover,
            onAuthTokenChange = viewModel::onAuthTokenChanged,
            onAuthTokenPaste = { viewModel.onAuthTokenChanged(readPlainClipboardText(context)) },
            onCustomUrlChange = viewModel::onCustomUrlChanged,
            onCustomUrlSubmit = {
                val url = uiState.customUrlInput
                if (url.isNotBlank()) {
                    val fileName = url.substringAfterLast(delimiter = "/").ifBlank { defaultCustomFilename }
                    viewModel.startDownload(url, fileName)
                }
            },
            onPresetDownload = { presetId ->
                uiState.availablePresets.find { it.url.toPresetId() == presetId }?.let { preset ->
                    val fileName = preset.url.substringAfterLast(delimiter = "/")
                    viewModel.startDownload(preset.url, fileName)
                }
            },
            onPresetCancelDownload = { viewModel.cancelDownload() },
            onPresetActivate = { presetId ->
                // The presetId is the dotless filename derived in `toPresetId()`.
                // Match against downloaded models (preset list shares the same
                // id convention) AND the dedicated downloadedRows entries
                // (custom downloads use the raw filename as id).
                uiState.downloadedModels
                    .firstOrNull { it.name.toPresetId() == presetId || it.name == presetId }
                    ?.let { viewModel.setActiveModel(it.id) }
            },
            onPresetDelete = { presetId ->
                uiState.downloadedModels
                    .firstOrNull { it.name.toPresetId() == presetId || it.name == presetId }
                    ?.let { viewModel.deleteModel(it.id) }
            },
            onCustomDownloadCancel = { viewModel.cancelDownload() },
            onToggleVision = { enabled ->
                uiState.activeModel?.let { viewModel.setVisionSupport(it.id, enabled) }
            },
            onToggleAudio = { enabled ->
                uiState.activeModel?.let { viewModel.setAudioSupport(it.id, enabled) }
            },
            onRunBenchmark = viewModel::onRunBenchmark,
            onCancelBenchmark = viewModel::onCancelBenchmark,
            onShareBenchmark = viewModel::onShareBenchmark,
            onDismissBenchmark = viewModel::onDismissBenchmark,
        ),
        // The screen showed download and benchmark failures through this state
        // without ever rendering a host, so they never appeared.
        snackbarHost = { KnotworkSnackbarHost(hostState = snackbarHostState) },
    )
}

/** Hands the formatted benchmark summary to the system share sheet as plain text. */
private fun shareBenchmarkText(context: Context, text: String, subject: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, subject))
}

/**
 * Map the in-app [ModelsUiState] onto the catalog [ModelsViewState] consumed by
 * `ModelsContent`. Pulled out as a regular function so it can be exercised by
 * unit tests independently of the composable subscription.
 *
 * @param subtitleFormat localised `"%1$d active · %2$d on disk · %3$s"` template.
 */
internal fun ModelsUiState.toViewState(subtitleFormat: String): ModelsViewState {
    // Sum of `LocalModel.size` is unreliable because the download manager
    // doesn't currently report content-length back to the local store,
    // so freshly-downloaded entries land with `size = 0L`. Render a dash
    // instead of a misleading `0 GB` whenever at least one model is on
    // disk but the totals look empty.
    val sumBytes = downloadedModels.sumOf { it.size }
    val totalSizeLabel = if (downloadedModels.isNotEmpty() && sumBytes == 0L) {
        "—"
    } else {
        sumBytes.toGigabytesLabel()
    }
    val subtitle = subtitleFormat.format(
        if (activeModel != null) 1 else 0,
        downloadedModels.size,
        totalSizeLabel,
    )
    val backendLabel = backendDisplayLabel(localBackendKey)
    val active = activeModel?.let { model ->
        ActiveModelRow(
            id = model.id,
            displayName = model.name,
            meta = model.toMetaLine(backendLabel = backendLabel),
            visionSupported = model.supportsVision,
            audioSupported = model.supportsAudio,
        )
    }
    val downloadingName = activeDownloadFileName

    fun statusFor(fileName: String, presetSize: Long? = null): PresetStatus {
        val matchingModel = downloadedModels.firstOrNull { it.name == fileName }
        return when {
            downloadingName == fileName -> PresetStatus.Downloading(
                progress = downloadProgress ?: 0,
                metaLine = null,
            )
            matchingModel != null && matchingModel.isActive -> PresetStatus.Active(
                sizeMeta = matchingModel.toMetaLine(backendLabel = backendLabel),
            )
            matchingModel != null -> PresetStatus.OnDisk(
                sizeMeta = matchingModel.toMetaLine(backendLabel = backendLabel),
            )
            else -> PresetStatus.Idle(
                sizeMeta = presetSize?.takeIf { it > 0L }?.toGigabytesLabel(),
            )
        }
    }

    // Curated presets retain their canonical id derived from the URL slug.
    val presets = availablePresets.map { preset ->
        val fileName = preset.url.substringAfterLast(delimiter = "/")
        PresetRow(
            id = preset.url.toPresetId(),
            name = preset.name,
            source = preset.url.toShortSource(),
            status = statusFor(fileName = fileName),
        )
    }
    // Models on disk that are NOT part of the curated preset list — i.e.
    // custom URL downloads. The id is the raw filename so the activate
    // / delete lambdas can resolve them.
    val presetFileNames = availablePresets.map { it.url.substringAfterLast(delimiter = "/") }.toSet()
    val downloadedRows = downloadedModels
        .filter { it.name !in presetFileNames }
        .map { model ->
            PresetRow(
                id = model.name,
                name = model.name,
                source = model.path.substringBeforeLast(delimiter = "/").substringAfterLast(delimiter = "/"),
                status = statusFor(fileName = model.name),
            )
        }
    // Surfaces the in-flight progress for a non-preset (custom URL)
    // download as a separate row near the URL field. Without this the
    // Get button would just go disabled with no visible feedback.
    val customDownload: PresetStatus.Downloading? = when {
        !isDownloading -> null
        downloadingName != null && downloadingName in presetFileNames -> null
        else -> PresetStatus.Downloading(progress = downloadProgress ?: 0, metaLine = null)
    }

    return ModelsViewState(
        visualState = ModelsVisualState.Default,
        active = active,
        authToken = authTokenInput,
        customUrl = customUrlInput,
        customUrlEnabled = !isDownloading,
        customDownload = customDownload,
        presets = presets,
        downloadedRows = downloadedRows,
        subtitle = subtitle,
        performance = toPerformanceCardState(),
    )
}

/**
 * Collapses the active model, its rolling performance summary, the live
 * engine-busy signal and the benchmark sub-state into the single
 * [PerformanceCardState] the catalog card renders.
 */
private fun ModelsUiState.toPerformanceCardState(): PerformanceCardState {
    if (activeModel == null) return PerformanceCardState.NoModel
    return when (val bench = benchmark) {
        is BenchmarkUiState.Running -> PerformanceCardState.Running(bench.phase.toCatalogPhase())
        is BenchmarkUiState.Result -> PerformanceCardState.Result(
            ttft = PerformanceFormatting.ttft(bench.report.ttftMs),
            decode = PerformanceFormatting.decode(bench.report.decodeTokensPerSec),
            total = PerformanceFormatting.totalSeconds(bench.report.totalMs),
            peakMemory = PerformanceFormatting.memory(bench.report.peakNativeHeapBytes),
        )
        BenchmarkUiState.Idle -> when {
            engineBusy -> PerformanceCardState.EngineBusy
            performanceSummary != null -> PerformanceCardState.Populated(
                ttft = PerformanceFormatting.ttft(performanceSummary.avgTtftMs),
                decode = PerformanceFormatting.decode(performanceSummary.avgDecodeTokensPerSec),
                peakMemory = PerformanceFormatting.memory(performanceSummary.peakNativeHeapBytes),
                sampleCaption = PerformanceFormatting.sampleCaption(performanceSummary.sampleCount),
            )
            else -> PerformanceCardState.Empty
        }
    }
}

/** Maps the domain benchmark phase onto the catalog card's phase enum. */
private fun BenchmarkRunPhase.toCatalogPhase(): BenchmarkPhase = when (this) {
    BenchmarkRunPhase.WARMING_UP -> BenchmarkPhase.WarmingUp
    BenchmarkRunPhase.MEASURING -> BenchmarkPhase.Measuring
}

private fun LocalModel.toMetaLine(backendLabel: String): String {
    // Drop the size prefix when the on-disk file is unreadable so we
    // don't render a misleading "0 GB" — the size is enriched from the
    // file system by `LocalModelRepositoryImpl.getAllModels()`.
    val prefix = if (size > 0L) "${size.toGigabytesLabel()} · " else ""
    return "$prefix$backendLabel · LiteRT"
}

/**
 * Human-readable label for the [app.knotwork.android.domain.models.LocalBackend]
 * wire key persisted in settings. Falls back to upper-casing the raw key
 * for forward-compatibility with backend ids that haven't been added to
 * the typed enum yet.
 */
private fun backendDisplayLabel(key: String): String = when (key.lowercase()) {
    "cpu" -> "CPU"
    "gpu" -> "GPU"
    "npu" -> "NPU"
    else -> key.uppercase()
}

private fun String.toPresetId(): String = this
    .substringAfterLast(delimiter = "/")
    .substringBeforeLast(delimiter = ".")

private fun String.toShortSource(): String {
    val withoutScheme = removePrefix(prefix = "https://").removePrefix(prefix = "http://")
    return if (withoutScheme.length > MAX_SOURCE_LEN) {
        withoutScheme.take(MAX_SOURCE_LEN) + "…"
    } else {
        withoutScheme
    }
}

private const val MAX_SOURCE_LEN = 44

// Reuses the shared GiB/MiB ladder ([gigabyteOrMegabyteLabel]); model sizes
// render "0 GB" for an unknown/zero size rather than nothing.
private fun Long.toGigabytesLabel(): String = if (this <= 0L) "0 GB" else gigabyteOrMegabyteLabel(this)

/** Bundle of localised display strings threaded into [ModelsContent]. */
private data class LocalisedModelsStrings(val content: ModelsStrings, val subtitleFormat: String)

@Composable
private fun modelsStrings(): LocalisedModelsStrings = LocalisedModelsStrings(
    content = ModelsStrings(
        title = stringResource(R.string.models_screen_title),
        backCd = stringResource(R.string.common_back),
        overflowCd = stringResource(R.string.models_overflow_cd),
        activeBadge = stringResource(R.string.models_active_badge),
        hfSection = stringResource(R.string.models_section_hf),
        hfOptional = stringResource(R.string.models_section_hf_optional),
        hfPlaceholder = stringResource(R.string.models_field_hf_placeholder),
        hfPaste = stringResource(R.string.models_action_paste),
        customUrlSection = stringResource(R.string.models_section_custom_url),
        customUrlPlaceholder = stringResource(R.string.models_field_url_placeholder),
        customUrlGet = stringResource(R.string.models_action_get),
        customDownloadLabel = stringResource(R.string.models_custom_download_label),
        formatHint = stringResource(R.string.models_hint_formats),
        presetsSection = stringResource(R.string.models_section_available_presets),
        downloadedSection = stringResource(R.string.models_section_downloaded_v2),
        presetGet = stringResource(R.string.models_preset_get),
        presetActivate = stringResource(R.string.models_preset_activate),
        presetDelete = stringResource(R.string.models_preset_delete),
        presetCancelCd = stringResource(R.string.models_preset_cancel_cd),
        rowMenuCd = stringResource(R.string.models_row_menu_cd),
        emptyTitle = stringResource(R.string.models_empty_title),
        emptySubtitle = stringResource(R.string.models_empty_subtitle),
        emptyCta = stringResource(R.string.models_empty_cta),
        errorTitle = stringResource(R.string.models_error_title),
        errorRetry = stringResource(R.string.common_retry),
        discoverCd = stringResource(R.string.models_discover_cd),
    ),
    subtitleFormat = stringResource(R.string.models_subtitle_format),
)
