package app.knotwork.design.screens.skills

import app.knotwork.design.components.chips.Risk

/**
 * Visual variant of the Skill Library list surface.
 */
enum class SkillLibraryVisualState {
    /** Initial fetch in progress — skeleton rows. */
    Loading,

    /** Normal list of skill rows for the selected tab. */
    Default,

    /** The "Mine" tab is selected and the user has no skills of their own. */
    EmptyMine,

    /** Fatal load error — message + retry CTA. */
    Error,
}

/** The two sub-catalogues, surfaced as a 2-tab control. */
enum class SkillLibraryTab {
    /** Read-only skills shipped with the app. */
    Bundled,

    /** User-authored skills. */
    Mine,
}

/**
 * Tool-restriction indicator rendered on a library row. The three states are
 * visually distinct so "all tools" (unrestricted) can never be confused with
 * "no tools" (explicit empty allowlist). Mirrors the domain
 * `SkillToolRestriction`.
 */
enum class SkillToolIndicator {
    /** Unrestricted — filled accent pill. */
    All,

    /** Explicit empty allowlist — muted outline pill. */
    None,

    /** An explicit subset — neutral outline pill with a count. */
    Subset,
}

/**
 * Tri-state master control for the editor's tool allowlist.
 */
enum class SkillToolMode {
    /** No restriction — every tool, including ones added later. */
    All,

    /** An explicit subset selected from the tool list. */
    Restrict,

    /** Explicit empty allowlist — instruction only. */
    None,
}

/**
 * One row in the Skill Library list.
 *
 * @property id stable skill id.
 * @property name display name (bold title; clamps to one line).
 * @property description one-line summary (clamps to one line).
 * @property toolIndicator which tool-restriction pill to render.
 * @property toolCount number of tools when [toolIndicator] is
 *   [SkillToolIndicator.Subset]; ignored otherwise.
 * @property toolCountLabel pre-pluralised "N tools" label for the Subset pill
 *   (so the host can supply a proper "1 tool" singular). When blank the pill
 *   falls back to formatting [toolCount] with `SkillLibraryStrings`.
 * @property isBundled `true` shows the BUNDLED badge and limits the overflow
 *   menu to Duplicate (no Edit / Delete).
 */
data class SkillRowUi(
    val id: String,
    val name: String,
    val description: String,
    val toolIndicator: SkillToolIndicator,
    val toolCount: Int = 0,
    val toolCountLabel: String = "",
    val isBundled: Boolean,
)

/**
 * One selectable tool in the editor's Restrict list.
 *
 * @property id tool id (mono).
 * @property risk risk tier for the trailing [Risk] pill, or `null` when
 *   unknown.
 * @property description short tool description.
 * @property selected whether this tool is in the allowlist.
 */
data class SkillToolOptionUi(val id: String, val risk: Risk?, val description: String, val selected: Boolean)

/**
 * One of the five context toggles in the editor.
 *
 * @property key stable key (chatHistory / originalTask / nodeInput /
 *   longTermMemory / toolResults).
 * @property label human label.
 * @property description one-line explanation.
 * @property enabled current toggle value.
 */
data class SkillContextFlagUi(val key: String, val label: String, val description: String, val enabled: Boolean)

/**
 * Full-screen editor body state.
 *
 * @property id `null` for a new draft, non-null when editing an existing skill.
 * @property fromBundled `true` when this draft is an editable copy of a bundled
 *   skill (subtitle hint).
 * @property name current Name value.
 * @property description current Description value.
 * @property instruction current Instruction value (mono, `$VAR`-aware).
 * @property availableVariables `$VAR` tokens offered in the Insert row.
 * @property toolMode tri-state allowlist mode.
 * @property tools options shown when [toolMode] is [SkillToolMode.Restrict].
 * @property toolTotal total number of available tools (for the `N/total` hint).
 * @property contextFlags the five context toggles.
 * @property nameError `true` highlights the Name field as invalid.
 * @property instructionError `true` highlights the Instruction field as invalid.
 * @property canSave whether Save is enabled.
 * @property readOnly `true` renders the editor as a read-only viewer (no Save /
 *   Delete, non-editable fields, non-interactive controls) — used to inspect a
 *   bundled skill the user cannot mutate.
 */
