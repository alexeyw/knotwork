package app.knotwork.android.domain.settings

/**
 * Stable identifier of a settings category. Drives the settings hub, the
 * per-category sub-screens, the key→category guidance board and (later) the
 * settings search index. The ordinal order matches the ratified display order
 * (Generation → Models → … → About).
 *
 * Pure-domain: carries no Android resources. Human-readable titles and one-line
 * summaries are resolved in the presentation layer from string resources keyed
 * by this id; the stable [SettingsCategory.iconKey] maps to an `AppIcons.*`
 * glyph there.
 */
enum class SettingsCategoryId {
    /** System prompt, sampling parameters and voice input. */
    GENERATION,

    /** Local-model backend, external providers and the embedding provider. */
    MODELS,

    /** Long-term memory extraction, compaction, retrieval tuning and data actions. */
    MEMORY,

    /** Pipeline step caps and structured-output repair budget. */
    PIPELINES,

    /** Tool approval policy, safety guardrails, workspace and HTTP limits. */
    TOOLS,

    /** Notifications plus the resume and background-approval windows. */
    BACKGROUND,

    /** Crash-reporting consent and run-trace retention. */
    PRIVACY,

    /** Identity, version/licenses and the reset action. */
    ABOUT,
}

/**
 * Visibility tier of a single setting within its category.
 *
 * [BASIC] settings render immediately at the top of the category (and the six
 * flagged [SettingEntry.hubBasic] also appear inline on the hub). [ADVANCED]
 * settings live behind the in-category "Advanced — change deliberately"
 * disclosure.
 */
enum class SettingTier {
    /** Surfaced immediately (and, when flagged [SettingEntry.hubBasic], inline on the hub). */
    BASIC,

    /** Tucked behind the in-category "Advanced — change deliberately" disclosure. */
    ADVANCED,
}

/**
 * The kind of control a settings row renders. Lets the registry describe the
 * surface without coupling the domain to any Compose type — the presentation
 * layer maps each value to a concrete catalog component.
 *
 * - [TEXTAREA] free-text editor (e.g. system instructions).
 * - [SLIDER] bounded numeric slider.
 * - [TOGGLE] on/off switch.
 * - [SEGMENTED] mutually-exclusive segmented control.
 * - [DROPDOWN] single-choice dropdown.
 * - [BACKEND] the local-model backend chooser (restart-gated).
 * - [LINK] a row that routes to another screen instead of editing a value;
 *   the destination lives in [SettingEntry.linkDestination].
 * - [MEMORY_ACTIONS] the Export / Import / Re-embed / Clear action cluster.
 * - [IDENTITY] the read-only identity card.
 * - [RESET] the "Reset all settings" destructive action.
 */
enum class SettingControlType {
    /** Free-text editor (e.g. system instructions). */
    TEXTAREA,

    /** Bounded numeric slider. */
    SLIDER,

    /** On/off switch. */
    TOGGLE,

    /** Mutually-exclusive segmented control. */
    SEGMENTED,

    /** Single-choice dropdown. */
    DROPDOWN,

    /** The local-model backend chooser (restart-gated). */
    BACKEND,

    /** A row that routes to another screen; destination in [SettingEntry.linkDestination]. */
    LINK,

    /** The Export / Import / Re-embed / Clear action cluster. */
    MEMORY_ACTIONS,

    /** The read-only identity card. */
    IDENTITY,

    /** The "Reset all settings" destructive action. */
    RESET,
}

/**
 * One row in the settings information architecture.
 *
 * A row is either a real persisted setting (a non-null [key] matching the
 * name of the preference key in its settings store's `Keys` object) or a non-editing row —
 * a [SettingControlType.LINK] to another screen, the [SettingControlType.IDENTITY]
 * card, or the [SettingControlType.RESET] action — in which case [key] is `null`.
 *
 * @property key The persisted preference key name (SCREAMING_SNAKE, identical to
 *   the `Keys` entry in its settings store), or `null` for link / identity /
 *   reset / memory-action rows that edit no single preference.
 * @property categoryId The category this row belongs to.
 * @property tier Whether the row is surfaced immediately ([SettingTier.BASIC]) or
 *   behind the Advanced disclosure ([SettingTier.ADVANCED]).
 * @property hubBasic `true` for the small cross-category set surfaced inline on
 *   the hub (exactly six rows across all categories).
 * @property controlType The kind of control rendered for this row.
 * @property synonyms Extra search terms (English) the settings search matches in
 *   addition to the human-readable name, so a query like `"cost"` can surface
 *   *Run limits* — whose own name says nothing about spend. Each entry is one
 *   term, matched on its own; there is no phrase matching.
 * @property linkDestination For [SettingControlType.LINK] rows, a stable label of
 *   the screen the row routes to (e.g. `"Provider detail"`); `null` otherwise.
 */
data class SettingEntry(
    val key: String?,
    val categoryId: SettingsCategoryId,
    val tier: SettingTier,
    val hubBasic: Boolean = false,
    val controlType: SettingControlType,
    val synonyms: List<String> = emptyList(),
    val linkDestination: String? = null,
)

/**
 * A settings category and the ordered rows it owns.
 *
 * @property id Stable category identifier.
 * @property iconKey Stable glyph key the presentation layer resolves to an
 *   `AppIcons.*` icon (e.g. `"spark"`, `"chip"`, `"brain"`).
 * @property order Zero-based display order on the hub.
 * @property entries The rows under this category, in display order (Basic rows
 *   first, then Advanced).
 */
data class SettingsCategory(
    val id: SettingsCategoryId,
    val iconKey: String,
    val order: Int,
    val entries: List<SettingEntry>,
)
