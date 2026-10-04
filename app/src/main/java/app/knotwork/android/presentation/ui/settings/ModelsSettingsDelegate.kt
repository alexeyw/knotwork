package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.models.ProviderSummary
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.GenerationSettings
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.requiredCredential
import app.knotwork.android.domain.usecases.TestBackendUseCase
import app.knotwork.android.presentation.ui.settings.provider.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Models category delegate of [SettingsViewModel].
 *
 * Owns the local-model backend selection + test probe, the live active-model
 * card, the collapsed external-provider rows, and the **restart-required**
 * derived flag (backend / Ollama base-URL changes need a process restart).
 * Shares the ViewModel's [scope] and single [SettingsUiState] reducer.
 *
 * Restart-required baselines MUST be locked in before the live observers start
 * emitting: reactive DataStore / EncryptedPrefs flows can deliver a transient
 * seed value (null / default) before the persisted one settles, and capturing
 * the baseline from such an emission would race the steady-state value and
 * mis-flag the next emission as a "change" (spurious banner on every open). The
 * delegate therefore reads the two baselines with `.first()` before calling
 * [startObservers], guaranteeing baseline == persisted == first observed value
 * on a fresh ViewModel.
 *
 * @property scope The ViewModel's `viewModelScope`.
 * @property state The ViewModel's single source-of-truth state flow.
 * @property appContext Application context for probe-result snackbar copy.
 * @property generationSettings Persistence for the backend selection + probe result.
 * @property apiKeyRepository Source of the per-provider key/model/endpoint flows.
 * @property localModelRepository Source of the active-model card metadata.
 * @property testBackendUseCase Runs the on-device backend smoke probe.
 */
class ModelsSettingsDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SettingsUiState>,
    private val appContext: Context,
    private val generationSettings: GenerationSettings,
    private val apiKeyRepository: ApiKeyRepository,
    private val localModelRepository: LocalModelRepository,
    private val testBackendUseCase: TestBackendUseCase,
) {

    /**
     * Initial `localModelBackend` value; baseline for restart-required detection.
     * `null` is a legitimate baseline value (when no model has been picked yet),
     * so the dedicated [hasCapturedBackendBaseline] flag — not the field's
     * nullability — decides whether the baseline has been seen.
     */
    private var appliedBackend: String? = null
    private var hasCapturedBackendBaseline: Boolean = false

    /**
     * Initial `ollamaBaseUrl` value; baseline for restart-required detection.
     * Captured on the very first emission of the providers flow (even when the
     * URL is `null`) so the `null → some URL` and `some URL → null` transitions
     * are both detected.
     */
    private var appliedOllamaBaseUrl: String? = null
    private var hasCapturedOllamaBaseline: Boolean = false

    init {
        scope.launch {
            appliedBackend = generationSettings.localModelBackend.first()
            hasCapturedBackendBaseline = true
            appliedOllamaBaseUrl = apiKeyRepository.getBaseUrl(CloudProvider.OLLAMA).firstOrNull()
            hasCapturedOllamaBaseline = true
            startObservers()
        }
    }

    private fun startObservers() {
        localModelRepository.observeActiveModelMeta().onEach { meta ->
            state.update { it.copy(activeModelMeta = meta) }
        }.launchIn(scope)

        generationSettings.localModelBackend.onEach { value ->
            state.update {
                it.copy(
                    localModelBackend = value,
                    restartRequired = restartRequired(backend = value, ollamaUrl = it.providers.ollamaBaseUrl()),
                )
            }
        }.launchIn(scope)

        generationSettings.lastTestProbeResult.onEach { value ->
            state.update { it.copy(lastTestProbeResult = value) }
        }.launchIn(scope)

        observeProviders()
    }

    private fun observeProviders() {
        // Provider summaries are derived from ApiKeyRepository flows; we collapse
        // the individual key/model flows into a single `providers` list with
        // masked fingerprints so the UI receives a single payload it can map
        // straight to ProviderRowState.
        scope.launch {
            combineProviderFlows().collect { summaries ->
                val ollamaUrl = summaries.ollamaBaseUrl()
                state.update {
                    it.copy(
                        providers = summaries,
                        restartRequired = restartRequired(
                            backend = it.localModelBackend,
                            ollamaUrl = ollamaUrl,
                        ),
                    )
                }
            }
        }
    }

    /**
     * One summary per [ProviderId], in declaration order — the order the External providers
     * list shows them in. A provider reached at an address the user enters shows that address
     * where a hosted provider shows its key fingerprint, and carries the LAN pill.
     */
    private fun combineProviderFlows(): Flow<List<ProviderSummary>> =
        combine(ProviderId.entries.map(::providerSummaryFlow)) { summaries -> summaries.toList() }

    private fun providerSummaryFlow(id: ProviderId): Flow<ProviderSummary> {
        val provider = id.cloudProvider
        return combine(
            apiKeyRepository.requiredCredential(provider),
            apiKeyRepository.getModel(provider),
        ) { credential, model ->
            ProviderSummary(
                id = id,
                displayName = id.displayName(),
                // Only its presence is read for an address-based provider — the row shows the
                // address itself through `endpointHint`.
                keyFingerprint = credential?.takeIf { it.isNotBlank() }?.let { maskKey(it) },
                model = model?.takeIf { it.isNotBlank() },
                isLanLocal = provider.usesBaseUrl,
                endpointHint = credential?.takeIf { provider.usesBaseUrl && it.isNotBlank() },
            )
        }
    }

    private fun maskKey(key: String): String {
        val tail = key.takeLast(MASK_TAIL_LENGTH)
        val prefix = key.takeWhile { it != '-' || key.indexOf('-') == 0 }
            .takeIf { it.isNotBlank() && key.length > MASK_PREFIX_THRESHOLD }
            ?.take(MASK_PREFIX_LENGTH)
            .orEmpty()
        return if (prefix.isBlank()) "…$tail" else "$prefix-…$tail"
    }

    /**
     * Restart-required is derived state — comparison-only. The baselines are
     * captured before the source flows start observing, so `null → some URL` and
     * `some URL → null` transitions for Ollama are both detected. This function
     * never mutates the baseline so the call ordering between the backend and
     * providers flows cannot race the comparison.
     */
    private fun restartRequired(backend: String, ollamaUrl: String?): Boolean {
        val backendChanged = hasCapturedBackendBaseline && appliedBackend != backend
        val ollamaChanged = hasCapturedOllamaBaseline && appliedOllamaBaseUrl != ollamaUrl
        return backendChanged || ollamaChanged
    }

    /** Persists the selected local-model backend (restart-gated). */
    fun setLocalModelBackend(backend: String) {
        scope.launch { generationSettings.setLocalModelBackend(backend) }
    }

    /** Runs the on-device backend smoke probe and surfaces the outcome as a snackbar. */
    fun runBackendProbe() {
        scope.launch {
            state.update { it.copy(testProbeInFlight = true) }
            val result = testBackendUseCase()
            state.update { it.copy(testProbeInFlight = false) }
            val message = if (result.success) {
                appContext.getString(
                    R.string.settings_row_test_backend_success,
                    result.tokensGenerated,
                    String.format(Locale.getDefault(), "%.2fs", result.durationMs / MS_PER_SECOND_F),
                    String.format(Locale.getDefault(), "%.1f", result.tokensPerSecond),
                )
            } else {
                appContext.getString(R.string.settings_row_test_backend_failed, result.errorMessage.orEmpty())
            }
            state.update { it.copy(snackbarMessage = message) }
        }
    }

    /** Maps a cloud-provider wire id back to its [ProviderId], or `null` when unknown. */
    fun providerForId(id: String): ProviderId? = ProviderId.entries.firstOrNull { it.cloudProvider.id == id }

    /** Resets the restart baseline after the user restarts, so the banner stays gone. */
    fun acknowledgeRestart() {
        val current = state.value
        appliedBackend = current.localModelBackend
        appliedOllamaBaseUrl = current.providers.ollamaBaseUrl()
        hasCapturedBackendBaseline = true
        hasCapturedOllamaBaseline = true
        state.update { it.copy(restartRequired = false) }
    }

    private companion object {
        const val MASK_TAIL_LENGTH = 4
        const val MASK_PREFIX_LENGTH = 2
        const val MASK_PREFIX_THRESHOLD = 6
        const val MS_PER_SECOND_F = 1_000f
    }
}

/** The Ollama provider's endpoint URL within a provider-summary list, if present. */
private fun List<ProviderSummary>.ollamaBaseUrl(): String? = firstOrNull { it.id == ProviderId.Ollama }?.endpointHint
