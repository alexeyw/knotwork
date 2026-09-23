package app.knotwork.design.screens.settings

/**
 * Internal preview fixtures backing the settings hub + category snapshot suites.
 * Stateless test data only — no behaviour. Mirrors the `screens-settings-ia`
 * design gallery states (hub default/loading/restart, Memory full matrix,
 * Generation / Tools / About for breadth).
 */
internal object SettingsPreview {

    // ─── Hub ─────────────────────────────────────────────────────────────────

    fun hubDefault(): SettingsHubViewState = SettingsHubViewState(
        loading = false,
        subtitleVersion = "0.9.2",
        subtitleChannel = "alpha",
        subtitleBuildDate = "2026.05.18",
        systemInstructionsPreview = "Be concise. Prefer bullet points over prose.",
        approveSelection = ApproveToolCallsOption.Sensitive,
        approveAllLabel = "All",
        approveSensitiveLabel = "Sensitive",
        approveNeverLabel = "Never",
        blockDestructive = true,
        backendLabel = "NPU (QNN) · auto-fallback to GPU then CPU.",
        selectedBackend = "NPU · auto",
        backendOptions = listOf("NPU · auto", "GPU", "CPU"),
        crashReportingEnabled = false,
    )

    fun hubLoading(): SettingsHubViewState = hubDefault().copy(loading = true)

    fun hubRestart(): SettingsHubViewState =
        hubDefault().copy(restartRequiredMessage = "Backend change requires restart.")

    /** Hub with an active "max" query — results across categories incl. a synonym hit. */
    fun hubSearchResults(): SettingsHubViewState = hubDefault().copy(
        // Every row here must be one the engine could actually return for this
        // query. The run-limits row used to sit in this list on the strength of
        // a "max steps" synonym that never existed; it is keyed on its own terms
        // now — steps, tokens, limit, cap, budget, cost, spend — none of which
        // is "max", so it does not belong in a "max" result set. Its place is
        // taken by TOOL_CALL_TIMEOUT_MS, which matches "max" by synonym alone
        // and so keeps the synonym-chip state this fixture exists to show.
        searchQuery = "max",
        searchResults = listOf(
            searchRow("MAX_CONTEXT_LENGTH", SettingsCategoryId.Generation, "Max context length", 0, 3),
            searchRow(
                "TOOL_CALL_TIMEOUT_MS",
                SettingsCategoryId.Tools,
                "Tool call timeout",
                synonym = "max",
            ),
            searchRow("MAX_MEMORY_CHUNKS", SettingsCategoryId.Memory, "Max memory chunks", 0, 3),
            searchRow("WORKSPACE_MAX_FILE_SIZE_BYTES", SettingsCategoryId.Tools, "Largest file", 10, 3),
            searchRow("RESUME_MAX_AGE_HOURS", SettingsCategoryId.Background, "Resume max age", 7, 3),
        ),
    )

    /**
     * A hit set containing a **Basic** row, which the "max" fixture above
     * cannot produce.
     *
     * Its existence is not decorative. Every entry matching "max" is Advanced,
     * so once the run-limits row stopped answering to that query the Basic tier
     * had no depiction anywhere — the tag string and its primary-colour
     * treatment were rendered by nothing and asserted by nothing.
     *
     * Producible as written: "limit" appears in exactly two indexed names —
     * *Memory summary limit* and *Run limits* — both as substrings, so both
     * rank the same and the stable sort keeps registry order, Memory first.
     */
    fun hubSearchBasicTier(): SettingsHubViewState = hubDefault().copy(
        searchQuery = "limit",
        searchResults = listOf(
            searchRow("MEMORY_SUMMARY_DEFAULT_LIMIT", SettingsCategoryId.Memory, "Memory summary limit", 15, 5),
            searchRow("LINK_RUN_LIMITS", SettingsCategoryId.Pipelines, "Run limits", 4, 5, basic = true),
        ),
    )

    /** Hub with a query that matches nothing — the calm empty state. */
    fun hubSearchEmpty(): SettingsHubViewState = hubDefault().copy(searchQuery = "lidar", searchResults = emptyList())

