package app.knotwork.android.presentation.ui.tools

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.knotwork.android.domain.connection.ConnectionPreconditions
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.android.domain.connection.McpConnectionChecker
import app.knotwork.android.domain.models.McpAuth
import app.knotwork.android.domain.models.McpServerConfig
import app.knotwork.android.domain.models.McpTransport
import app.knotwork.android.domain.models.UpdateMcpServerResult
import app.knotwork.android.domain.repositories.McpServerRepository
import app.knotwork.android.domain.repositories.NetworkSettings
import app.knotwork.android.domain.repositories.ToolSettings
import app.knotwork.android.domain.services.CleartextPolicy
import app.knotwork.android.presentation.ui.common.ConnectionTest
import app.knotwork.android.presentation.ui.common.ConnectionTestState
import app.knotwork.design.screens.tools.AddMcpServerForm
import app.knotwork.design.screens.tools.McpAuthSelector
import app.knotwork.design.screens.tools.McpHeaderRow
import app.knotwork.design.screens.tools.McpTransportOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.TimeSource

/**
 * ViewModel for the standalone `McpServerConfigScreen`.
 *
 * Drives the [AddMcpServerForm] state for both Add and Edit modes:
 *
 *  - **Add** — no `originalUrl` nav argument; submission calls
 *    `ToolSettings.addMcpServer`.
 *  - **Edit** — `originalUrl` is supplied via the nav graph; the VM
 *    fetches the existing config from `ToolSettings.mcpServers`
 *    on first observation and pre-fills the form. Submission calls
 *    `ToolSettings.updateMcpServer` and disconnects the
 *    underlying client so the next fetch picks up new headers.
 *
 * Also owns the form's Test connection row ([connectionTestState]): it checks the values in the
 * form through [McpConnectionChecker] and saves nothing — *Add server* / *Save* does.
 */
