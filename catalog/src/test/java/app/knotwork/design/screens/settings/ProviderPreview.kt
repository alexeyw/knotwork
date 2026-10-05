package app.knotwork.design.screens.settings

import app.knotwork.design.components.misc.TestProbeTone
import app.knotwork.design.components.misc.TestProbeUi

/**
 * Preview fixtures of the external providers: the Models list with all eight rows, the *Add
 * provider* picker, each provider form in its states, and the model list. Stateless test data;
 * every string arrives resolved, as it does in production.
 */
internal object ProviderPreview {

    /**
     * All eight providers, each row in one of its states: configured, no key, no address, and
     * a key saved with no model where the provider has no default.
     */
    fun modelsProvidersEight(): ModelsSettingsViewState = SettingsPreview.models().copy(
        providers = listOf(
            ProviderRowState("openai", "OpenAI", "sk-…3a9f", "gpt-4o-mini", null, false),
            ProviderRowState("anthropic", "Anthropic", null, null, null, false),
            ProviderRowState("google", "Google", null, null, null, false),
            ProviderRowState("deepseek", "DeepSeek", null, null, null, false),
            ProviderRowState("openrouter", "OpenRouter", "sk-or-…7c21", null, null, false, modelMissing = true),
            ProviderRowState("groq", "Groq", null, null, null, false),
            ProviderRowState(
                id = "ollama",
                title = "Ollama",
                fingerprint = "192.168.1.12:11434",
                model = "llama3.2:3b",
                endpointHint = "http://192.168.1.12:11434",
                isLan = true,
                usesAddress = true,
            ),
            ProviderRowState(
                id = "openai_compatible",
                title = "OpenAI-compatible server",
                fingerprint = null,
                model = null,
                endpointHint = null,
                isLan = true,
                usesAddress = true,
            ),
        ),
    )

    // ─── Provider detail ─────────────────────────────────────────────────────

    private const val TEST_LABEL = "Test connection"
    private const val TEST_IDLE = "Asks for the model list. Sends no prompt and spends no tokens."
    private const val COMPAT_ADDRESS = "http://192.168.1.20:8000/v1"

    private fun test(tone: TestProbeTone, status: String, head: String? = null): TestProbeUi = TestProbeUi(
        label = TEST_LABEL,
        tone = tone,
        status = status,
        head = head,
        actionLabel = when (tone) {
            TestProbeTone.Idle, TestProbeTone.Disabled -> "Test"
            TestProbeTone.Running -> "Cancel"
            else -> "Test again"
        },
    )

    /**
     * A hosted provider with a built-in model list — the form is open on its own
     * screen. Every string arrives resolved, as it does in production — this
     * module never learns which providers exist.
     */
    fun providerDetail(): ProviderDetailViewState = ProviderDetailViewState(
        title = "OpenAI",
        backContentDescription = "Back",
        apiKey = ProviderKeyUi(label = "OpenAI API key", value = "sk-live-4f2b9c"),
        test = test(TestProbeTone.Idle, TEST_IDLE),
        model = ProviderModelUi(
            label = "OpenAI model",
            value = "gpt-4o-mini",
            options = listOf("gpt-4o", "gpt-4o-mini", "o3-mini"),
        ),
        retry = cloudRetry(),
    )

    /** A hosted provider while "Block network from local model" is on: Test says so, nothing was sent. */
    fun providerDetailKeyRefused(): ProviderDetailViewState = providerDetail().copy(
        test = test(
            TestProbeTone.Refused,
            "Block network from local model is on, and OpenAI is on the internet. To use it, turn the " +
                "setting off in Settings → Tools & workspace.",
            head = "Not sent.",
        ),
    )

    /** Ollama: an address and no key, a typed model, and its context window. */
    fun providerDetailOllama(): ProviderDetailViewState = ProviderDetailViewState(
        title = "Ollama",
        backContentDescription = "Back",
        address = ProviderAddressUi(
            label = "Ollama base URL",
            value = "http://192.168.1.24:11434",
            placeholder = "http://192.168.1.100:11434",
        ),
        test = test(TestProbeTone.Idle, TEST_IDLE),
        model = ProviderModelUi(
            label = "Ollama model name",
            value = "llama3.1",
            note = "Type the id, or run Test to choose from the server’s list.",
        ),
        contextWindow = ProviderContextWindowUi(label = "Ollama context window size", value = "8192"),
        retry = cloudRetry(),
    )