    private fun searchRow(
        anchor: String,
        category: SettingsCategoryId,
        name: String,
        nameStart: Int = -1,
        len: Int = 0,
        basic: Boolean = false,
        synonym: String? = null,
    ): HubSearchResultRow = HubSearchResultRow(anchor, category, name, nameStart, len, basic, synonym)

    // ─── Generation ──────────────────────────────────────────────────────────

    fun generation(): GenerationSettingsViewState = GenerationSettingsViewState(
        systemInstructions = systemInstructions(),
        advancedSliders = listOf(
            SettingSliderRow(SLIDER_TEMPERATURE, "Temperature", "0.7", 0.7f, 0f..2f),
            SettingSliderRow(SLIDER_TOP_K, "Top-K", "40", 40f, 1f..100f, steps = 99),
            SettingSliderRow(SLIDER_TOP_P, "Top-P", "0.90", 0.9f, 0f..1f),
            SettingSliderRow(
                SLIDER_MAX_CONTEXT,
                "Max context",
                "4096 tok",
                4096f,
                512f..8192f,
                steps = 14,
                anchorKey = "MAX_CONTEXT_LENGTH",
            ),
            SettingSliderRow(
                SLIDER_AUDIO_MAX_DURATION,
                "Voice input length",
                "60 s",
                60f,
                5f..120f,
                anchorKey = "AUDIO_MAX_DURATION_SEC",
            ),
        ),
    )

    // ─── Models ──────────────────────────────────────────────────────────────

    fun models(): ModelsSettingsViewState = ModelsSettingsViewState(
        localModel = localModel(),
        providers = providers(),
    )

    fun modelsRestart(): ModelsSettingsViewState =
        models().copy(restartRequiredMessage = "Backend change requires restart.")

    // ─── Memory ──────────────────────────────────────────────────────────────

    fun memory(): MemorySettingsViewState = MemorySettingsViewState(
        stats = listOf(
            MemoryStatCell("Chunks", "1 248"),
            MemoryStatCell("Size", "14.2 MB"),
            MemoryStatCell("Threads", "38"),
            MemoryStatCell("Avg score", "0.74"),
        ),
        autoExtractEnabled = true,
        autoExtractLabel = "Auto-extract from conversations",
        compactionEnabled = true,
        compactionLabel = "Background compaction",
        chatHistoryCompressionEnabled = true,
        chatHistoryCompressionLabel = "Compress chat history",
        advancedSliders = memorySliders(),
        verboseLoggingEnabled = false,
        embeddingTitle = "Embedding model",
        embeddingOptions = listOf(
            EmbeddingOptionRow("use", "On-device (Universal Sentence Encoder)"),
            EmbeddingOptionRow("openai_3_small", "OpenAI (text-embedding-3-small)"),
        ),
        selectedEmbeddingId = "use",
        selectedEmbeddingLabel = "On-device (Universal Sentence Encoder)",
        exportLabel = "Export base",
        importLabel = "Import",
        reembedLabel = "Re-embed",
        clearLabel = "Clear",
    )

    fun memoryError(): MemorySettingsViewState = memory().copy(
        advancedSliders = memorySliders().map {
            if (it.id == SLIDER_MEMORY_MAX_CHUNKS) it.copy(valueLabel = "24 000", value = 24_000f) else it
        },
        validationError = "Out of range — max is 20 000.",
    )

    fun memoryPending(): MemorySettingsViewState = memory().copy(reembedProgressPercent = 42)

    fun memoryReembedBanner(): MemorySettingsViewState =
        memory().copy(reembedBanner = "Stored vectors were created with a different provider. Re-embed to realign.")

    // ─── Pipelines ───────────────────────────────────────────────────────────

    // ─── Run limits ──────────────────────────────────────────────────────────

