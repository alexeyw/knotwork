package app.knotwork.android.presentation.ui.settings.provider

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.R
import app.knotwork.android.data.engine.KoogModelMapper
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.services.CleartextPolicy
import app.knotwork.design.screens.settings.CleartextConsentUi
import app.knotwork.design.screens.settings.CloudRetryViewState
import app.knotwork.design.screens.settings.KnotworkProviderRow
import app.knotwork.design.screens.settings.LocalSettingsHints
import app.knotwork.design.screens.settings.OllamaProviderInputs
import app.knotwork.design.screens.settings.ProviderDetailCallbacks
import app.knotwork.design.screens.settings.ProviderDetailContent
import app.knotwork.design.screens.settings.ProviderDetailViewState
import app.knotwork.design.screens.settings.SettingsHint
import app.knotwork.design.screens.settings.SettingsHintController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Standalone editor for a single external LLM provider, reached from
 * the Settings → External providers nav-row.
 *
 * Wraps the catalog [KnotworkProviderRow] inside a top-level scaffold —
 * lets users tweak API key, model and (for Ollama) the LAN base URL and
 * context window without scrolling back inside the main Settings stack.
 *
 * @param providerId Which provider to render. Determines which fields
 *   appear (Ollama gets two extra inputs).
 * @param onBack Invoked when the user taps the system back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    providerId: ProviderId,
    onBack: () -> Unit,
    viewModel: ProviderDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(providerId) { viewModel.bind(providerId) }
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val hints = remember(context) { retryHints(context) }

    CompositionLocalProvider(LocalSettingsHints provides hints) {
        ProviderDetailContent(
            state = uiState.toViewState(providerId, context),
            callbacks = ProviderDetailCallbacks(
                onBack = onBack,
                onApiKeyChange = { value -> viewModel.updateKey(providerId, value) },
                onModelChange = { value -> viewModel.updateModel(providerId, value) },
                onOllamaBaseUrlChange = viewModel::updateOllamaBaseUrl,
                onOllamaContextWindowChange = viewModel::updateOllamaContextWindow,
                onApproveCleartextOrigin = viewModel::approveCleartextOrigin,
                onRetryAttemptsChange = viewModel::updateCloudRetryMaxAttempts,
                onRetryDelayChange = viewModel::updateCloudRetryBaseDelayMs,
            ),
        )
    }
}

/**
 * Projects the VM state onto the catalog's view state, resolving every string
 * here so the design module never learns which providers exist.
 *
 * The provider `when` is the one place that knows a provider's shape. Ollama is
 * the outlier twice over: it runs LAN-local without authentication, so its API
 * key is `null` rather than empty, and its model is typed rather than chosen
 * from a list.
 *
 * @param providerId Which provider the screen was opened for.
 * @param context Resource resolution.
 * @return The resolved view state.
 */
