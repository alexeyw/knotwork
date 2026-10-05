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
import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.connection.ConnectionPreconditions
import app.knotwork.android.domain.connection.EndpointRule
import app.knotwork.android.domain.connection.ProviderConnectionChecker
import app.knotwork.android.domain.connection.ProviderConnectionDraft
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.services.CleartextPolicy
import app.knotwork.android.presentation.ui.common.ConnectionTest
import app.knotwork.android.presentation.ui.common.ConnectionTestState
import app.knotwork.android.presentation.ui.common.TestSubject
import app.knotwork.android.presentation.ui.common.elapsedSecondsOf
import app.knotwork.android.presentation.ui.common.toTestProbeUi
import app.knotwork.design.screens.settings.CleartextConsentUi
import app.knotwork.design.screens.settings.CloudRetryViewState
import app.knotwork.design.screens.settings.LocalSettingsHints
import app.knotwork.design.screens.settings.ModelSheetUi
import app.knotwork.design.screens.settings.ProviderAddressUi
import app.knotwork.design.screens.settings.ProviderContextWindowUi
import app.knotwork.design.screens.settings.ProviderDetailCallbacks
import app.knotwork.design.screens.settings.ProviderDetailContent
import app.knotwork.design.screens.settings.ProviderDetailViewState
import app.knotwork.design.screens.settings.ProviderFixedAddressUi
import app.knotwork.design.screens.settings.ProviderKeyUi
import app.knotwork.design.screens.settings.ProviderModelUi
import app.knotwork.design.screens.settings.SettingsHint
import app.knotwork.design.screens.settings.SettingsHintController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.TimeSource

/**
 * Standalone editor for a single external LLM provider, reached from
 * the Settings → External providers nav-row.
 *
 * Renders the catalog's provider form — the provider's address or key, *Test connection*, its
 * model, and the retry policy — with every string resolved here, and ticks a running test's
 * elapsed seconds.
 *
 * @param providerId Which provider to render. Determines which fields appear.
 * @param onBack Invoked when the user taps the system back button.
 * @param viewModel The provider's values and the test row.
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
    val test by viewModel.connectionTestState.collectAsState()
    val elapsedSeconds by elapsedSecondsOf(test)
    val context = LocalContext.current
    val hints = remember(context) { retryHints(context) }

    CompositionLocalProvider(LocalSettingsHints provides hints) {
        ProviderDetailContent(
            state = uiState.toViewState(providerId, context, test, elapsedSeconds),
            callbacks = ProviderDetailCallbacks(
                onBack = onBack,
                onApiKeyChange = { value -> viewModel.updateKey(providerId, value) },
                onModelChange = { value -> viewModel.updateModel(providerId, value) },
                onAddressChange = { value -> viewModel.updateBaseUrl(providerId, value) },
                onContextWindowChange = viewModel::updateOllamaContextWindow,
                onApproveCleartextOrigin = viewModel::approveCleartextOrigin,
                onRetryAttemptsChange = viewModel::updateCloudRetryMaxAttempts,
                onRetryDelayChange = viewModel::updateCloudRetryBaseDelayMs,
                onTestRun = viewModel::startConnectionTest,
                onTestCancel = viewModel::cancelConnectionTest,
                onChooseModel = viewModel::openModelSheet,
                onModelSheetDismiss = viewModel::closeModelSheet,
            ),
        )
    }
}

/**
 * Projects the VM state onto the catalog's view state, resolving every string
 * here so the design module never learns which providers exist.
 *
 * The fields come from the provider's [CloudProvider][app.knotwork.android.domain.models.CloudProvider]
 * shape — an address for a server the user runs, a key where it takes one, a fixed address for
 * OpenRouter and Groq, a built-in model list for the four providers Koog lists, a typed id
 * otherwise — so a field the provider has no use for is hidden rather than shown empty.
 *
 * @param providerId Which provider the screen was opened for.
 * @param context Resource resolution.
 * @param test The *Test connection* row's state.
 * @param elapsedSeconds Seconds since a running test started.
 * @return The resolved view state.
 */
