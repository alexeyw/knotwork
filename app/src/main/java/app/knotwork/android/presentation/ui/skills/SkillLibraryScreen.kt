package app.knotwork.android.presentation.ui.skills

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.models.SkillToolRestriction
import app.knotwork.android.presentation.ui.common.asString
import app.knotwork.android.presentation.ui.tools.BUILT_IN_TOOL_DESCRIPTIONS
import app.knotwork.design.screens.skills.SkillContextFlagUi
import app.knotwork.design.screens.skills.SkillDeleteDialogContent
import app.knotwork.design.screens.skills.SkillDeleteStrings
import app.knotwork.design.screens.skills.SkillDeleteUi
import app.knotwork.design.screens.skills.SkillEditorCallbacks
import app.knotwork.design.screens.skills.SkillEditorContent
import app.knotwork.design.screens.skills.SkillEditorStrings
import app.knotwork.design.screens.skills.SkillEditorUi
import app.knotwork.design.screens.skills.SkillLibraryCallbacks
import app.knotwork.design.screens.skills.SkillLibraryContent
import app.knotwork.design.screens.skills.SkillLibraryStrings
import app.knotwork.design.screens.skills.SkillLibraryTab
import app.knotwork.design.screens.skills.SkillLibraryViewState
import app.knotwork.design.screens.skills.SkillLibraryVisualState
import app.knotwork.design.screens.skills.SkillRowUi
import app.knotwork.design.screens.skills.SkillToolIndicator
import app.knotwork.design.screens.skills.SkillToolOptionUi

/**
 * App-side Skill Library mapper. Subscribes to [SkillLibraryViewModel.uiState],
 * folds it into the catalog [SkillLibraryViewState], and routes between the
 * list, the full-screen editor (`SkillEditorContent`) and the delete dialog
 * (`SkillDeleteDialogContent`).
 *
 * The editor replaces the list while open — the catalog editor is a full-screen
 * surface (per the design decision), so it is rendered instead of the list
 * rather than over it.
 *
 * @param modifier optional layout modifier.
 * @param viewModel the screen ViewModel.
 * @param onBack back-navigation callback.
 */
@Composable
fun SkillLibraryScreen(
    modifier: Modifier = Modifier,
    viewModel: SkillLibraryViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val editor = uiState.editor

    if (editor != null) {
        SkillEditorContent(
            state = uiState.toEditorUi(editor),
            modifier = modifier,
            strings = skillEditorStrings(),
            callbacks = SkillEditorCallbacks(
                onClose = viewModel::closeEditor,
                onSave = viewModel::saveEditor,
                onNameChange = viewModel::onEditorNameChange,
                onDescriptionChange = viewModel::onEditorDescriptionChange,
                onInstructionChange = viewModel::onEditorInstructionChange,
                onVariableInsert = viewModel::onEditorVariableInsert,
                onToolModeChange = viewModel::onEditorToolModeChange,
                onToolToggle = viewModel::onEditorToolToggle,
                onToolsClear = viewModel::onEditorToolsClear,
                onContextToggle = viewModel::onEditorContextToggle,
                onDelete = { editor.id?.let(viewModel::requestDelete) },
            ),
        )
        return
    }

    val resolvedError = uiState.errorMessage?.asString()
    val fallbackError = stringResource(R.string.skills_error_body)
    SkillLibraryContent(
        state = uiState.toListViewState(
            subtitle = stringResource(
                R.string.skills_subtitle_format,
                uiState.bundledSkills.size,
                uiState.userSkills.size,
            ),
            resolvedError = resolvedError,
            fallbackError = fallbackError,
            subsetLabels = uiState.subsetLabels(),
        ),
        modifier = modifier,
        strings = skillLibraryStrings(),
        callbacks = SkillLibraryCallbacks(
            onBack = onBack,
            onTabSelected = viewModel::selectTab,
            onNewSkill = viewModel::openNewSkill,
            onRowClick = viewModel::onRowClick,
            onRowMenuOpen = viewModel::openRowMenu,
            onRowMenuDismiss = viewModel::dismissRowMenu,
            onViewSkill = viewModel::openViewSkill,
            onEditSkill = viewModel::openEditSkill,
            onDuplicateSkill = viewModel::duplicateSkill,
            onDeleteSkill = viewModel::requestDelete,
            onRetry = viewModel::retry,
        ),
    )

    val deleteTarget = uiState.deleteTarget
    if (deleteTarget != null) {
        Dialog(onDismissRequest = viewModel::cancelDelete) {
            SkillDeleteDialogContent(
                state = SkillDeleteUi(
                    skillName = deleteTarget.name,
                    dependentPipelineNames = deleteTarget.dependentPipelineNames,
                ),
                strings = skillDeleteStrings(),
                onConfirm = viewModel::confirmDelete,
                onCancel = viewModel::cancelDelete,
            )
        }
    }
}