    /**
     * Default run limits: the background step ceiling has never been set, so it
     * inherits — the state a fresh install is actually in, and the one the
     * qualifier exists to explain.
     */
    fun runLimits(): RunLimitsViewState = RunLimitsViewState(
        intro = "An autonomous run stops itself when it reaches one of these limits. Everything a run starts " +
            "counts towards them — pipelines it calls, and every time it resumes.",
        stepsGroupLabel = "Steps",
        steps = LimitSliderRowState(
            label = "Steps per run",
            valueLabel = "15",
            description = "How many steps a run may take before it stops. One step is one node execution.",
            value = 15f,
            valueRange = 5f..100f,
            minLabel = "5",
            maxLabel = "100",
        ),
        stepsBackground = LimitSliderRowState(
            label = "Steps per background run",
            valueLabel = "15",
            description = "Not set separately, so runs you did not start yourself — a trigger, a schedule, the " +
                "Quick Settings tile, or another app — use the same limit as above.",
            qualifier = "Same as above",
            value = 15f,
            valueRange = 5f..100f,
            minLabel = "5",
            maxLabel = "100",
        ),
        tokensGroupLabel = "Tokens",
        tokens = LimitSliderRowState(
            label = "Tokens per run",
            valueLabel = "1,000,000",
            description = "How many tokens a run may send and receive in total.",
            value = 6f,
            valueRange = 4f..7f,
            minLabel = "10,000",
            maxLabel = "10,000,000",
        ),
        tokensBackground = LimitSliderRowState(
            label = "Tokens per background run",
            valueLabel = "100,000",
            description = "Runs you did not start yourself get a lower token limit by default, because no " +
                "one is watching them finish.",
            value = 5f,
            valueRange = 4f..7f,
            minLabel = "10,000",
            maxLabel = "10,000,000",
        ),
        spendGroupLabel = "Spend",
        spend = StatementRowState(
            label = "Spending limit",
            stateWord = "Not measured",
            body = "The app runs on your own API key, so it never sees your bill and cannot measure or cap " +
                "what a run costs. The token limit above is the closest control.",
        ),
        softNote = "A run in an open chat warns you when it passes 75% of a limit, while there is still " +
            "room to finish. The warning point is not adjustable, and a run that resumes after a pause " +
            "may warn again.",
    )

    /** The user raised the interactive cap; the inherited row follows it. */
    fun runLimitsRaised(): RunLimitsViewState = runLimits().let { base ->
        base.copy(
            steps = base.steps.copy(valueLabel = "40", value = 40f),
            stepsBackground = base.stepsBackground.copy(valueLabel = "40", value = 40f),
            tokens = base.tokens.copy(valueLabel = "4,000,000", value = 6.6f),
        )
    }

    /** The background ceiling has been set on its own: no qualifier, its own copy. */
    fun runLimitsBackgroundSet(): RunLimitsViewState = runLimits().let { base ->
        base.copy(
            stepsBackground = base.stepsBackground.copy(
                valueLabel = "8",
                value = 8f,
                qualifier = null,
                description = "Applies to runs you did not start yourself — a trigger, a schedule, the " +
                    "Quick Settings tile, or another app.",
            ),
        )
    }

    fun pipelines(): PipelinesSettingsViewState = PipelinesSettingsViewState(
        runLimitsSummary = "15 steps · 1,000,000 tokens per run",
        advancedSliders = listOf(
            SettingSliderRow(SLIDER_PIPELINE_NESTING_DEPTH, "Max nesting depth", "3", 3f, 1f..5f, steps = 3),
            SettingSliderRow(
                SLIDER_PIPELINE_STRUCTURED_REPAIRS,
                "Structured-output repairs",
                "2",
                2f,
                0f..4f,
                steps = 3,
            ),
        ),
    )

    // ─── Tools ───────────────────────────────────────────────────────────────

    fun tools(): ToolsSettingsViewState = ToolsSettingsViewState(
        approveSelection = ApproveToolCallsOption.Sensitive,
        approveAllLabel = "All",
        approveSensitiveLabel = "Sensitive",
        approveNeverLabel = "Never",
        blockDestructive = true,
        blockNetwork = true,
        advancedSliders = toolsSliders(),
    )