@VisibleForTesting
internal fun ProviderDetailUiState.toViewState(
    providerId: ProviderId,
    context: Context,
    test: ConnectionTestState = ConnectionTestState.Idle,
    elapsedSeconds: Long = 0,
): ProviderDetailViewState {
    val name = providerId.displayName()
    val provider = providerId.cloudProvider
    val subject = if (provider.usesBaseUrl) TestSubject.OwnServer(name) else TestSubject.Hosted(name)
    val listed = ((test as? ConnectionTestState.Finished)?.result as? ConnectionCheckResult.Reachable)?.items
    return ProviderDetailViewState(
        title = name,
        backContentDescription = context.getString(R.string.common_back),
        address = addressUi(providerId, context),
        cleartextConsent = cleartextConsentOrigin?.let { origin ->
            CleartextConsentUi(
                body = context.getString(R.string.settings_cleartext_consent_body, origin),
                actionLabel = context.getString(R.string.settings_cleartext_consent_action),
            )
        },
        fixedAddress = fixedAddressOf(providerId)?.let {
            ProviderFixedAddressUi(label = context.getString(R.string.settings_fixed_address_label), value = it)
        },
        apiKey = keyUi(providerId, context),
        test = test.toTestProbeUi(context, subject, elapsedSeconds),
        model = modelUi(providerId, context, test, listed),
        contextWindow = if (providerId == ProviderId.Ollama) {
            ProviderContextWindowUi(
                label = context.getString(R.string.settings_ollama_context_label),
                value = ollamaContextWindow,
            )
        } else {
            null
        },
        modelSheet = listed?.takeIf { modelSheetOpen && it.isNotEmpty() }?.let { ids ->
            ModelSheetUi(source = name, ids = ids, selected = model)
        },
        retry = cloudRetryViewState(context),
    )
}

/** The address field of a server the user runs, with the reason it cannot be used, if any. */
private fun ProviderDetailUiState.addressUi(providerId: ProviderId, context: Context): ProviderAddressUi? {
    if (!providerId.cloudProvider.usesBaseUrl) return null
    val ollama = providerId == ProviderId.Ollama
    return ProviderAddressUi(
        label = context.getString(
            if (ollama) R.string.settings_ollama_base_url_label else R.string.settings_address_label,
        ),
        value = baseUrl,
        placeholder = context.getString(
            if (ollama) R.string.settings_ollama_base_url_placeholder else R.string.settings_address_placeholder_compat,
        ),
        hint = context.getString(R.string.settings_address_hint_v1).takeUnless { ollama },
        error = when {
            !baseUrlInvalid -> null
            baseUrl.isBlank() -> context.getString(R.string.settings_address_error_empty)
            else -> context.getString(R.string.settings_address_error_not_url)
        },
        refusal = when (val refusal = addressRefusal) {
            is AddressRefusal.PublicCleartext ->
                context.getString(R.string.settings_address_refused_public_http, refusal.host)
            is AddressRefusal.HostNotLocal -> context.getString(
                if (refusal.host.isIpLiteral()) {
                    R.string.settings_address_refused_block_ip
                } else {
                    R.string.settings_address_refused_block_host
                },
                refusal.host,
            )
            // The banner above asks for this one; saying it twice would split the answer.
            is AddressRefusal.CleartextNeedsApproval, null -> null
        },
    )
}

/** The key field, for a provider that takes one. */
private fun ProviderDetailUiState.keyUi(providerId: ProviderId, context: Context): ProviderKeyUi? {
    if (!providerId.cloudProvider.usesApiKey) return null
    val compat = providerId == ProviderId.OpenAiCompatible
    return ProviderKeyUi(
        label = if (compat) {
            context.getString(R.string.settings_key_label_compat)
        } else {
            context.getString(R.string.settings_provider_api_key_label, providerId.displayName())
        },
        value = apiKey,
        marker = when (providerId) {
            ProviderId.OpenAiCompatible -> context.getString(R.string.settings_marker_optional)
            ProviderId.OpenRouter, ProviderId.Groq -> context.getString(R.string.settings_marker_required)
            else -> null
        },
        placeholder = when (providerId) {
            ProviderId.OpenAiCompatible -> context.getString(R.string.settings_key_placeholder_compat)
            ProviderId.OpenRouter -> context.getString(R.string.settings_key_placeholder_openrouter)
            ProviderId.Groq -> context.getString(R.string.settings_key_placeholder_groq)
            else -> null
        },
        hint = context.getString(R.string.settings_key_hint_compat).takeIf { compat },
    )
}

/**
 * The model field: the built-in list for the four providers Koog lists, otherwise a typed id —
 * with *Choose* once a test brought a list, and a note before it or when the server sent none.
 */