@VisibleForTesting
internal fun ProviderDetailUiState.toViewState(providerId: ProviderId, context: Context): ProviderDetailViewState {
    val label = providerLabel(providerId)
    val ollamaError = context.getString(R.string.settings_ollama_base_url_error).takeIf { ollamaBaseUrlInvalid }
    return ProviderDetailViewState(
        title = context.getString(R.string.settings_provider_detail_title, label),
        providerLabel = label,
        backContentDescription = context.getString(R.string.common_back),
        apiKey = when (providerId) {
            ProviderId.OpenAi -> openAiKey
            ProviderId.Anthropic -> anthropicKey
            ProviderId.Google -> googleKey
            ProviderId.DeepSeek -> deepSeekKey
            ProviderId.Ollama -> null
        },
        apiKeyLabel = context.getString(R.string.settings_provider_api_key_label, label),
        model = when (providerId) {
            ProviderId.OpenAi -> openAiModel
            ProviderId.Anthropic -> anthropicModel
            ProviderId.Google -> googleModel
            ProviderId.DeepSeek -> deepSeekModel
            ProviderId.Ollama -> ollamaModel
        },
        modelLabel = when (providerId) {
            ProviderId.Ollama -> context.getString(R.string.settings_ollama_model_label)
            else -> context.getString(R.string.settings_provider_model_label, label)
        },
        availableModels = when (providerId) {
            ProviderId.OpenAi -> KoogModelMapper.getOpenAIModelIdList()
            ProviderId.Anthropic -> KoogModelMapper.getAnthropicModelIdList()
            ProviderId.Google -> KoogModelMapper.getGoogleModelIdList()
            ProviderId.DeepSeek -> KoogModelMapper.getDeepSeekModelIdList()
            ProviderId.Ollama -> emptyList()
        },
        ollama = if (providerId == ProviderId.Ollama) {
            OllamaProviderInputs(
                baseUrl = ollamaBaseUrl,
                baseUrlPlaceholder = context.getString(R.string.settings_ollama_base_url_placeholder),
                baseUrlValidationError = ollamaError,
                contextWindow = ollamaContextWindow,
                contextWindowLabel = context.getString(R.string.settings_ollama_context_label),
                baseUrlLabel = context.getString(R.string.settings_ollama_base_url_label),
            )
        } else {
            null
        },
        cleartextConsent = cleartextConsentOrigin?.let { origin ->
            CleartextConsentUi(
                body = context.getString(R.string.settings_cleartext_consent_body, origin),
                actionLabel = context.getString(R.string.settings_cleartext_consent_action),
            )
        },
        retry = cloudRetryViewState(context),
    )
}

/**
 * Resolves the cloud-retry sliders, bounds included.
 *
 * The bounds travel with the state rather than living in the design module: they
 * are the same `SettingsDefaults` values the store coerces against, and a second
 * copy over there could disagree with the range actually enforced.
 *
 * @param context Resource resolution.
 * @return The resolved retry state.
 */
@VisibleForTesting
internal fun ProviderDetailUiState.cloudRetryViewState(context: Context): CloudRetryViewState {
    val minAttempts = SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MIN
    val maxAttempts = SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_MAX
    return CloudRetryViewState(
        sectionTitle = context.getString(R.string.settings_cloud_retry_title),
        sectionAnchor = RETRY_SECTION_ANCHOR,
        attempts = cloudRetryMaxAttempts,
        attemptsLabel = context.getString(R.string.settings_cloud_retry_attempts_title),
        attemptsValueLabel = cloudRetryMaxAttempts.toString(),
        attemptsRange = minAttempts.toFloat()..maxAttempts.toFloat(),
        attemptsSteps = maxAttempts - minAttempts - 1,
        attemptsAnchor = RETRY_ATTEMPTS_ANCHOR,
        delayMs = cloudRetryBaseDelayMs,
        delayLabel = context.getString(R.string.settings_cloud_retry_delay_title),
        delayValueLabel = context.getString(R.string.settings_cloud_retry_delay_value, cloudRetryBaseDelayMs),
        delayRange = delayRange(),
        delayAnchor = RETRY_DELAY_ANCHOR,
    )
}

/**
 * Allowed base-delay range, as floats for the slider.
 *
 * Its own function only because the two qualified constants do not fit on one
 * line together, and a range expression wrapped across lines reads worse than a
 * name.
 *
 * @return The delay bounds.
 */
private fun delayRange(): ClosedFloatingPointRange<Float> {
    val min = SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MIN.toFloat()
    val max = SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_MAX.toFloat()
    return min..max
}

/** Anchor of the retry-policy section header. */
private const val RETRY_SECTION_ANCHOR = "CLOUD_RETRY_POLICY"

/** Anchor of the max-attempts slider. */
private const val RETRY_ATTEMPTS_ANCHOR = "CLOUD_RETRY_MAX_ATTEMPTS"

/** Anchor of the base-delay slider. */
private const val RETRY_DELAY_ANCHOR = "CLOUD_RETRY_BASE_DELAY_MS"

