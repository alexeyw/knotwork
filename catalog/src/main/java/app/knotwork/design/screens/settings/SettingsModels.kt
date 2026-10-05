package app.knotwork.design.screens.settings

/**
 * Stable identifier of a settings category. Catalog-local mirror of the
 * domain `SettingsCategoryId`, kept here so the catalog module stays free of
 * any app dependency (the same convention as [ApproveToolCallsOption]). The
 * declared order matches the ratified hub display order
 * (Generation → … → About).
 */
enum class SettingsCategoryId {
    /** System prompt, sampling and voice input. */
    Generation,

    /** Local-model backend, providers and the active-model card. */
    Models,

    /** Long-term memory extraction, compaction, retrieval tuning and data actions. */
    Memory,

    /** Pipeline step caps, nesting depth and structured-output repair budget. */
    Pipelines,

    /** Tool approval policy, safety guardrails and managed tool/MCP links. */
    Tools,

    /** Notifications plus the resume and background-approval windows. */
    Background,

    /** Crash-reporting consent and run-trace retention. */
    Privacy,

    /** Identity, version/licenses and the reset action. */
    About,
}

/**
 * One settings-search hit rendered on the hub when a query is active.
 *
 * The match scoring lives in the domain search engine; this model carries only
 * what the result row draws: the localized name with the matched substring
 * range (for bold highlighting), the owning category (resolved to an icon and
 * breadcrumb title on the hub), the tier badge and an optional synonym chip.
 *
 * @property anchorKey Stable row anchor, passed back on tap to deep-link and
 *   highlight the destination row on its category sub-screen.
 * @property categoryId Owning category (drives the breadcrumb icon + title).
 * @property name Localized setting name.
 * @property nameMatchStart Start index of the matched substring in [name], or
 *   `-1` when the hit was by synonym / category / description (no name match).
 * @property nameMatchLength Length of the matched substring; `0` when none.
 * @property isBasic `true` for a Basic-tier row (drives the Basic/Advanced tag).
 * @property synonymHit The synonym that surfaced the row (shows the `≈ "…"`
 *   chip), or `null` when the name itself matched.
 */
data class HubSearchResultRow(
    val anchorKey: String,
    val categoryId: SettingsCategoryId,
    val name: String,
    val nameMatchStart: Int,
    val nameMatchLength: Int,
    val isBasic: Boolean,
    val synonymHit: String?,
)

/**
 * Approve-tool-calls segmented control state — mirrors
 * `app.knotwork.android.domain.models.ToolApprovalPolicy` but stays free of
 * domain imports so the catalog module keeps its zero-app dependency.
 */
enum class ApproveToolCallsOption {
    /** Confirm every tool call. */
    AllCalls,

    /** Confirm only sensitive / destructive calls. */
    Sensitive,

    /** Never prompt. */
    Never,
}

/**
 * One tunable numeric parameter rendered as a labelled slider row. Shared by
 * every category sub-screen (sampling, memory tuning, retention, etc.).
 *
 * @property id Stable id — the slider's test tag and the discriminator routed
 *   back through the per-category `on…SliderChange(id, value)` callback.
 * @property title Localised row label.
 * @property valueLabel Localised display value (e.g. `"0.70"`, `"30 d"`).
 * @property value Slider position (`[valueRange.start, valueRange.endInclusive]`).
 * @property valueRange Slider domain.
 * @property steps Discrete steps; `0` for continuous sliders.
 * @property errorText Optional inline validation error rendered beneath the
 *   slider when the last edit to this row was rejected; `null` otherwise.
 * @property anchorKey Stable settings-search anchor (the registry key) used to
 *   deep-link and flash this row; `null` when the row is not search-indexed.
 */
data class SettingSliderRow(
    val id: String,
    val title: String,
    val valueLabel: String,
    val value: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    val steps: Int = 0,
    val errorText: String? = null,
    val anchorKey: String? = null,
)

/**
 * Read-only identity card slice. `null` while the first identity read is in
 * flight — the card then renders a short skeleton.
 *
 * @property displayName Localised "Anonymous · this device" label.
 * @property avatarInitials Two-letter avatar fallback (e.g. "AA").
 * @property metaLine Secondary line ("device-id 4f3a-92d1 · keys in Keystore").
 */