private fun ProviderDetailUiState.modelUi(
    providerId: ProviderId,
    context: Context,
    test: ConnectionTestState,
    listed: List<String>?,
): ProviderModelUi {
    builtInModels(providerId)?.let { options ->
        return ProviderModelUi(
            label = context.getString(R.string.settings_provider_model_label, providerId.displayName()),
            value = model,
            options = options,
        )
    }
    val noList = ((test as? ConnectionTestState.Finished)?.result as? ConnectionCheckResult.Failed)
        ?.failure == ConnectionFailure.NoModelList
    val required = providerId.cloudProvider.requiresModel
    return ProviderModelUi(
        label = context.getString(
            if (providerId ==
                ProviderId.Ollama
            ) {
                R.string.settings_ollama_model_label
            } else {
                R.string.settings_model_label_free
            },
        ),
        value = model,
        marker = context.getString(R.string.settings_marker_required).takeIf { required },
        placeholder = when (providerId) {
            ProviderId.OpenAiCompatible -> context.getString(R.string.settings_model_placeholder_compat)
            ProviderId.OpenRouter -> context.getString(R.string.settings_model_placeholder_openrouter)
            ProviderId.Groq -> context.getString(R.string.settings_model_placeholder_groq)
            else -> null
        },
        note = when {
            noList -> context.getString(R.string.settings_model_note_no_list)
            listed.isNullOrEmpty() -> context.getString(R.string.settings_model_note_before_test)
            else -> null
        },
        chooseLabel = context.getString(R.string.settings_model_action_choose).takeIf { !listed.isNullOrEmpty() },
    )
}

/** The built-in model list of a provider Koog lists, or `null` for one whose model is typed. */
private fun builtInModels(providerId: ProviderId): List<String>? = when (providerId) {
    ProviderId.OpenAi -> KoogModelMapper.getOpenAIModelIdList()
    ProviderId.Anthropic -> KoogModelMapper.getAnthropicModelIdList()
    ProviderId.Google -> KoogModelMapper.getGoogleModelIdList()
    ProviderId.DeepSeek -> KoogModelMapper.getDeepSeekModelIdList()
    ProviderId.OpenRouter, ProviderId.Groq, ProviderId.Ollama, ProviderId.OpenAiCompatible -> null
}

/**
 * Where a provider with a fixed address sends, shown read-only. Spelled here rather than read from
 * the client that builds the request — the screen does not reach a network client; a test holds
 * the two equal.
 */
@VisibleForTesting
internal fun fixedAddressOf(providerId: ProviderId): String? = when (providerId) {
    ProviderId.OpenRouter -> "https://openrouter.ai/api"
    ProviderId.Groq -> "https://api.groq.com/openai"
    else -> null
}

/** Whether a host is an IP literal rather than a name — the two need different advice. */
private fun String.isIpLiteral(): Boolean = all { it.isDigit() || it == '.' } || contains(':')

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
 * UI state slice surfaced by [ProviderDetailViewModel] — the values of the one provider
 * the screen is bound to.
 *
 * Kept as a single data class so the screen recomposes against one
 * snapshot.
 *
 * @property apiKey The saved key; empty when none (and unused by a provider without one).
 * @property model The chosen model id; empty when none.
 * @property baseUrl The server address of a provider reached at one; empty otherwise.
 * @property baseUrlInvalid Whether [baseUrl] as typed cannot be used.
 * @property ollamaContextWindow The Ollama context window, as text for the field.
 * @property cleartextConsentOrigin The unencrypted private origin awaiting the user's approval.
 * @property cloudRetryMaxAttempts The global cloud-retry attempt budget.
 * @property cloudRetryBaseDelayMs The global cloud-retry base delay.
 * @property addressRefusal Why the rules refuse [baseUrl], judged as it is typed by the rule a
 *   request is held to ([EndpointRule]); `null` when they do not.
 * @property modelSheetOpen Whether the server's model list is open over the form.
 */
data class ProviderDetailUiState(
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
    val baseUrlInvalid: Boolean = false,
    val ollamaContextWindow: String = "4096",
    val cleartextConsentOrigin: String? = null,
    val cloudRetryMaxAttempts: Int = SettingsDefaults.CLOUD_RETRY_MAX_ATTEMPTS_DEFAULT,
    val cloudRetryBaseDelayMs: Long = SettingsDefaults.CLOUD_RETRY_BASE_DELAY_MS_DEFAULT,
    val addressRefusal: AddressRefusal? = null,
    val modelSheetOpen: Boolean = false,
)