/**
 * Hints for the retry rows.
 *
 * Local rather than from `SettingsHelpCatalog`: these three live on the provider
 * screen and are not rows of the settings registry, which the catalogue and its
 * completeness gate are keyed to. They follow the same rules — one sentence,
 * what changes and what you will notice.
 *
 * @param context Resource resolution for the localized text.
 */
private fun retryHints(context: Context): SettingsHintController = SettingsHintController { anchor ->
    when (anchor) {
        RETRY_SECTION_ANCHOR -> SettingsHint(context.getString(R.string.settings_cloud_retry_hint))
        RETRY_ATTEMPTS_ANCHOR -> SettingsHint(context.getString(R.string.settings_cloud_retry_attempts_hint))
        RETRY_DELAY_ANCHOR -> SettingsHint(context.getString(R.string.settings_cloud_retry_delay_hint))
        else -> null
    }
}

/**
 * The provider's own name. Not localized: these are product names.
 *
 * @param id The provider.
 * @return Its display name.
 */
private fun providerLabel(id: ProviderId): String = when (id) {
    ProviderId.OpenAi -> "OpenAI"
    ProviderId.Anthropic -> "Anthropic"
    ProviderId.Google -> "Google"
    ProviderId.DeepSeek -> "DeepSeek"
    ProviderId.Ollama -> "Ollama"
}

/**
 * UI state slice surfaced by [ProviderDetailViewModel].
 *
 * Kept as a single data class so the screen recomposes against one
 * snapshot.
 */
data class ProviderDetailUiState(
    val openAiKey: String = "",
    val openAiModel: String = "",
    val anthropicKey: String = "",
    val anthropicModel: String = "",
    val googleKey: String = "",
    val googleModel: String = "",
    val deepSeekKey: String = "",
    val deepSeekModel: String = "",
    val ollamaBaseUrl: String = "",
    val ollamaModel: String = "",
    val ollamaContextWindow: String = "4096",
    val ollamaBaseUrlInvalid: Boolean = false,
    val cleartextConsentOrigin: String? = null,
    val cloudRetryMaxAttempts: Int = SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT,
    val cloudRetryBaseDelayMs: Long = SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_DEFAULT,
)

/**
 * ViewModel backing [ProviderDetailScreen] — owns the API-key / model
 * edits routed through [ApiKeyRepository]. Independent from the main
 * Settings VM so the provider detail screen can be reached as a deep-
 * link target without forcing the entire Settings tree to load.
 */