@HiltViewModel
class McpServerConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val toolSettings: ToolSettings,
    private val networkSettings: NetworkSettings,
    private val mcpServerRepository: McpServerRepository,
    private val connectionChecker: McpConnectionChecker,
    timeSource: TimeSource.WithComparableMarks,
) : ViewModel() {

    /**
     * Original URL passed via the nav argument. `null` or blank means
     * Add mode; non-null means Edit mode and triggers a one-shot read
     * from `ToolSettings.mcpServers` to populate the form.
     */
    private val originalUrl: String? = savedStateHandle
        .get<String>(EXTRA_ORIGINAL_URL)
        ?.takeIf { it.isNotBlank() }

    private val _form = MutableStateFlow(
        AddMcpServerForm(editingUrl = originalUrl),
    )
    val form: StateFlow<AddMcpServerForm> = _form.asStateFlow()

    /**
     * Latest snapshot of the approved origins, so [onUrlChange] can recompute
     * the notice synchronously while the user types rather than waiting for the
     * collector to run. Written only by that collector.
     */
    private var approvedOrigins: Set<String> = emptySet()

    /** One-shot terminal events emitted after a successful submission. */
    private val _events = MutableStateFlow<Event?>(null)
    val events: StateFlow<Event?> = _events.asStateFlow()

    private val connectionTest = ConnectionTest(viewModelScope, timeSource)

    /**
     * The Test connection row: disabled with a reason, idle, running since a moment, or finished
     * with what the check found. A result is dropped as soon as a value it was run with changes.
     */
    val connectionTestState: StateFlow<ConnectionTestState> = connectionTest.state

    init {
        // The consent notice is derived from the typed URL and the approved set,
        // so it appears and disappears as the user edits rather than only at save
        // time — and it stays correct if approval happens from elsewhere.
        viewModelScope.launch {
            networkSettings.approvedCleartextOrigins.collect { approved ->
                approvedOrigins = approved
                _form.update { it.copy(cleartextConsentOrigin = consentOriginFor(it.url, approved)) }
            }
        }
        // The Test row reads the server as the form would save it — the display name aside,
        // which no check reads — and the approved origins.
        combine(
            _form.map { it.toDomain().copy(name = null) }.distinctUntilChanged(),
            networkSettings.approvedCleartextOrigins,
        ) { config, approved -> config to approved }
            .onEach { (config, approved) ->
                connectionTest.onInputs(inputs = config to approved, refusal = testRefusal(config.url, approved))
            }
            .launchIn(viewModelScope)
        if (originalUrl != null) {
            viewModelScope.launch {
                val existing = toolSettings.mcpServers.first().firstOrNull { it.url == originalUrl }
                if (existing != null) {
                    // Read the approved set here rather than relying on the
                    // collector above having run first: the two coroutines are
                    // unordered, and if this one wins, the notice would be
                    // computed from the still-empty URL. The user would then see
                    // no banner and a Save that refuses with "approve the
                    // connection above" — pointing at nothing.
                    val approved = networkSettings.approvedCleartextOrigins.first()
                    _form.update {
                        it.fromConfig(existing)
                            .copy(cleartextConsentOrigin = consentOriginFor(existing.url, approved))
                    }
                }
            }
        }
    }

    fun onUrlChange(value: String) {
        _form.update {
            it.copy(
                url = value,
                urlError = validateUrl(input = value, requireNonEmpty = false),
                cleartextConsentOrigin = consentOriginFor(value, approvedOrigins),
            )
        }
    }

    /**
     * Records the user's consent to reach the typed address over an unencrypted
     * connection. Without it the connection is refused outright by the cleartext
     * gate, so saving the server would produce a form that can never connect.
     */
    fun onApproveCleartext() {
        val origin = _form.value.cleartextConsentOrigin ?: return
        viewModelScope.launch { networkSettings.approveCleartextOrigin(origin) }
    }

    /**
     * Origin the user would have to approve for [url], or `null` when nothing
     * needs approving (encrypted, already approved, or a public host — the last
     * of which is refused outright rather than offered). Pure: the caller
     * supplies the approved set.
     */
    private fun consentOriginFor(url: String, approved: Set<String>): String? =
        (CleartextPolicy.classify(url, approved) as? CleartextPolicy.Verdict.NeedsApproval)?.origin

    /**
     * Runs Test connection with the values in the form. Does nothing while the row is disabled or
     * a check is running.
     */
    fun onTestConnection() {
        val config = _form.value.toDomain()
        connectionTest.start(CleartextPolicy.hostOf(config.url) ?: config.url) { connectionChecker.check(config) }
    }

    /** Cancels a running Test connection; the row goes back to idle. */
    fun onCancelConnectionTest() {
        connectionTest.cancel()
    }

    /**
     * Why Test connection cannot run for [url]: the checks it applies itself, then the form's own
     * stricter reading of an address (a scheme the server form accepts).
     */
    private fun testRefusal(url: String, approved: Set<String>): ConnectionRefusal? =
        ConnectionPreconditions.mcp(url, approved)
            ?: ConnectionRefusal.NotAnAddress.takeIf { validateUrl(input = url, requireNonEmpty = true) != null }

    fun onNameChange(value: String) = _form.update { it.copy(name = value) }

    fun onTransportSelect(option: McpTransportOption) = _form.update { it.copy(transport = option) }

    fun onAuthTypeSelect(option: McpAuthSelector) = _form.update { it.copy(authType = option) }

    fun onBearerTokenChange(value: String) = _form.update { it.copy(bearerToken = value) }

    fun onBasicUsernameChange(value: String) = _form.update { it.copy(basicUsername = value) }

    fun onBasicPasswordChange(value: String) = _form.update { it.copy(basicPassword = value) }

    fun onApiKeyHeaderNameChange(value: String) = _form.update { it.copy(apiKeyHeaderName = value) }

    fun onApiKeyValueChange(value: String) = _form.update { it.copy(apiKeyValue = value) }

    fun onHeaderAdd() = _form.update { it.copy(headers = it.headers + McpHeaderRow()) }

    fun onHeaderChange(index: Int, key: String, value: String) {
        _form.update { current ->
            if (index !in current.headers.indices) return@update current
            val next = current.headers.toMutableList()
            next[index] = McpHeaderRow(key = key, value = value)
            current.copy(headers = next)
        }
    }

    fun onHeaderRemove(index: Int) {
        _form.update { current ->
            if (index !in current.headers.indices) return@update current
            val next = current.headers.toMutableList()
            next.removeAt(index)
            current.copy(headers = next)
        }
    }

    /**
     * Persists the current form. On invalid URL, surfaces the error in
     * the form state and stays open. On a URL collision with another
     * persisted server (Edit mode only), surfaces the collision message
     * and stays open. On success, emits [Event.Saved] so the host can
     * `popBackStack`.
     */
    fun onSubmit() {
        val current = _form.value
        val error = validateUrl(input = current.url, requireNonEmpty = true)
        if (error != null) {
            _form.update { it.copy(urlError = error) }
            return
        }
        if (current.cleartextConsentOrigin != null) {
            _form.update { it.copy(urlError = CLEARTEXT_UNAPPROVED_MESSAGE) }
            return
        }
        viewModelScope.launch {
            _form.update { it.copy(submitting = true, urlError = null) }
            val config = current.toDomain()
            if (originalUrl != null) {
                // Drop the cached client so the next fetch reconnects with the
                // new headers/transport/URL instead of reusing the old session.
                mcpServerRepository.disconnect(serverUrl = originalUrl)
                val outcome = toolSettings.updateMcpServer(originalUrl = originalUrl, updated = config)
                when (outcome) {
                    is UpdateMcpServerResult.Success -> _events.value = Event.Saved
                    is UpdateMcpServerResult.UrlCollision -> {
                        _form.update {
                            it.copy(
                                submitting = false,
                                urlError = formatCollisionMessage(outcome),
                            )
                        }
                    }
                }
            } else {
                toolSettings.addMcpServer(config = config)
                _events.value = Event.Saved
            }
        }
    }

    /** Acknowledged by the host after [Event.Saved] has been handled. */
    fun consumeEvent() {
        _events.value = null
    }

    /** Terminal events. Currently just `Saved`; expand if more arise. */
    sealed interface Event {
        data object Saved : Event
    }

    companion object {
        /** Nav-argument key — must match `NavRoutes.MCP_SERVER_CONFIG_URL_ARG`. */
        const val EXTRA_ORIGINAL_URL: String = "originalUrl"

        private const val URL_REQUIRED_MESSAGE = "Enter a server URL."
        private const val URL_SCHEME_REQUIRED_MESSAGE =
            "URL must start with http://, https:// or mcp://."
        private const val URL_HOST_REQUIRED_MESSAGE = "URL needs a host name."
        private const val CLEARTEXT_UNAPPROVED_MESSAGE =
            "Approve the unencrypted connection above before saving."

        /**
         * Renders a [UpdateMcpServerResult.UrlCollision] into the inline
         * error string shown beneath the URL field. Falls back to the
         * URL itself when the colliding row has no display name set.
         */
        internal fun formatCollisionMessage(collision: UpdateMcpServerResult.UrlCollision): String {
            val label = collision.collidingDisplayName ?: collision.collidingUrl
            return "A server with this URL already exists: \"$label\"."
        }

        /**
         * Validates [input]. When [requireNonEmpty] is `true`, an empty
         * string returns the "required" error; otherwise empty silently
         * passes so the user isn't yelled at while typing.
         */
        @Suppress("ReturnCount")
        internal fun validateUrl(input: String, requireNonEmpty: Boolean): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return if (requireNonEmpty) URL_REQUIRED_MESSAGE else null
            val lower = trimmed.lowercase()
            val schemes = listOf("http://", "https://", "mcp://")
            val matched = schemes.firstOrNull { lower.startsWith(prefix = it) }
                ?: return URL_SCHEME_REQUIRED_MESSAGE
            val afterScheme = trimmed.substring(startIndex = matched.length)
            if (afterScheme.isEmpty() || afterScheme.startsWith(prefix = "/")) {
                return URL_HOST_REQUIRED_MESSAGE
            }
            return null
        }
    }
}