    private fun toolsSliders(): List<SettingSliderRow> = listOf(
        SettingSliderRow(
            SLIDER_TOOL_CALL_TIMEOUT,
            "Approval wait",
            "60 s",
            60f,
            5f..300f,
            anchorKey = "TOOL_CALL_TIMEOUT_MS",
        ),
        SettingSliderRow(
            SLIDER_WORKSPACE_MAX_FILE_SIZE,
            "Largest file",
            "5 MB",
            5f,
            1f..50f,
            anchorKey = "WORKSPACE_MAX_FILE_SIZE_BYTES",
        ),
        SettingSliderRow(
            SLIDER_WORKSPACE_MAX_TOTAL,
            "Workspace size limit",
            "100 MB",
            100f,
            10f..1024f,
            anchorKey = "WORKSPACE_MAX_TOTAL_BYTES",
        ),
        SettingSliderRow(
            SLIDER_WORKSPACE_READ_TOKEN_BUDGET,
            "Single read budget",
            "2000 tok",
            2000f,
            200f..8000f,
            anchorKey = "WORKSPACE_READ_TOKEN_BUDGET",
        ),
        SettingSliderRow(
            SLIDER_HTTP_TOOL_MAX_RESPONSE,
            "Largest tool response",
            "1024 KB",
            1024f,
            64f..8192f,
            anchorKey = "HTTP_TOOL_MAX_RESPONSE_BYTES",
        ),
    )

    // ─── Background ──────────────────────────────────────────────────────────

    fun background(): BackgroundSettingsViewState = BackgroundSettingsViewState(
        scheduledResultsEnabled = true,
        shareTargetPipelineLabel = "Default System Pipeline",
        shareReuseSessionEnabled = true,
        quickTilePipelineLabel = "Not set",
        externalAutomationEnabled = false,
        externalAutomationPipelineLabel = "Not set",
        externalAutomationUnbound = false,
        externalAutomationJournalLabel = "No requests yet",
        advancedSliders = listOf(
            SettingSliderRow(SLIDER_BACKGROUND_RESUME_MAX_AGE, "Resume window", "48 h", 48f, 1f..168f),
            SettingSliderRow(SLIDER_BACKGROUND_APPROVAL_WINDOW, "Approval window", "24 h", 24f, 1f..168f),
        ),
    )

    /**
     * External automation switched on with nothing bound: reachable, inert, and
     * the state whose whole job is to look visibly incomplete rather than
     * quietly do nothing.
     */
    fun backgroundExternalUnbound(): BackgroundSettingsViewState = background().copy(
        externalAutomationEnabled = true,
        externalAutomationPipelineLabel = "Not set — every request is refused",
        externalAutomationUnbound = true,
    )

    /** External automation switched on and bound — the working state, with a refusal to diagnose. */
    fun backgroundExternalBound(): BackgroundSettingsViewState = background().copy(
        externalAutomationEnabled = true,
        externalAutomationPipelineLabel = "Morning digest",
        externalAutomationUnbound = false,
        externalAutomationJournalLabel = "Refused · 14:32",
    )

    // ─── Privacy ─────────────────────────────────────────────────────────────

    fun privacy(): PrivacySettingsViewState = PrivacySettingsViewState(
        crashReportingEnabled = false,
        advancedSliders = listOf(
            SettingSliderRow(SLIDER_PRIVACY_RETENTION_RUNS, "Trace retention · runs", "30", 30f, 5f..100f),
            SettingSliderRow(SLIDER_PRIVACY_RETENTION_AGE, "Trace retention · age", "30 d", 30f, 7f..180f),
        ),
    )

    /**
     * Privacy state for the FOSS distribution: the crash-reporting consent row is
     * hidden (`crashReportingAvailable = false`), leaving only the Advanced
     * retention sliders and a Basic row count of zero.
     */
    fun privacyFossHidden(): PrivacySettingsViewState = privacy().copy(crashReportingAvailable = false)

    // ─── Usage statistics ────────────────────────────────────────────────────

    fun usageTelemetry(): UsageTelemetryViewState = UsageTelemetryViewState(
        recordingEnabled = true,
        isEmpty = false,
        runsHeadline = "12 total",
        outcomes = listOf(
            UsageStatRow("Completed", "9 (75%)"),
            UsageStatRow("Failed", "2 (16%)"),
            UsageStatRow("Cancelled", "1 (8%)"),
            UsageStatRow("Interrupted", "0 (0%)"),
        ),
        pipelines = listOf(
            UsageStatRow("Daily digest", "7"),
            UsageStatRow("Research assistant", "5"),
        ),
        triggersHeadline = "4 total",
        triggers = listOf(
            UsageStatRow("Charging", "3"),
            UsageStatRow("Daily schedule", "1"),
        ),
        activeDays = listOf(
            UsageStatRow("Days", "5"),
            UsageStatRow("First", "2026-06-20"),
            UsageStatRow("Last", "2026-06-25"),
        ),
        retention = listOf(
            UsageStatRow("Active days", "4 / 7"),
            UsageStatRow("Week before", "2 / 7"),
            UsageStatRow("Pipelines used", "2"),
            UsageStatRow("Current streak", "3 days"),
            UsageStatRow("Returns after a break", "1"),
            UsageStatRow("Longest break", "5 days"),
            UsageStatRow("First week after install", "6 / 7"),
        ),
        onboarding = listOf(
            UsageStatRow("Time to first value", "7:24"),
            UsageStatRow("Model download", "5:00"),
            UsageStatRow("Excluding model download", "2:24"),
        ),
    )

