package app.knotwork.design.screens.prompts

/**
 * Visual variant of the Prompt Library surface.
 */
enum class PromptLibraryVisualState {
    /** Initial fetch in progress. */
    Loading,

    /** No prompts in any category — show empty state. */
    Empty,

    /** Normal list-of-cards layout. */
    Default,

    /** Fatal load error — message + retry CTA. */
    Error,
}

/**
 * Per-prompt list-card content surfaced by `PromptLibraryContent`.
 *
 * @property id stable identifier.
 * @property category category label rendered in the leading chip.
 * @property name prompt display name (bold title). Wraps onto multiple
 *   lines — the card never truncates the name.
 * @property body raw prompt text including `$VAR` tokens. The catalog
 *   renderer highlights placeholders inline via [PromptLibraryContent].
 * @property usedByCount how many pipelines reference this prompt.
 * @property isReadOnly `true` hides destructive affordances (Edit + Delete)
 *   so the row only exposes Preview / Duplicate. Used for bundled-preset
 *   rows that the user is not allowed to mutate.
 */
data class PromptRow(
    val id: String,
    val category: String,
    val name: String,
    val body: String,
    val usedByCount: Int,
    val isReadOnly: Boolean = false,
)

/**
 * Editor-sheet body.
 *
 * @property id `null` for a new draft, non-null when editing an existing prompt.
 * @property name current value of the Name field.
 * @property category currently-selected category (rendered as the dropdown value).
 * @property body current value of the Prompt Text field.
 * @property usedByCount surfaced in the footer hint when [id] is non-null.
 */
data class PromptEditorState(
    val id: String? = null,
    val name: String = "",
    val category: String = "",
    val body: String = "",
    val usedByCount: Int = 0,
)

/**
 * Top-level immutable input to `PromptLibraryContent`.
 *
 * @property visualState rendering branch (Loading / Empty / Default / Error).
 * @property categories ordered list of category labels surfaced as tabs.
 * @property selectedCategory currently-selected tab; controls which rows are shown.
 * @property prompts rows of the *currently selected* category.
 * @property availableVariables `$VAR` tokens offered in the editor sheet's
 * Insert row.
 * @property editor non-null when the editor sheet is open.
 * @property subtitle TopAppBar subtitle (e.g. `"5 categories · 24 prompts"`).
 * @property errorMessage user-visible error when [visualState] is [PromptLibraryVisualState.Error].
 */
data class PromptLibraryViewState(
    val visualState: PromptLibraryVisualState,
    val categories: List<String> = emptyList(),
    val selectedCategory: String = "",
    val prompts: List<PromptRow> = emptyList(),
    val availableVariables: List<String> = emptyList(),
    val editor: PromptEditorState? = null,
    val subtitle: String = "",
    val errorMessage: String? = null,
) {
    init {
        require((visualState == PromptLibraryVisualState.Error) == (errorMessage != null)) {
            "errorMessage must be non-null iff visualState == Error"
        }
    }
}

/** One-shot callbacks consumed by `PromptLibraryContent`. */
class PromptLibraryCallbacks(
    val onBack: () -> Unit = {},
    val onCategorySelected: (String) -> Unit = {},
    val onNewPrompt: () -> Unit = {},
    val onImportPrompt: () -> Unit = {},
    val onExportPrompt: (String) -> Unit = {},
    val onEditPrompt: (String) -> Unit = {},
    val onDeletePrompt: (String) -> Unit = {},
    val onDuplicatePrompt: (String) -> Unit = {},
    val onPreviewPrompt: (String) -> Unit = {},
    val onEditorNameChange: (String) -> Unit = {},
    val onEditorCategoryChange: (String) -> Unit = {},
    val onEditorBodyChange: (String) -> Unit = {},
    val onEditorVariableInsert: (String) -> Unit = {},
    val onEditorSave: () -> Unit = {},
    val onEditorCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** Convenience factory returning a no-op callback bundle. */
fun noopPromptLibraryCallbacks(): PromptLibraryCallbacks = PromptLibraryCallbacks()