data class SkillEditorUi(
    val id: String?,
    val fromBundled: Boolean,
    val name: String,
    val description: String,
    val instruction: String,
    val availableVariables: List<String>,
    val toolMode: SkillToolMode,
    val tools: List<SkillToolOptionUi>,
    val toolTotal: Int,
    val contextFlags: List<SkillContextFlagUi>,
    val nameError: Boolean,
    val instructionError: Boolean,
    val canSave: Boolean,
    val readOnly: Boolean = false,
) {
    /** Number of tools currently selected (the `N` in `N/total`). */
    val selectedToolCount: Int get() = tools.count { it.selected }
}

/**
 * Delete-confirmation dialog state.
 *
 * @property skillName the name being deleted.
 * @property dependentPipelineNames pipelines that reference the skill through a
 *   SKILL node; empty means the no-dependents (baseline) branch. Always empty
 *   in this task — the SKILL node ships later — but the dialog already renders
 *   the 1/N branches so no further work is needed then.
 */
data class SkillDeleteUi(val skillName: String, val dependentPipelineNames: List<String> = emptyList())

/**
 * Top-level immutable input to `SkillLibraryContent`.
 *
 * @property visualState rendering branch.
 * @property tab the selected sub-catalogue.
 * @property bundledCount number of bundled skills (tab counter + subtitle).
 * @property mineCount number of user skills (tab counter + subtitle).
 * @property rows rows for the *currently selected* tab.
 * @property openMenuRowId id of the row whose overflow menu is open, or `null`.
 * @property subtitle TopAppBar subtitle (e.g. `"3 bundled · 2 mine"`).
 * @property errorMessage user-visible error when [visualState] is
 *   [SkillLibraryVisualState.Error].
 */
data class SkillLibraryViewState(
    val visualState: SkillLibraryVisualState,
    val tab: SkillLibraryTab = SkillLibraryTab.Bundled,
    val bundledCount: Int = 0,
    val mineCount: Int = 0,
    val rows: List<SkillRowUi> = emptyList(),
    val openMenuRowId: String? = null,
    val subtitle: String = "",
    val errorMessage: String? = null,
) {
    init {
        require((visualState == SkillLibraryVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/** One-shot callbacks consumed by `SkillLibraryContent`. */
class SkillLibraryCallbacks(
    val onBack: () -> Unit = {},
    val onTabSelected: (SkillLibraryTab) -> Unit = {},
    val onNewSkill: () -> Unit = {},
    val onRowClick: (String) -> Unit = {},
    val onRowMenuOpen: (String) -> Unit = {},
    val onRowMenuDismiss: () -> Unit = {},
    val onViewSkill: (String) -> Unit = {},
    val onEditSkill: (String) -> Unit = {},
    val onDuplicateSkill: (String) -> Unit = {},
    val onDeleteSkill: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** Convenience factory returning a no-op callback bundle. */
fun noopSkillLibraryCallbacks(): SkillLibraryCallbacks = SkillLibraryCallbacks()

/** One-shot callbacks consumed by `SkillEditorContent`. */
class SkillEditorCallbacks(
    val onClose: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onNameChange: (String) -> Unit = {},
    val onDescriptionChange: (String) -> Unit = {},
    val onInstructionChange: (String) -> Unit = {},
    val onVariableInsert: (String) -> Unit = {},
    val onToolModeChange: (SkillToolMode) -> Unit = {},
    val onToolToggle: (String) -> Unit = {},
    val onToolsClear: () -> Unit = {},
    val onContextToggle: (String) -> Unit = {},
    val onDelete: () -> Unit = {},
)

/** Convenience factory returning a no-op editor callback bundle. */
fun noopSkillEditorCallbacks(): SkillEditorCallbacks = SkillEditorCallbacks()