    /** Empty Usage statistics state (recording on, nothing recorded yet). */
    fun usageTelemetryEmpty(): UsageTelemetryViewState = UsageTelemetryViewState(
        recordingEnabled = true,
        isEmpty = true,
        runsHeadline = "0 total",
        outcomes = emptyList(),
        pipelines = emptyList(),
        triggersHeadline = "0 total",
        triggers = emptyList(),
        activeDays = emptyList(),
    )

    // ─── About ───────────────────────────────────────────────────────────────

    fun about(): AboutSettingsViewState = AboutSettingsViewState(
        identity = identity(),
        versionLine = "v0.9.2 · alpha · 2026.05.18",
    )

    // ─── Shared building blocks ──────────────────────────────────────────────

    private fun identity(): IdentityCardState = IdentityCardState(
        displayName = "Anonymous · this device",
        avatarInitials = "AA",
        metaLine = "device-id 4f3a-92d1 · keys in Android Keystore",
    )

    private fun systemInstructions(): SystemInstructionsCardState = SystemInstructionsCardState(
        value = "Be concise. Prefer bullet points over prose. " +
            "Use \$DATE for any date reference and \$LANG for the user's language.",
        placeholder = "Be concise.",
        variableChips = listOf("\$DATE", "\$TIME", "\$LANG", "\$LOCATION", "\$USER", "\$DEVICE"),
        characterCount = 218,
        characterLimit = 4_000,
        approximateTokens = 62,
        helperText = "Prepended to every system prompt the agent sends.",
        validationError = null,
    )

    private fun localModel(): LocalModelCardState = LocalModelCardState(
        modelName = "gemma-2b-it-q4",
        metaLine = "1.4 GB · 2 048 ctx · Q4_K_M · downloaded 12 May",
        backendLabel = "NPU (QNN) · auto-fallback to GPU then CPU.",
        backendOptions = listOf("NPU · auto", "GPU", "CPU"),
        selectedBackend = "NPU · auto",
        testProbeText = "Last probe · 248 tok in 1.42 s · 174 tok/s",
        testProbeIsError = false,
    )

    private fun providers(): List<ProviderRowState> = listOf(
        ProviderRowState("openai", "OpenAI", "sk-…3a9f", "gpt-4o-mini", null, false),
        ProviderRowState("anthropic", "Anthropic", "sk-ant-…b21c", "claude-sonnet-4", null, false),
        ProviderRowState("google", "Google", null, null, null, false),
        ProviderRowState("ollama", "Ollama", "192.168.1.42:11434", "mistral-7b · 4096", "192.168.1.42:11434", true),
    )