/**
 * ViewModel backing [ProviderDetailScreen] — owns the API-key / model
 * edits routed through [ApiKeyRepository]. Independent from the main
 * Settings VM so the provider detail screen can be reached as a deep-
 * link target without forcing the entire Settings tree to load.
 *
 * Also owns the screen's Test connection row ([connectionTestState]): it checks the values in the
 * form through [ProviderConnectionChecker], and is disabled, with the reason, while
 * [ConnectionPreconditions] says the check cannot run.
 */
@HiltViewModel
class ProviderDetailViewModel @Inject constructor(
    private val apiKeyRepository: ApiKeyRepository,
    private val networkSettings: NetworkSettings,
    private val connectionChecker: ProviderConnectionChecker,
    timeSource: TimeSource.WithComparableMarks,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderDetailUiState())
    val uiState: StateFlow<ProviderDetailUiState> = _uiState.asStateFlow()

    private val connectionTest = ConnectionTest(viewModelScope, timeSource)

    /**
     * The Test connection row: disabled with a reason, idle, running since a moment, or finished
     * with what the check found. A result is dropped as soon as a value it was run with changes.
     */
    val connectionTestState: StateFlow<ConnectionTestState> = connectionTest.state

    /** The provider [bind] attached the screen to; `null` until then. */
    private var boundProvider: CloudProvider? = null

    init {
        // The model list shows what the last test brought. When that result is dropped — the
        // values it ran with changed — the list closes with it, so the next test does not open
        // it unasked.
        connectionTest.state
            .onEach { state ->
                val listed = (state as? ConnectionTestState.Finished)?.result is ConnectionCheckResult.Reachable
                if (!listed) _uiState.update { it.copy(modelSheetOpen = false) }
            }
            .launchIn(viewModelScope)
    }

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
     * Binds the screen to the flows of [providerId]: its key when it uses one, its model, and
     * — for a provider reached at an address the user enters — that address with its
     * cleartext consent state. Idempotent — `LaunchedEffect(providerId)` invokes this on the
     * very first composition.
     *
     * @param providerId The provider the screen shows.
     */
    fun bind(providerId: ProviderId) {
        val provider = providerId.cloudProvider
        boundProvider = provider
        if (provider.usesApiKey) {
            apiKeyRepository.getApiKey(provider)
                .onEach { v -> _uiState.update { it.copy(apiKey = v.orEmpty()) } }
                .launchIn(viewModelScope)
        }
        apiKeyRepository.getModel(provider)
            .onEach { v -> _uiState.update { it.copy(model = v.orEmpty()) } }
            .launchIn(viewModelScope)
        if (provider.usesBaseUrl) {
            apiKeyRepository.getBaseUrl(provider)
                .onEach { v -> _uiState.update { it.copy(baseUrl = v.orEmpty()) } }
                .launchIn(viewModelScope)
            // The consent notice is derived, not stored: it appears whenever the
            // saved address is an unencrypted private one the user has not
            // approved, and disappears the moment either side changes. Combining
            // both flows (rather than checking once on save) is what keeps it
            // correct when the URL is edited keystroke by keystroke — this field
            // persists on every character, so there is no "save" moment to hang a
            // confirmation off.
            combine(
                apiKeyRepository.getBaseUrl(provider),
                networkSettings.approvedCleartextOrigins,
            ) { url, approved ->
                val verdict = CleartextPolicy.classify(url.orEmpty(), approved)
                (verdict as? CleartextPolicy.Verdict.NeedsApproval)?.origin
            }
                .onEach { origin -> _uiState.update { it.copy(cleartextConsentOrigin = origin) } }
                .launchIn(viewModelScope)
        }
        if (providerId == ProviderId.Ollama) {
            apiKeyRepository.getOllamaContextWindowSize()
                .onEach { v -> _uiState.update { it.copy(ollamaContextWindow = v.toString()) } }
                .launchIn(viewModelScope)
        }
        observeConnectionTestInputs(provider)
    }

    /**
     * Feeds the Test row everything a check of [provider] reads — the key and address in the form,
     * the approved unencrypted origins, "Block network from local model" — with the reason it
     * cannot run, judged by the same [ConnectionPreconditions] the check applies.
     */
    private fun observeConnectionTestInputs(provider: CloudProvider) {
        combine(
            _uiState.map { it.connectionDraft() }.distinctUntilChanged(),
            networkSettings.approvedCleartextOrigins,
            networkSettings.blockNetworkFromLocalModel,
        ) { draft, approved, localOnly -> ConnectionTestInputs(draft, approved, localOnly) }
            .onEach { inputs ->
                connectionTest.onInputs(
                    inputs = inputs,
                    refusal = ConnectionPreconditions.provider(
                        provider = provider,
                        draft = inputs.draft,
                        approvedCleartextOrigins = inputs.approvedCleartextOrigins,
                        localOnly = inputs.localOnly,
                    ),
                )
                // Said once, under the address, while it is typed: the same rule a request is
                // held to when it is sent.
                val address = inputs.draft.baseUrl.orEmpty().trim()
                val refusal = address.takeIf { provider.usesBaseUrl && CleartextPolicy.hostOf(it) != null }
                    ?.let { EndpointRule.refusal(it, inputs.approvedCleartextOrigins, inputs.localOnly) }
                _uiState.update { it.copy(addressRefusal = refusal) }
            }
            .launchIn(viewModelScope)
    }

    /** Opens the server's model list over the form; it lists what the last test brought. */
    fun openModelSheet() {
        _uiState.update { it.copy(modelSheetOpen = true) }
    }

    /** Closes the model list, after a pick or without one. */
    fun closeModelSheet() {
        _uiState.update { it.copy(modelSheetOpen = false) }
    }

    /**
     * Runs Test connection with the values in the form. Does nothing before [bind], while the row
     * is disabled, or while a check is running.
     */
    fun startConnectionTest() {
        val provider = boundProvider ?: return
        val draft = _uiState.value.connectionDraft()
        val host = connectionChecker.destination(provider, draft.baseUrl) ?: return
        connectionTest.start(host) { connectionChecker.check(provider, draft) }
    }

    /** Cancels a running Test connection; the row goes back to idle. */
    fun cancelConnectionTest() {
        connectionTest.cancel()
    }

    /**
     * Persists a key edit for [providerId]; a provider that uses no key never reaches this.
     *
     * @param providerId The provider being edited.
     * @param value The new key; blank clears it.
     */
    fun updateKey(providerId: ProviderId, value: String) {
        val provider = providerId.cloudProvider
        if (!provider.usesApiKey) return
        viewModelScope.launch { apiKeyRepository.setApiKey(provider, value.takeIf { it.isNotBlank() }) }
    }

    /**
     * Persists a model edit for [providerId].
     *
     * @param providerId The provider being edited.
     * @param value The new model id; blank clears it.
     */
    fun updateModel(providerId: ProviderId, value: String) {
        viewModelScope.launch { apiKeyRepository.setModel(providerId.cloudProvider, value.takeIf { it.isNotBlank() }) }
    }

    /**
     * Persists the server address of [providerId] and flags whether it can be used.
     *
     * The check used to be `isBlank()` alone, so the only invalid value was an
     * empty field: `192.168.1.24` was accepted silently, and — because
     * `CleartextPolicy.classify` treats anything without an `http://` prefix as
     * *not cleartext* — no consent was asked for either. The address was stored,
     * looked fine, and failed at request time. `hostOf` returns `null` exactly
     * when there is no parseable scheme and host, which is the same condition
     * every downstream gate applies.
     *
     * @param providerId The provider being edited; one that is reached at no address of its
     *   own never reaches this.
     * @param value The URL as typed; blank clears the setting.
     */
    fun updateBaseUrl(providerId: ProviderId, value: String) {
        val provider = providerId.cloudProvider
        if (!provider.usesBaseUrl) return
        val unusable = value.isBlank() || CleartextPolicy.hostOf(value) == null
        _uiState.update { it.copy(baseUrl = value, baseUrlInvalid = unusable) }
        viewModelScope.launch { apiKeyRepository.setBaseUrl(provider, value.takeIf { it.isNotBlank() }) }
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

/** The key and address a provider check reads, from the form's state. */
private fun ProviderDetailUiState.connectionDraft(): ProviderConnectionDraft =
    ProviderConnectionDraft(apiKey = apiKey, baseUrl = baseUrl)

/**
 * Everything a provider check reads, compared as a whole: a change to any of it drops a result.
 *
 * @property draft The key and address in the form.
 * @property approvedCleartextOrigins The approved unencrypted origins.
 * @property localOnly Whether "Block network from local model" is on.
 */
private data class ConnectionTestInputs(
    val draft: ProviderConnectionDraft,
    val approvedCleartextOrigins: Set<String>,
    val localOnly: Boolean,
)

/** Test tag for the unencrypted-connection consent banner on the Ollama provider screen. */
const val CLEARTEXT_CONSENT_BANNER_TAG: String = "cleartext_consent_banner"