/** Pre-pluralised "N tools" labels keyed by skill id (for the Subset pill). */
@Composable
private fun SkillLibraryUiState.subsetLabels(): Map<String, String> = (bundledSkills + userSkills)
    .filter { it.toolRestriction == SkillToolRestriction.SUBSET }
    .associate { skill ->
        val count = skill.toolAllowlist?.size ?: 0
        skill.id to pluralStringResource(R.plurals.skills_tools_count, count, count)
    }

/** Maps the app state onto the catalog list view state for the active tab. */
private fun SkillLibraryUiState.toListViewState(
    subtitle: String,
    resolvedError: String?,
    fallbackError: String,
    subsetLabels: Map<String, String>,
): SkillLibraryViewState {
    val rows = (if (tab == SkillLibraryTab.Bundled) bundledSkills else userSkills)
        .map { it.toRowUi(subsetLabels[it.id].orEmpty()) }
    val visualState = when {
        errorMessage != null && bundledSkills.isEmpty() && userSkills.isEmpty() -> SkillLibraryVisualState.Error
        isLoading -> SkillLibraryVisualState.Loading
        tab == SkillLibraryTab.Mine && userSkills.isEmpty() -> SkillLibraryVisualState.EmptyMine
        else -> SkillLibraryVisualState.Default
    }
    val errorText = if (visualState == SkillLibraryVisualState.Error) {
        resolvedError?.takeIf { it.isNotBlank() } ?: fallbackError
    } else {
        null
    }
    return SkillLibraryViewState(
        visualState = visualState,
        tab = tab,
        bundledCount = bundledSkills.size,
        mineCount = userSkills.size,
        rows = rows,
        openMenuRowId = openMenuSkillId,
        subtitle = subtitle,
        errorMessage = errorText,
    )
}

private fun Skill.toRowUi(subsetLabel: String): SkillRowUi = SkillRowUi(
    id = id,
    name = name,
    description = description,
    toolIndicator = when (toolRestriction) {
        SkillToolRestriction.ALL -> SkillToolIndicator.All
        SkillToolRestriction.NONE -> SkillToolIndicator.None
        SkillToolRestriction.SUBSET -> SkillToolIndicator.Subset
    },
    toolCount = toolAllowlist?.size ?: 0,
    toolCountLabel = subsetLabel,
    isBundled = isBundled,
)

/** Maps the editor draft + tool catalogue onto the catalog editor view state. */
@Composable
private fun SkillLibraryUiState.toEditorUi(draft: SkillEditorDraft): SkillEditorUi = SkillEditorUi(
    id = draft.id,
    fromBundled = draft.fromBundled,
    name = draft.name,
    description = draft.description,
    instruction = draft.instruction,
    availableVariables = availableVariables,
    toolMode = draft.toolMode,
    tools = availableTools.map { tool ->
        SkillToolOptionUi(
            id = tool.id,
            risk = tool.risk,
            description = BUILT_IN_TOOL_DESCRIPTIONS[tool.id]?.let { stringResource(it) } ?: tool.description,
            selected = tool.id in draft.selectedToolIds,
        )
    },
    toolTotal = availableTools.size,
    contextFlags = draft.contextFlags(),
    nameError = false,
    instructionError = false,
    canSave = draft.canSave,
    readOnly = draft.readOnly,
)

/** Builds the five context-flag rows from the draft's [SkillEditorDraft.contextConfig]. */
@Composable
private fun SkillEditorDraft.contextFlags(): List<SkillContextFlagUi> = listOf(
    SkillContextFlagUi(
        key = KEY_CHAT,
        label = stringResource(R.string.skills_context_chat_label),
        description = stringResource(R.string.skills_context_chat_desc),
        enabled = contextConfig.chatHistory,
    ),
    SkillContextFlagUi(
        key = KEY_TASK,
        label = stringResource(R.string.skills_context_task_label),
        description = stringResource(R.string.skills_context_task_desc),
        enabled = contextConfig.originalTask,
    ),
    SkillContextFlagUi(
        key = KEY_INPUT,
        label = stringResource(R.string.skills_context_input_label),
        description = stringResource(R.string.skills_context_input_desc),
        enabled = contextConfig.nodeInput,
    ),
    SkillContextFlagUi(
        key = KEY_MEMORY,
        label = stringResource(R.string.skills_context_memory_label),
        description = stringResource(R.string.skills_context_memory_desc),
        enabled = contextConfig.longTermMemory,
    ),
    SkillContextFlagUi(
        key = KEY_TOOLS,
        label = stringResource(R.string.skills_context_tools_label),
        description = stringResource(R.string.skills_context_tools_desc),
        enabled = contextConfig.toolResults,
    ),
)