@HiltViewModel
class ProviderDetailViewModel @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository,
    private val networkSettings: NetworkSettings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderDetailUiState())
    val uiState: StateFlow<ProviderDetailUiState> = _uiState.asStateFlow()

    init {
        // The cloud-retry policy is global (applies to every provider), so it is
        // bound once on construction rather than per-provider in [bind].
        networkSettings.cloudRetryMaxAttempts
            .onEach { v -> _uiState.update { it.copy(cloudRetryMaxAttempts = v) } }
            .launchIn(viewModelScope)
        networkSettings.cloudRetryBaseDelayMs
            .onEach { v -> _uiState.update { it.copy(cloudRetryBaseDelayMs = v) } }
            .launchIn(viewModelScope)
    }

    /**
     * Binds the screen to the relevant API-key flows. Idempotent —
     * `LaunchedEffect(providerId)` invokes this on the very first
     * composition.
     */
    fun bind(providerId: ProviderId) {
        when (providerId) {
            ProviderId.OpenAi -> {
                apiKeyRepository.getOpenAIKey().onEach { v -> _uiState.update { it.copy(openAiKey = v.orEmpty()) } }
                    .launchIn(viewModelScope)
                apiKeyRepository.getOpenAIModel().onEach { v -> _uiState.update { it.copy(openAiModel = v.orEmpty()) } }
                    .launchIn(viewModelScope)
            }
            ProviderId.Anthropic -> {
                apiKeyRepository.getAnthropicKey()
                    .onEach { v -> _uiState.update { it.copy(anthropicKey = v.orEmpty()) } }
                    .launchIn(viewModelScope)
                apiKeyRepository.getAnthropicModel()
                    .onEach { v -> _uiState.update { it.copy(anthropicModel = v.orEmpty()) } }
                    .launchIn(viewModelScope)
            }
            ProviderId.Google -> {
                apiKeyRepository.getGoogleKey()
                    .onEach { v -> _uiState.update { it.copy(googleKey = v.orEmpty()) } }
                    .launchIn(viewModelScope)
                apiKeyRepository.getGoogleModel()
                    .onEach { v -> _uiState.update { it.copy(googleModel = v.orEmpty()) } }
                    .launchIn(viewModelScope)
            }
            ProviderId.DeepSeek -> {
                apiKeyRepository.getDeepSeekKey()
                    .onEach { v -> _uiState.update { it.copy(deepSeekKey = v.orEmpty()) } }
                    .launchIn(viewModelScope)
                apiKeyRepository.getDeepSeekModel()
                    .onEach { v -> _uiState.update { it.copy(deepSeekModel = v.orEmpty()) } }
                    .launchIn(viewModelScope)
            }
            ProviderId.Ollama -> {
                apiKeyRepository.getOllamaBaseUrl()
                    .onEach { v -> _uiState.update { it.copy(ollamaBaseUrl = v.orEmpty()) } }
                    .launchIn(viewModelScope)
                // The consent notice is derived, not stored: it appears whenever the
                // saved address is an unencrypted private one the user has not
                // approved, and disappears the moment either side changes. Combining
                // both flows (rather than checking once on save) is what keeps it
                // correct when the URL is edited keystroke by keystroke — this field
                // persists on every character, so there is no "save" moment to hang a
                // confirmation off.
                combine(
                    apiKeyRepository.getOllamaBaseUrl(),
                    networkSettings.approvedCleartextOrigins,
                ) { url, approved ->
                    val verdict = CleartextPolicy.classify(url.orEmpty(), approved)
                    (verdict as? CleartextPolicy.Verdict.NeedsApproval)?.origin
                }
                    .onEach { origin -> _uiState.update { it.copy(cleartextConsentOrigin = origin) } }
                    .launchIn(viewModelScope)
                apiKeyRepository.getOllamaModelName()
                    .onEach { v -> _uiState.update { it.copy(ollamaModel = v.orEmpty()) } }
                    .launchIn(viewModelScope)
                apiKeyRepository.getOllamaContextWindowSize()
                    .onEach { v -> _uiState.update { it.copy(ollamaContextWindow = v.toString()) } }
                    .launchIn(viewModelScope)
            }
        }
    }

    /**
     * Routes a key edit to the bound provider's own setter.
     *
     * The screen no longer names providers when wiring its callbacks — the one
     * place that knows a provider's shape is the projection above — so the
     * dispatch lives here instead of being spelled out five times at the call
     * site. Ollama has no key and never reaches this.
     *
     * @param providerId The provider being edited.
     * @param value The new key; blank clears it.
     */
    fun updateKey(providerId: ProviderId, value: String) {
        when (providerId) {
            ProviderId.OpenAi -> updateOpenAiKey(value)
            ProviderId.Anthropic -> updateAnthropicKey(value)
            ProviderId.Google -> updateGoogleKey(value)
            ProviderId.DeepSeek -> updateDeepSeekKey(value)
            ProviderId.Ollama -> Unit
        }
    }

    /**
     * Routes a model edit to the bound provider's own setter.
     *
     * @param providerId The provider being edited.
     * @param value The new model id.
     */
    fun updateModel(providerId: ProviderId, value: String) {
        when (providerId) {
            ProviderId.OpenAi -> updateOpenAiModel(value)
            ProviderId.Anthropic -> updateAnthropicModel(value)
            ProviderId.Google -> updateGoogleModel(value)
            ProviderId.DeepSeek -> updateDeepSeekModel(value)
            ProviderId.Ollama -> updateOllamaModel(value)
        }
    }

    fun updateOpenAiKey(value: String) {
        viewModelScope.launch { apiKeyRepository.setOpenAIKey(value.takeIf { it.isNotBlank() }) }
    }

    fun updateOpenAiModel(value: String) {
        viewModelScope.launch { apiKeyRepository.setOpenAIModel(value.takeIf { it.isNotBlank() }) }
    }

    fun updateAnthropicKey(value: String) {
        viewModelScope.launch { apiKeyRepository.setAnthropicKey(value.takeIf { it.isNotBlank() }) }
    }

    fun updateAnthropicModel(value: String) {
        viewModelScope.launch { apiKeyRepository.setAnthropicModel(value.takeIf { it.isNotBlank() }) }
    }

    fun updateGoogleKey(value: String) {
        viewModelScope.launch { apiKeyRepository.setGoogleKey(value.takeIf { it.isNotBlank() }) }
    }

    fun updateGoogleModel(value: String) {
        viewModelScope.launch { apiKeyRepository.setGoogleModel(value.takeIf { it.isNotBlank() }) }
    }

    fun updateDeepSeekKey(value: String) {
        viewModelScope.launch { apiKeyRepository.setDeepSeekKey(value.takeIf { it.isNotBlank() }) }
    }

    fun updateDeepSeekModel(value: String) {
        viewModelScope.launch { apiKeyRepository.setDeepSeekModel(value.takeIf { it.isNotBlank() }) }
    }

    /**
     * Persists the Ollama base URL and flags whether it can be used.
     *
     * The check used to be `isBlank()` alone, so the only invalid value was an
     * empty field: `192.168.1.24` was accepted silently, and — because
     * `CleartextPolicy.classify` treats anything without an `http://` prefix as
     * *not cleartext* — no consent was asked for either. The address was stored,
     * looked fine, and failed at request time. `hostOf` returns `null` exactly
     * when there is no parseable scheme and host, which is the same condition
     * every downstream gate applies.
     *
     * @param value The URL as typed; blank clears the setting.
     */
    fun updateOllamaBaseUrl(value: String) {
        val unusable = value.isBlank() || CleartextPolicy.hostOf(value) == null
        _uiState.update { it.copy(ollamaBaseUrl = value, ollamaBaseUrlInvalid = unusable) }
        viewModelScope.launch { apiKeyRepository.setOllamaBaseUrl(value.takeIf { it.isNotBlank() }) }
    }

    /**
     * Records the user's consent to talk to the currently-configured Ollama
     * address over an unencrypted connection. Until this is called, the
     * cleartext gate refuses the connection outright — see `CleartextPolicy`.
     */
    fun approveCleartextOrigin() {
        val origin = _uiState.value.cleartextConsentOrigin ?: return
        viewModelScope.launch { networkSettings.approveCleartextOrigin(origin) }
    }

    fun updateOllamaModel(value: String) {
        viewModelScope.launch { apiKeyRepository.setOllamaModelName(value.takeIf { it.isNotBlank() }) }
    }

    fun updateOllamaContextWindow(value: String) {
        viewModelScope.launch {
            val size = value.toIntOrNull() ?: SettingsDefaults.OLLAMA_CONTEXT_WINDOW_DEFAULT
            apiKeyRepository.setOllamaContextWindowSize(size)
        }
    }

    /** Persists the global cloud-retry attempt budget (coerced to 1–5 by the store). */
    fun updateCloudRetryMaxAttempts(value: Int) {
        viewModelScope.launch { networkSettings.setCloudRetryMaxAttempts(value) }
    }

    /** Persists the global cloud-retry base delay in milliseconds (coerced to 100–10000). */
    fun updateCloudRetryBaseDelayMs(value: Long) {
        viewModelScope.launch { networkSettings.setCloudRetryBaseDelayMs(value) }
    }
}

/** Test tag for the unencrypted-connection consent banner on the Ollama provider screen. */
const val CLEARTEXT_CONSENT_BANNER_TAG: String = "cleartext_consent_banner"
