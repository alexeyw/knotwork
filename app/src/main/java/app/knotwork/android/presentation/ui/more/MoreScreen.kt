package app.knotwork.android.presentation.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.knotwork.android.R
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.screens.more.MoreContent
import app.knotwork.design.screens.more.MoreRow
import app.knotwork.design.screens.more.MoreSection
import app.knotwork.design.screens.more.MoreStrings
import app.knotwork.design.screens.more.MoreViewState

/**
 * Landing screen of the "More" bottom-nav tab. Renders the Knotwork
 * [MoreContent] surface with the secondary-destination navigation rows, live
 * counters, and a footer status pill summarising recent outbound network
 * activity.
 */
@Composable
fun MoreScreen(
    onNavigateToMemory: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToMonitoring: () -> Unit,
    onNavigateToTaskMonitor: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPrompts: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToTriggers: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToHelp: () -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToChatArchive: () -> Unit,
    viewModel: MoreViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val state = uiState.toViewState(
        titleSectionAutomation = stringResource(R.string.more_section_automation),
        titleSectionContent = stringResource(R.string.more_section_content),
        titleSectionBlocks = stringResource(R.string.more_section_blocks),
        titleSectionApp = stringResource(R.string.more_section_app),
        titleMemory = stringResource(R.string.more_row_memory),
        titleModels = stringResource(R.string.more_row_models),
        titlePrompts = stringResource(R.string.more_row_prompts),
        titleSkills = stringResource(R.string.more_skills_title),
        subtitleSkills = stringResource(R.string.more_skills_subtitle),
        titleTriggers = stringResource(R.string.more_row_triggers),
        subtitleTriggers = stringResource(R.string.more_triggers_subtitle),
        titleTasks = stringResource(R.string.more_row_task_monitor),
        titleMetrics = stringResource(R.string.more_row_monitoring),
        titleSettings = stringResource(R.string.more_row_settings),
        titleAbout = stringResource(R.string.more_row_about),
        titleHelp = stringResource(R.string.more_row_help),
        helpSubtitle = stringResource(R.string.more_row_help_sub),
        titleLibrary = stringResource(R.string.more_row_library),
        titleFiles = stringResource(R.string.more_row_files),
        titleArchive = stringResource(R.string.more_row_chat_archive),
        subtitleArchive = if (uiState.archivedChats == 0) {
            stringResource(R.string.more_chat_archive_subtitle_empty)
        } else {
            pluralStringResource(
                R.plurals.more_chat_archive_subtitle,
                uiState.archivedChats,
                uiState.archivedChats,
            )
        },
        onMemory = onNavigateToMemory,
        onModels = onNavigateToModels,
        onPrompts = onNavigateToPrompts,
        onSkills = onNavigateToSkills,
        onTriggers = onNavigateToTriggers,
        onTasks = onNavigateToTaskMonitor,
        onMetrics = onNavigateToMonitoring,
        onSettings = onNavigateToSettings,
        onAbout = onNavigateToAbout,
        onHelp = onNavigateToHelp,
        onLibrary = onNavigateToLibrary,
        onFiles = onNavigateToFiles,
        onArchive = onNavigateToChatArchive,
    )
    MoreContent(
        state = state,
        modifier = Modifier.testTag(MORE_ROOT_TEST_TAG),
        strings = MoreStrings(
            title = stringResource(R.string.nav_tab_more),
            subtitle = stringResource(R.string.more_subtitle),
        ),
    )
}