    private fun memorySliders(): List<SettingSliderRow> = listOf(
        SettingSliderRow(
            SLIDER_MEMORY_SEARCH_TOP_K,
            "Search results (top-K)",
            "5",
            5f,
            1f..20f,
            anchorKey = "MEMORY_SEARCH_TOP_K",
        ),
        SettingSliderRow(
            SLIDER_MEMORY_SEARCH_THRESHOLD,
            "Similarity threshold",
            "0.55",
            0.55f,
            0.3f..0.9f,
            anchorKey = "MEMORY_SEARCH_THRESHOLD",
        ),
        SettingSliderRow(
            SLIDER_MEMORY_RECENCY_HALF_LIFE,
            "Recency half-life",
            "30 d",
            30f,
            7f..180f,
            anchorKey = "MEMORY_RECENCY_HALF_LIFE_DAYS",
        ),
        SettingSliderRow(
            SLIDER_MEMORY_COMPACTION_AGE,
            "Compaction age",
            "30 d",
            30f,
            7f..90f,
            anchorKey = "MEMORY_COMPACTION_AGE_DAYS",
        ),
        SettingSliderRow(
            SLIDER_MEMORY_MAX_CHUNKS,
            "Max chunks",
            "5 000",
            5000f,
            1000f..20000f,
            anchorKey = "MAX_MEMORY_CHUNKS",
        ),
        SettingSliderRow(
            SLIDER_MEMORY_COMPRESSION_THRESHOLD,
            "Compression threshold",
            "3 500",
            3500f,
            1000f..8000f,
            anchorKey = "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS",
        ),
        SettingSliderRow(
            SLIDER_MEMORY_LIVE_WINDOW,
            "Live message window",
            "10",
            10f,
            2f..50f,
            anchorKey = "CHAT_HISTORY_LIVE_WINDOW_SIZE",
        ),
        SettingSliderRow(
            SLIDER_MEMORY_SUMMARY_LIMIT,
            "Memory summary size",
            "5",
            5f,
            1f..50f,
            anchorKey = "MEMORY_SUMMARY_DEFAULT_LIMIT",
        ),
    )

    // ─── Provider detail ─────────────────────────────────────────────────────

    /**
     * A key-based provider. Every string arrives resolved, as it does in
     * production — this module never learns which providers exist.
     */
    fun providerDetail(): ProviderDetailViewState = ProviderDetailViewState(
        title = "OpenAI settings",
        providerLabel = "OpenAI",
        backContentDescription = "Back",
        apiKey = "sk-live-4f2b9c",
        apiKeyLabel = "OpenAI API key",
        model = "gpt-4o-mini",
        modelLabel = "OpenAI model",
        availableModels = listOf("gpt-4o", "gpt-4o-mini", "o3-mini"),
        retry = cloudRetry(),
    )

    /**
     * Ollama: `apiKey = null`, which hides the field rather than showing an
     * empty one. It runs LAN-local without authentication, and a blank key box
     * would read as one the user had forgotten to fill.
     */
    fun providerDetailOllama(): ProviderDetailViewState = providerDetail().copy(
        title = "Ollama settings",
        providerLabel = "Ollama",
        apiKey = null,
        apiKeyLabel = "",
        model = "llama3.1",
        modelLabel = "Model name",
        availableModels = emptyList(),
        ollama = OllamaProviderInputs(
            baseUrl = "http://192.168.1.24:11434",
            baseUrlPlaceholder = "http://localhost:11434",
            baseUrlValidationError = null,
            contextWindow = "8192",
            contextWindowLabel = "Context window",
            baseUrlLabel = "Base URL",
        ),
    )

    /**
     * The state the whole screen was migrated for: an unencrypted origin waiting
     * to be allowed. It had no baseline at all, on a screen that had quietly
     * grown a visual language of its own.
     */
    fun providerDetailCleartext(): ProviderDetailViewState = providerDetailOllama().copy(
        cleartextConsent = CleartextConsentUi(
            body = "Traffic to 192.168.1.24:11434 is not encrypted. Allow it for this address?",
            actionLabel = "Allow",
        ),
    )

    /** A base URL the parser rejected — the error sits under the field, not in a dialog. */
    fun providerDetailInvalidUrl(): ProviderDetailViewState = providerDetailOllama().copy(
        ollama = requireNotNull(providerDetailOllama().ollama).copy(
            baseUrl = "192.168.1.24",
            baseUrlValidationError = "Enter a full URL, including http:// or https://",
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

    /** Every provider on offer, in the order the picker lists them. */
    fun providerPicker(): ProviderPickerViewState = ProviderPickerViewState(
        title = "Add provider",
        backContentDescription = "Back",
        rows = listOf(
            ProviderPickerRowUi(id = "OpenAi", title = "OpenAI"),
            ProviderPickerRowUi(id = "Anthropic", title = "Anthropic"),
            ProviderPickerRowUi(id = "Google", title = "Google"),
            ProviderPickerRowUi(id = "DeepSeek", title = "DeepSeek"),
            ProviderPickerRowUi(id = "Ollama", title = "Ollama"),
        ),
    )
}