data class IdentityCardState(val displayName: String, val avatarInitials: String, val metaLine: String)

/**
 * System-instructions textarea slice.
 *
 * @property value Current textarea content.
 * @property placeholder Empty-state placeholder shown inside the field.
 * @property variableChips Available `$VARIABLE` chip placeholders. Tapping a
 *   chip inserts the placeholder.
 * @property characterCount Live character count for the counter footer.
 * @property characterLimit Maximum allowed characters (counter denominator).
 * @property approximateTokens Token-count estimate shown alongside the counter.
 * @property helperText Copy beneath the counter.
 * @property validationError Optional inline error rendered beneath the counter.
 */
data class SystemInstructionsCardState(
    val value: String,
    val placeholder: String,
    val variableChips: List<String>,
    val characterCount: Int,
    val characterLimit: Int,
    val approximateTokens: Int,
    val helperText: String,
    val validationError: String?,
)

/**
 * Local-model card slice (Models category).
 *
 * @property modelName Active model name (`null` when no model is installed).
 * @property metaLine "1.4 GB · 2 048 ctx · Q4_K_M · downloaded 12 May".
 * @property backendLabel Localised inference-backend description.
 * @property backendOptions Available backend dropdown options.
 * @property selectedBackend Currently selected backend key.
 * @property testProbeText Subtitle for the Test backend row (pre-formatted).
 * @property testProbeIsError `true` when the last probe failed.
 */
data class LocalModelCardState(
    val modelName: String?,
    val metaLine: String?,
    val backendLabel: String,
    val backendOptions: List<String>,
    val selectedBackend: String,
    val testProbeText: String,
    val testProbeIsError: Boolean,
)

/**
 * External-provider list row (Models category).
 *
 * @property id Stable id (e.g. "openai", "ollama") for keying & test tags.
 * @property title Display name (`"OpenAI"`).
 * @property fingerprint Masked API-key fingerprint or `null` when not configured.
 * @property model Currently selected model name; `null` when unset.
 * @property endpointHint Optional secondary line (e.g. Ollama base URL).
 * @property isLan `true` for LAN-local providers — drives the LAN pill.
 * @property usesAddress `true` for a server the user runs: unconfigured, its row asks for the
 *   server address rather than an API key.
 * @property modelMissing `true` when the key or address is saved but no model is chosen for a
 *   provider without a default one — the row ends in "no model selected", in warn ink.
 */
data class ProviderRowState(
    val id: String,
    val title: String,
    val fingerprint: String?,
    val model: String?,
    val endpointHint: String?,
    val isLan: Boolean,
    val usesAddress: Boolean = false,
    val modelMissing: Boolean = false,
)

/**
 * Memory stats stat-cell payload.
 *
 * @property label Cell label ("Chunks", "Size", "Threads", "Avg score").
 * @property value Pre-formatted value ("1 248", "14.2 MB", "—", "0.74").
 */
data class MemoryStatCell(val label: String, val value: String)

/**
 * One selectable embedding provider in the Memory embedding dropdown.
 *
 * @property id Stable provider id used as the selection key (e.g. `"use"`).
 * @property label Human-readable provider name rendered in the dropdown.
 */
data class EmbeddingOptionRow(val id: String, val label: String)

/**
 * Destructive typed-confirm dialog payload. The typed-confirm field is enabled
 * only when [pendingInput] (trim+lowercased) equals [keyword].
 *
 * @property title Localised dialog title.
 * @property body Localised dialog body explaining the scope of the action.
 * @property keyword Word the user must type to enable the confirm button.
 * @property hint Placeholder shown in the typed-confirm field.
 * @property pendingInput Current value of the typed-confirm field.
 * @property kind Tag identifying which action the dialog is gating.
 */
data class DestructiveActionState(
    val title: String,
    val body: String,
    val keyword: String,
    val hint: String,
    val pendingInput: String,
    val kind: DestructiveActionKind,
)

/** Which destructive action a [DestructiveActionState] is gating. */
enum class DestructiveActionKind {
    /** Irreversible memory wipe — typed-keyword gate. */
    ClearMemory,

    /** Settings reset — plain confirm (touches no user data). */
    ResetSettings,
}