/** Build the catalog view state from the live UI state + localized labels. */
@Suppress("LongParameterList") // Mapper bundles localised strings + navigation lambdas.
internal fun MoreUiState.toViewState(
    titleSectionAutomation: String,
    titleSectionContent: String,
    titleSectionBlocks: String,
    titleSectionApp: String,
    titleMemory: String,
    titleModels: String,
    titlePrompts: String,
    titleSkills: String,
    subtitleSkills: String,
    titleTriggers: String,
    subtitleTriggers: String,
    titleTasks: String,
    titleMetrics: String,
    titleSettings: String,
    titleAbout: String,
    titleHelp: String,
    helpSubtitle: String,
    titleLibrary: String,
    titleFiles: String,
    titleArchive: String,
    subtitleArchive: String,
    onMemory: () -> Unit,
    onModels: () -> Unit,
    onPrompts: () -> Unit,
    onSkills: () -> Unit,
    onTriggers: () -> Unit,
    onTasks: () -> Unit,
    onMetrics: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
    onLibrary: () -> Unit,
    onFiles: () -> Unit,
    onArchive: () -> Unit,
): MoreViewState = MoreViewState(
    // Four named sections of three, in the order "why you opened More, most
    // often first". Triggers moves from the seventh row to the first: a closed
    // test found it after two hours of looking. `Diagnostics` is deliberately
    // not a section — only Live metrics would live in it, and a section of one
    // is a heading pretending to be structure, so it joins App.
    sections = listOf(
        MoreSection(
            id = "automation",
            title = titleSectionAutomation,
            rows = listOf(
                MoreRow(
                    id = "triggers",
                    title = titleTriggers,
                    subtitle = subtitleTriggers,
                    icon = AppIcons.Trigger,
                    onClick = onTriggers,
                ),
                MoreRow(
                    id = "library",
                    title = titleLibrary,
                    subtitle = librarySubtitle,
                    icon = AppIcons.Bookmark,
                    onClick = onLibrary,
                ),
                MoreRow(
                    id = "tasks",
                    title = titleTasks,
                    subtitle = tasksSubtitle,
                    icon = AppIcons.History,
                    badge = tasksBadge,
                    onClick = onTasks,
                ),
            ),
        ),
        MoreSection(
            id = "content",
            title = titleSectionContent,
            rows = listOf(
                MoreRow(
                    id = "memory",
                    title = titleMemory,
                    subtitle = memorySubtitle,
                    icon = AppIcons.Brain,
                    onClick = onMemory,
                ),
                MoreRow(
                    id = "files",
                    title = titleFiles,
                    subtitle = filesSubtitle,
                    icon = AppIcons.Folder,
                    onClick = onFiles,
                ),
                // Always shown, even at zero, because the drawer's own entry is
                // hidden then and the feature must stay discoverable. The count
                // lives in the subtitle, never a badge.
                MoreRow(
                    id = "archive",
                    title = titleArchive,
                    subtitle = subtitleArchive,
                    icon = AppIcons.Archive,
                    onClick = onArchive,
                ),
            ),
        ),
        MoreSection(
            id = "blocks",
            title = titleSectionBlocks,
            rows = listOf(
                MoreRow(
                    id = "prompts",
                    title = titlePrompts,
                    subtitle = promptsSubtitle,
                    icon = AppIcons.Sliders,
                    onClick = onPrompts,
                ),
                MoreRow(
                    id = "skills",
                    title = titleSkills,
                    subtitle = subtitleSkills,
                    icon = AppIcons.Skill,
                    onClick = onSkills,
                ),
                // Models is not content: you do not author it, you install it.
                MoreRow(
                    id = "models",
                    title = titleModels,
                    subtitle = modelsSubtitle,
                    icon = AppIcons.Ram,
                    onClick = onModels,
                ),
            ),
        ),
        appSection(
            title = titleSectionApp,
            titleSettings = titleSettings,
            settingsSubtitle = settingsSubtitle,
            titleHelp = titleHelp,
            helpSubtitle = helpSubtitle,
            titleMetrics = titleMetrics,
            metricsSubtitle = metricsSubtitle,
            titleAbout = titleAbout,
            aboutSubtitle = aboutSubtitle,
            onSettings = onSettings,
            onHelp = onHelp,
            onMetrics = onMetrics,
            onAbout = onAbout,
        ),
    ),
    networkStatus = networkStatusText.takeIf { it.isNotEmpty() },
    networkStatusOk = networkStatusOk,
)

/** Stable test tag for the More screen root — used by instrumented tests. */
const val MORE_ROOT_TEST_TAG: String = "more_root"

/**
 * The `App` section of the More tab.
 *
 * Extracted from [toViewState] because that builder grew past the length the
 * static-analysis gate allows when Help joined the section — and a section is
 * the natural seam: each one is a list of rows that share a heading.
 *
 * @param title The section heading.
 * @param titleSettings Settings row label.
 * @param settingsSubtitle Settings row mono subtitle.
 * @param titleHelp Help row label.
 * @param helpSubtitle Help row mono subtitle.
 * @param titleMetrics Live-metrics row label.
 * @param metricsSubtitle Live-metrics row mono subtitle.
 * @param titleAbout About row label.
 * @param aboutSubtitle About row mono subtitle.
 * @param onSettings Settings row click.
 * @param onHelp Help row click.
 * @param onMetrics Live-metrics row click.
 * @param onAbout About row click.
 * @return The section.
 */
@Suppress("LongParameterList") // One parameter per row label, subtitle and action.
private fun appSection(
    title: String,
    titleSettings: String,
    settingsSubtitle: String,
    titleHelp: String,
    helpSubtitle: String,
    titleMetrics: String,
    metricsSubtitle: String,
    titleAbout: String,
    aboutSubtitle: String,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onMetrics: () -> Unit,
    onAbout: () -> Unit,
): MoreSection = MoreSection(
    id = "app",
    title = title,
    rows = listOf(
        MoreRow(
            id = "settings",
            title = titleSettings,
            subtitle = settingsSubtitle,
            icon = AppIcons.Cog,
            onClick = onSettings,
        ),
        // Documentation is not "about the app", but it is *of* it, and `App` is
        // the section an Android user already reaches for. About keeps the last
        // position because it is the terminus: version, licences, reset.
        MoreRow(
            id = "help",
            title = titleHelp,
            subtitle = helpSubtitle,
            icon = AppIcons.Book,
            onClick = onHelp,
        ),
        MoreRow(
            id = "metrics",
            title = titleMetrics,
            subtitle = metricsSubtitle,
            icon = AppIcons.Bolt,
            onClick = onMetrics,
        ),
        MoreRow(
            id = "about",
            title = titleAbout,
            subtitle = aboutSubtitle,
            icon = AppIcons.Spark,
            onClick = onAbout,
        ),
    ),
)