    /** An address that is not an address — the error sits under the field, not in a dialog. */
    fun providerDetailInvalidUrl(): ProviderDetailViewState = providerDetailOllama().copy(
        address = requireNotNull(providerDetailOllama().address).copy(
            value = "192.168.1.24",
            error = "Not an address. It starts with http:// or https://.",
        ),
        test = test(TestProbeTone.Disabled, "Fix the address to test."),
    )

    /** A server the user runs, fresh: everything to fill in, Test waiting for the address. */
    fun providerDetailCompatEmpty(): ProviderDetailViewState = ProviderDetailViewState(
        title = "OpenAI-compatible server",
        backContentDescription = "Back",
        address = ProviderAddressUi(
            label = "Server address",
            value = "",
            placeholder = COMPAT_ADDRESS,
            hint = "Include /v1 — most servers serve the OpenAI API under it.",
        ),
        apiKey = ProviderKeyUi(
            label = "API key",
            value = "",
            marker = "optional",
            placeholder = "sk-…",
            hint = "Leave empty if the server has none. Sent as a Bearer token.",
        ),
        test = test(TestProbeTone.Disabled, "Enter the server address to test."),
        model = ProviderModelUi(
            label = "Model id",
            value = "",
            marker = "required",
            placeholder = "qwen2.5-7b-instruct",
            note = "Type the id, or run Test to choose from the server’s list.",
        ),
        retry = cloudRetry(),
    )

    /** The compat form with an address typed in. */
    private fun compatWith(address: String, refusal: String? = null): ProviderDetailViewState =
        providerDetailCompatEmpty().copy(
            address = requireNotNull(providerDetailCompatEmpty().address).copy(value = address, refusal = refusal),
        )

    /** An unencrypted LAN address waiting to be approved; Test points at the banner. */
    fun providerDetailCompatCleartext(): ProviderDetailViewState = compatWith(COMPAT_ADDRESS).copy(
        cleartextConsent = CleartextConsentUi(
            body = "Traffic to http://192.168.1.20:8000 is not encrypted. Anyone on the same network can " +
                "read what you send. Approve it only if this address is a machine you trust on your own network.",
            actionLabel = "Approve unencrypted connection",
        ),
        test = test(TestProbeTone.Disabled, "Approve the unencrypted connection above to test."),
    )

    /** A public host over http:// — refused under the field, in warn ink, as it is typed. */
    fun providerDetailCompatRefusedHttp(): ProviderDetailViewState = compatWith(
        address = "http://llm.example.com/v1",
        refusal = "llm.example.com is on the public internet, and http:// would send your prompts " +
            "unencrypted. Use https://.",
    ).copy(test = test(TestProbeTone.Disabled, "This address is not allowed. The reason is above."))

    /** A host name while "Block network from local model" is on. */
    fun providerDetailCompatRefusedBlock(): ProviderDetailViewState = compatWith(
        address = "https://gpu-box.lan/v1",
        refusal = "Block network from local model is on, so only this device and private IP addresses " +
            "are allowed. gpu-box.lan is a host name: use the server’s IP address, or turn the setting " +
            "off in Settings → Tools & workspace.",
    ).copy(test = test(TestProbeTone.Disabled, "This address is not allowed. The reason is above."))

    /** OpenRouter, fresh: the fixed address read-only, the key required. */
    fun providerDetailOpenRouterEmpty(): ProviderDetailViewState = ProviderDetailViewState(
        title = "OpenRouter",
        backContentDescription = "Back",
        fixedAddress = ProviderFixedAddressUi(label = "Sends to", value = "https://openrouter.ai/api"),
        apiKey = ProviderKeyUi(
            label = "OpenRouter API key",
            value = "",
            marker = "required",
            placeholder = "sk-or-v1-…",
        ),
        test = test(TestProbeTone.Disabled, "Enter the API key to test."),
        model = ProviderModelUi(
            label = "Model id",
            value = "",
            marker = "required",
            placeholder = "meta-llama/llama-3.3-70b-instruct",
            note = "Type the id, or run Test to choose from the server’s list.",
        ),
        retry = cloudRetry(),
    )

    /** OpenRouter after a test: 412 models listed, one chosen, *Choose* on the field. */
    fun providerDetailOpenRouterConfigured(): ProviderDetailViewState = providerDetailOpenRouterEmpty().copy(
        apiKey = requireNotNull(providerDetailOpenRouterEmpty().apiKey).copy(value = "sk-or-v1-0123456789abcdef7c21"),
        test = test(TestProbeTone.Reachable, "Connected · 412 models"),
        model = requireNotNull(providerDetailOpenRouterEmpty().model).copy(
            value = "meta-llama/llama-3.3-70b-instruct:free",
            note = null,
            chooseLabel = "Choose",
        ),
    )