private fun AddMcpServerForm.fromConfig(config: McpServerConfig): AddMcpServerForm {
    val base = copy(
        url = config.url,
        urlError = null,
        name = config.name.orEmpty(),
        transport = config.transport.toCatalogOption(),
        authType = McpAuthSelector.NONE,
        bearerToken = "",
        basicUsername = "",
        basicPassword = "",
        apiKeyHeaderName = "",
        apiKeyValue = "",
        headers = config.headers.entries.map { McpHeaderRow(key = it.key, value = it.value) },
        editingUrl = config.url,
    )
    return when (val auth = config.auth) {
        is McpAuth.None -> base
        is McpAuth.Bearer -> base.copy(authType = McpAuthSelector.BEARER, bearerToken = auth.token)
        is McpAuth.Basic -> base.copy(
            authType = McpAuthSelector.BASIC,
            basicUsername = auth.username,
            basicPassword = auth.password,
        )
        is McpAuth.ApiKey -> base.copy(
            authType = McpAuthSelector.API_KEY,
            apiKeyHeaderName = auth.headerName,
            apiKeyValue = auth.value,
        )
    }
}

private fun AddMcpServerForm.toDomain(): McpServerConfig {
    val cleaned = headers
        .filter { it.key.isNotBlank() }
        .associate { it.key.trim() to it.value }
    return McpServerConfig(
        url = url.trim(),
        name = name.trim().takeIf { it.isNotBlank() },
        transport = transport.toDomain(),
        auth = authToDomain(),
        headers = cleaned,
    )
}

private fun AddMcpServerForm.authToDomain(): McpAuth = when (authType) {
    McpAuthSelector.NONE -> McpAuth.None
    McpAuthSelector.BEARER -> if (bearerToken.isBlank()) McpAuth.None else McpAuth.Bearer(token = bearerToken)
    McpAuthSelector.BASIC -> if (basicUsername.isBlank() && basicPassword.isBlank()) {
        McpAuth.None
    } else {
        McpAuth.Basic(username = basicUsername, password = basicPassword)
    }
    McpAuthSelector.API_KEY -> if (apiKeyHeaderName.isBlank() || apiKeyValue.isBlank()) {
        McpAuth.None
    } else {
        McpAuth.ApiKey(headerName = apiKeyHeaderName.trim(), value = apiKeyValue)
    }
}

private fun McpTransportOption.toDomain(): McpTransport = when (this) {
    McpTransportOption.SSE -> McpTransport.SSE
    McpTransportOption.StreamableHttp -> McpTransport.STREAMABLE_HTTP
}

private fun McpTransport.toCatalogOption(): McpTransportOption = when (this) {
    McpTransport.SSE -> McpTransportOption.SSE
    McpTransport.STREAMABLE_HTTP -> McpTransportOption.StreamableHttp
}