@Composable
private fun skillLibraryStrings(): SkillLibraryStrings = SkillLibraryStrings(
    title = stringResource(R.string.skills_screen_title),
    backCd = stringResource(R.string.skills_back_cd),
    searchCd = stringResource(R.string.skills_search_cd),
    moreCd = stringResource(R.string.skills_more_cd),
    fab = stringResource(R.string.skills_fab),
    tabBundled = stringResource(R.string.skills_tab_bundled),
    tabMine = stringResource(R.string.skills_tab_mine),
    bundledBadge = stringResource(R.string.skills_bundled_badge),
    toolsAll = stringResource(R.string.skills_tools_all),
    toolsNone = stringResource(R.string.skills_tools_none),
    // toolsCountFormat is left at its default — per-row labels arrive
    // pre-pluralised via SkillRowUi.toolCountLabel, so this fallback is unused.
    menuView = stringResource(R.string.skills_menu_view),
    menuEdit = stringResource(R.string.skills_menu_edit),
    menuDuplicate = stringResource(R.string.skills_menu_duplicate),
    menuDelete = stringResource(R.string.skills_menu_delete),
    emptyMineTitle = stringResource(R.string.skills_empty_mine_title),
    emptyMineBody = stringResource(R.string.skills_empty_mine_body),
    errorTitle = stringResource(R.string.skills_error_title),
    errorRetry = stringResource(R.string.common_retry),
)

@Composable
private fun skillEditorStrings(): SkillEditorStrings = SkillEditorStrings(
    titleNew = stringResource(R.string.skills_editor_title_new),
    titleEdit = stringResource(R.string.skills_editor_title_edit),
    titleView = stringResource(R.string.skills_editor_title_view),
    subtitleDraft = stringResource(R.string.skills_editor_subtitle_draft),
    subtitleMine = stringResource(R.string.skills_editor_subtitle_mine),
    subtitleDuplicated = stringResource(R.string.skills_editor_subtitle_duplicated),
    subtitleReadOnly = stringResource(R.string.skills_editor_subtitle_readonly),
    closeCd = stringResource(R.string.skills_editor_close_cd),
    save = stringResource(R.string.common_save),
    required = stringResource(R.string.skills_editor_required),
    skillKicker = stringResource(R.string.skills_editor_skill_kicker),
    skillKickerBody = stringResource(R.string.skills_editor_skill_kicker_body),
    nameLabel = stringResource(R.string.skills_editor_name_label),
    namePlaceholder = stringResource(R.string.skills_editor_name_placeholder),
    nameError = stringResource(R.string.skills_editor_name_error),
    descriptionLabel = stringResource(R.string.skills_editor_description_label),
    descriptionHint = stringResource(R.string.skills_editor_description_hint),
    descriptionPlaceholder = stringResource(R.string.skills_editor_description_placeholder),
    instructionLabel = stringResource(R.string.skills_editor_instruction_label),
    instructionPlaceholder = stringResource(R.string.skills_editor_instruction_placeholder),
    instructionError = stringResource(R.string.skills_editor_instruction_error),
    insertLabel = stringResource(R.string.skills_editor_insert_label),
    toolsLabel = stringResource(R.string.skills_editor_tools_label),
    toolModeAll = stringResource(R.string.skills_editor_tool_mode_all),
    toolModeRestrict = stringResource(R.string.skills_editor_tool_mode_restrict),
    toolModeNone = stringResource(R.string.skills_editor_tool_mode_none),
    toolsSelectedFormat = stringResource(R.string.skills_editor_tools_selected_format),
    toolsSelectHeaderFormat = stringResource(R.string.skills_editor_tools_select_header_format),
    toolsClear = stringResource(R.string.skills_editor_tools_clear),
    toolsAllTitle = stringResource(R.string.skills_editor_tools_all_title),
    toolsAllBody = stringResource(R.string.skills_editor_tools_all_body),
    toolsNoneTitle = stringResource(R.string.skills_editor_tools_none_title),
    toolsNoneBody = stringResource(R.string.skills_editor_tools_none_body),
    contextLabel = stringResource(R.string.skills_editor_context_label),
    contextOnFormat = stringResource(R.string.skills_editor_context_on_format),
    deleteSkill = stringResource(R.string.skills_editor_delete),
)

@Composable
private fun skillDeleteStrings(): SkillDeleteStrings = SkillDeleteStrings(
    titleFormat = stringResource(R.string.skills_delete_title_format),
    bodyNoDependents = stringResource(R.string.skills_delete_body_no_deps),
    bodyOneDependent = stringResource(R.string.skills_delete_body_one),
    bodyManyFormat = stringResource(R.string.skills_delete_body_many_format),
    willBreakFormat = stringResource(R.string.skills_delete_will_break_format),
    cancel = stringResource(R.string.common_cancel),
    keep = stringResource(R.string.skills_delete_keep),
    delete = stringResource(R.string.skills_delete_delete),
    deleteAnyway = stringResource(R.string.skills_delete_anyway),
)