    /** The server's model list, searched. */
    fun modelSheet(): ModelSheetUi = ModelSheetUi(
        source = "OpenRouter",
        ids = listOf(
            "meta-llama/llama-3.3-70b-instruct",
            "meta-llama/llama-3.3-70b-instruct:free",
            "meta-llama/llama-3.3-8b-instruct:free",
            "nvidia/llama-3.3-nemotron-super-49b-v1",
            "nvidia/llama-3.3-nemotron-super-49b-v1:free",
            "deepcogito/cogito-v2-preview-llama-3.3-70b",
            "thedrummer/anubis-70b-v1.1-llama-3.3",
            "anthropic/claude-sonnet-4.5",
            "google/gemini-2.5-flash",
        ),
        selected = "meta-llama/llama-3.3-70b-instruct:free",
    )

    /** A server that answered, but not with a list. */
    fun providerDetailModelNoList(): ProviderDetailViewState = compatWith(COMPAT_ADDRESS).copy(
        test = test(TestProbeTone.Failed, "The server answered, but not with a model list."),
        model = requireNotNull(providerDetailCompatEmpty().model).copy(
            note = "This server sent no model list. Type the id it serves.",
        ),
    )

    /** A test running for four seconds. */
    fun providerDetailTestRunning(): ProviderDetailViewState = compatWith(COMPAT_ADDRESS).copy(
        test = test(TestProbeTone.Running, "Asking 192.168.1.20 for its models · 4 s"),
    )

    /** A test that reached the server: three models, *Choose* on the field. */
    fun providerDetailTestReachable(): ProviderDetailViewState = compatWith(COMPAT_ADDRESS).copy(
        test = test(TestProbeTone.Reachable, "Connected · 3 models"),
        model = requireNotNull(providerDetailCompatEmpty().model).copy(
            value = "qwen2.5-7b-instruct",
            note = null,
            chooseLabel = "Choose",
        ),
    )

    /** A test that failed: nothing at the address — most often a missing /v1. */
    fun providerDetailTestFailed(): ProviderDetailViewState = compatWith("http://192.168.1.20:8000").copy(
        test = test(
            TestProbeTone.Failed,
            "Nothing at http://192.168.1.20:8000/models (404). Most servers need the address to end in /v1.",
        ),
    )

    /** Retry policy at its defaults; bounds match `SettingsDefaults`. */
    private fun cloudRetry(): CloudRetryViewState = CloudRetryViewState(
        sectionTitle = "Retry policy",
        sectionAnchor = "CLOUD_RETRY_POLICY",
        attempts = 3,
        attemptsLabel = "Attempts",
        attemptsValueLabel = "3",
        attemptsRange = 1f..5f,
        attemptsSteps = 3,
        attemptsAnchor = "CLOUD_RETRY_MAX_ATTEMPTS",
        delayMs = 1_000L,
        delayLabel = "Base delay",
        delayValueLabel = "1000 ms",
        delayRange = 100f..10_000f,
        delayAnchor = "CLOUD_RETRY_BASE_DELAY_MS",
    )

    /** Every provider on offer, grouped as the picker lists them; four already set up. */
    fun providerPicker(): ProviderPickerViewState = ProviderPickerViewState(
        title = "Add provider",
        backContentDescription = "Back",
        groups = listOf(
            ProviderPickerGroupUi(
                title = "Hosted",
                rows = listOf(
                    ProviderPickerRowUi(id = "OpenAi", title = "OpenAI", added = true),
                    ProviderPickerRowUi(id = "Anthropic", title = "Anthropic"),
                    ProviderPickerRowUi(id = "Google", title = "Google"),
                    ProviderPickerRowUi(id = "DeepSeek", title = "DeepSeek"),
                    ProviderPickerRowUi(id = "OpenRouter", title = "OpenRouter", added = true),
                    ProviderPickerRowUi(id = "Groq", title = "Groq"),
                ),
            ),
            ProviderPickerGroupUi(
                title = "Your own server",
                rows = listOf(
                    ProviderPickerRowUi(id = "Ollama", title = "Ollama", added = true),
                    ProviderPickerRowUi(
                        id = "OpenAiCompatible",
                        title = "OpenAI-compatible server",
                        description = "vLLM, LM Studio, llama.cpp, or any server that speaks the OpenAI API.",
                        added = true,
                    ),
                ),
            ),
        ),
        addedLabel = "added",
    )
}
