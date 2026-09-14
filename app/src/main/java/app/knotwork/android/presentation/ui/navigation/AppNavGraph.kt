package app.knotwork.android.presentation.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import app.knotwork.android.domain.models.ProviderId
import app.knotwork.android.presentation.state.ChatEntryRequest
import app.knotwork.android.presentation.state.ChatEntryRequestRelay
import app.knotwork.android.presentation.ui.about.AboutScreen
import app.knotwork.android.presentation.ui.automation.ExternalAutomationJournalScreen
import app.knotwork.android.presentation.ui.chat.archive.ChatArchiveScreen
import app.knotwork.android.presentation.ui.chat.home.ChatHomeScreen
import app.knotwork.android.presentation.ui.chat.home.ChatHomeViewModel
import app.knotwork.android.presentation.ui.discover.DiscoverDetailScreen
import app.knotwork.android.presentation.ui.discover.DiscoverScreen
import app.knotwork.android.presentation.ui.files.FilesScreen
import app.knotwork.android.presentation.ui.help.HelpReaderScreen
import app.knotwork.android.presentation.ui.help.HelpScreen
import app.knotwork.android.presentation.ui.memory.MemoryScreen
import app.knotwork.android.presentation.ui.models.ModelsScreen
import app.knotwork.android.presentation.ui.monitoring.MonitoringScreen
import app.knotwork.android.presentation.ui.monitoring.MonitoringViewModel
import app.knotwork.android.presentation.ui.more.MoreScreen
import app.knotwork.android.presentation.ui.onboarding.OnboardingScreen
import app.knotwork.android.presentation.ui.orchestrator.OrchestratorViewModel
import app.knotwork.android.presentation.ui.orchestrator.PipelineLibraryScreen
import app.knotwork.android.presentation.ui.orchestrator.presets.PipelinePresetsManagerScreen
import app.knotwork.android.presentation.ui.pipeline.editor.PipelineEditorScreen
import app.knotwork.android.presentation.ui.prompts.PromptLibraryScreen
import app.knotwork.android.presentation.ui.settings.AboutSettingsScreen
import app.knotwork.android.presentation.ui.settings.BackgroundSettingsScreen
import app.knotwork.android.presentation.ui.settings.GenerationSettingsScreen
import app.knotwork.android.presentation.ui.settings.MemorySettingsScreen
import app.knotwork.android.presentation.ui.settings.ModelsSettingsScreen
import app.knotwork.android.presentation.ui.settings.PipelinesSettingsScreen
import app.knotwork.android.presentation.ui.settings.PrivacySettingsScreen
import app.knotwork.android.presentation.ui.settings.SettingsHubScreen
import app.knotwork.android.presentation.ui.settings.SettingsNavActions
import app.knotwork.android.presentation.ui.settings.SettingsViewModel
import app.knotwork.android.presentation.ui.settings.ToolsSettingsScreen
import app.knotwork.android.presentation.ui.settings.provider.ProviderDetailScreen
import app.knotwork.android.presentation.ui.settings.provider.ProviderPickerScreen
import app.knotwork.android.presentation.ui.settings.runlimits.RunLimitsScreen
import app.knotwork.android.presentation.ui.settings.usage.UsageTelemetryScreen
import app.knotwork.android.presentation.ui.skills.SkillLibraryScreen
import app.knotwork.android.presentation.ui.splash.SplashScreen
import app.knotwork.android.presentation.ui.taskmonitor.TaskMonitorScreen
import app.knotwork.android.presentation.ui.taskmonitor.TaskMonitorViewModel
import app.knotwork.android.presentation.ui.tools.AllowedDomainsScreen
import app.knotwork.android.presentation.ui.tools.McpServerConfigScreen
import app.knotwork.android.presentation.ui.tools.ToolDetailScreen
import app.knotwork.android.presentation.ui.tools.ToolsScreen
import app.knotwork.android.presentation.ui.triggers.TriggersScreen
import app.knotwork.design.screens.settings.SettingsCategoryId

/**
 * The single [NavHost] for the whole app.
 *
 * Navigation map:
 *  - Splash and Onboarding are top-level "shell-less" destinations — the
 *    bottom-nav is hidden for them (see [shouldShowBottomNav]). After
 *    onboarding completes, the user lands on the Chat tab.
 *  - The four bottom-nav tabs (Chat / Pipelines / Tools / More) each own a
 *    `composable(...)` start-destination; deeper screens (`memory`,
 *    `settings`, `tools/{id}`, `pipeline/{id}/edit`, ...) live as
 *    additional entries reachable from inside a tab.
 *  - The Pipelines tab is a nested `navigation { ... }` graph so its
 *    library and editor share a single [OrchestratorViewModel] scoped to
 *    the graph entry — exactly as before this task, just with a different
 *    parent route.
 *
 * **Tab roots are entered only through [navigateToTab].** Pushing a tab root on
 * top of another subtree's stack strands the user on a tab they did not choose
 * and moves the bottom-nav highlight there with them (closed-test finding
 * `#14`); a screen that wants to be reachable from two subtrees gets a second
 * route instead — see [NavRoutes.SETTINGS_TOOLS_MANAGE] and [ToolsRoute]. The
 * rule is enforced by `TabRootEntryGuardTest`, not by review.
 *
 * Modal bottom-sheet routes (`sheet/...`) are registered with the shared
 * [KnotworkModalRoute] wrapper; their bodies arrive in Tasks 6/7/10.
 *
 * @param navController Activity-owned controller observed by the parent
 *        [AppShellScaffold] for bottom-nav visibility / highlight.
 * @param showOnboarding Read once at composition. When `true`, the splash
 *        completion handler routes to onboarding; otherwise it goes to
 *        Chat. Sourced from `SettingsRepository.hasCompletedOnboarding`
 *        (inverted) — a flag that survives `InitializeAppUseCase` and so
 *        is the right gate for the UI surface, unlike `isFirstLaunch`
 *        which is cleared during cold-start init.
 * @param pendingDeepLink the `knotwork://` deep-link [Intent] the host activity
 *        was launched with (launcher shortcut, share target, notification tap),
 *        or `null` for a normal launch. The deep link is **not** auto-handled by
 *        the NavHost (the `navDeepLink` registrations were removed) — instead the
 *        splash handler builds the back stack deterministically: splash →
 *        [NavRoutes.CHAT_TAB] (home) → the deep-link target on top. This avoids
 *        the implicit-deep-link race that buried the target under the home chat
 *        (or produced a half-loaded second chat surface).
 * @param chatEntryRequestRelay bus connecting `knotwork://chat…` / `new-chat`
 *        entry surfaces to the single chat home: the deep-link handler posts an
 *        open-thread / new-chat request and the `CHAT_TAB` composable drains it
 *        into `selectThread` / `createNewSessionWithPipeline`.
 * @param modifier Inset-padding passthrough from [AppShellScaffold].
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    showOnboarding: Boolean,
    pendingDeepLink: Intent?,
    chatEntryRequestRelay: ChatEntryRequestRelay,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.SPLASH,
        modifier = modifier.fillMaxSize(),
    ) {
        composable(NavRoutes.SPLASH) {
            SplashScreen(
                onInitialized = {
                    val next = if (showOnboarding) NavRoutes.ONBOARDING else NavRoutes.CHAT_TAB
                    navController.navigate(next) {
                        popUpTo(NavRoutes.SPLASH) { inclusive = true }
                        launchSingleTop = true
                    }
                    // A launch deep link is applied AFTER landing on the chat home,
                    // so the back stack is deterministically [CHAT_TAB, target] —
                    // the target on top (Back returns home), never a half-loaded
                    // chat buried under the home surface. Skipped during onboarding;
                    // a first-run user has no sessions to deep-link into yet.
                    if (!showOnboarding) {
                        pendingDeepLink?.let { navController.navigateToDeepLink(it, chatEntryRequestRelay) }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        composable(NavRoutes.ONBOARDING) {
            OnboardingScreen(
                onCompleted = {
                    navController.navigate(NavRoutes.CHAT_TAB) {
                        popUpTo(NavRoutes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                // Pushed over onboarding, so back from the reader returns to the step
                // the user left, with its state intact.
                onOpenDocument = { id -> navController.navigate(NavRoutes.helpDocumentRoute(id)) },
            )
        }

        // ─── Chat tab — the single chat home ───────────────────────────────
        // There is ONE chat destination: the chat *is* the home. A deep link
        // (knotwork://chat, knotwork://chat/{id}, knotwork://new-chat) never adds
        // a second chat entry — it lands here and the session change is posted
        // through `chatEntryRequestRelay`, so Back from the chat always closes the
        // app, exactly like a normal launch. Deep links are NOT registered as
        // `navDeepLink`s (auto-handling raced the splash and produced a doubled /
        // blank chat); MainActivity routes them via DeepLinkRouter.
        composable(route = NavRoutes.CHAT_TAB) {
            val chatHomeViewModel: ChatHomeViewModel = hiltViewModel()
            // Drain entry requests into the one chat home. A request may be
            // buffered (CONFLATED) from before this collector mounted on a cold
            // launch, so it is delivered as soon as we land here.
            LaunchedEffect(chatHomeViewModel) {
                chatEntryRequestRelay.requests.collect { request ->
                    when (request) {
                        ChatEntryRequest.NewChat ->
                            chatHomeViewModel.threads.createNewSessionWithPipeline(pipelineId = null)
                        is ChatEntryRequest.OpenThread ->
                            chatHomeViewModel.selectThread(request.threadId)
                    }
                }
            }
            ChatHomeScreen(
                viewModel = chatHomeViewModel,
                onOpenSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onOpenModels = { navController.navigate(NavRoutes.MODELS) },
                onOpenArchive = { navController.navigate(NavRoutes.CHAT_ARCHIVE) },
                onOpenRunLimits = { navController.navigate(NavRoutes.SETTINGS_PIPELINES_RUN_LIMITS) },
            )
        }

        // ─── Pipelines tab (nested graph) ──────────────────────────────────
        navigation(
            startDestination = NavRoutes.PIPELINE_LIBRARY,
            route = NavRoutes.PIPELINES_GRAPH,
        ) {
            composable(route = NavRoutes.PIPELINE_LIBRARY) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(NavRoutes.PIPELINES_GRAPH)
                }
                val orchestratorViewModel: OrchestratorViewModel = hiltViewModel(parentEntry)
                PipelineLibraryScreen(
                    viewModel = orchestratorViewModel,
                    onOpenEditor = {
                        navController.navigate(NavRoutes.PIPELINE_EDITOR) {
                            launchSingleTop = true
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(NavRoutes.PIPELINE_EDITOR) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(NavRoutes.PIPELINES_GRAPH)
                }
                val orchestratorViewModel: OrchestratorViewModel = hiltViewModel(parentEntry)
                PipelineEditorScreen(
                    viewModel = orchestratorViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = NavRoutes.PIPELINE_EDIT_WITH_ID,
                arguments = listOf(
                    navArgument(NavRoutes.PIPELINE_EDIT_ID_ARG) {
                        type = NavType.StringType
                        nullable = false
                    },
                ),
            ) { entry ->
                val parentEntry = remember(entry) {
                    navController.getBackStackEntry(NavRoutes.PIPELINES_GRAPH)
                }
                val orchestratorViewModel: OrchestratorViewModel = hiltViewModel(parentEntry)
                val pipelineId = entry.arguments?.getString(NavRoutes.PIPELINE_EDIT_ID_ARG)
                LaunchedEffect(pipelineId) {
                    if (!pipelineId.isNullOrBlank()) orchestratorViewModel.loadPipeline(pipelineId)
                }
                PipelineEditorScreen(
                    viewModel = orchestratorViewModel,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // ─── Tools tab ─────────────────────────────────────────────────────
        composable(NavRoutes.TOOLS) { ToolsRoute(navController = navController) }
        composable(NavRoutes.ALLOWED_DOMAINS) {
            AllowedDomainsScreen(
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = NavRoutes.TOOL_DETAIL,
            arguments = listOf(
                navArgument(NavRoutes.TOOL_DETAIL_ID_ARG) {
                    type = NavType.StringType
                    nullable = false
                },
            ),
        ) { backStackEntry ->
            val toolId = backStackEntry.arguments?.getString(NavRoutes.TOOL_DETAIL_ID_ARG).orEmpty()
            ToolDetailScreen(
                toolId = toolId,
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = NavRoutes.MCP_SERVER_CONFIG,
            arguments = listOf(
                navArgument(NavRoutes.MCP_SERVER_CONFIG_URL_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            McpServerConfigScreen(
                onDone = { navController.popBackStack() },
                onCancel = { navController.popBackStack() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // ─── More tab and its secondary screens ────────────────────────────
        composable(NavRoutes.MORE) {
            MoreScreen(
                onNavigateToMemory = { navController.navigate(NavRoutes.MEMORY) },
                onNavigateToModels = { navController.navigate(NavRoutes.MODELS) },
                onNavigateToMonitoring = { navController.navigate(NavRoutes.MONITORING) },
                onNavigateToTaskMonitor = { navController.navigate(NavRoutes.TASK_MONITOR) },
                onNavigateToSettings = { navController.navigate(NavRoutes.SETTINGS) },
                onNavigateToPrompts = { navController.navigate(NavRoutes.PROMPTS) },
                onNavigateToSkills = { navController.navigate(NavRoutes.SKILLS) },
                onNavigateToTriggers = { navController.navigate(NavRoutes.TRIGGERS) },
                onNavigateToAbout = { navController.navigate(NavRoutes.ABOUT) },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
                onNavigateToLibrary = { navController.navigate(NavRoutes.PIPELINE_PRESETS) },
                onNavigateToFiles = { navController.navigate(NavRoutes.FILES) },
                onNavigateToChatArchive = { navController.navigate(NavRoutes.CHAT_ARCHIVE) },
            )
        }
        composable(NavRoutes.PIPELINE_PRESETS) {
            PipelinePresetsManagerScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.MEMORY) {
            MemoryScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.FILES) {
            FilesScreen(onBack = { navController.popBackStack() })
        }
        composable(NavRoutes.MODELS) {
            ModelsScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
                onOpenDiscover = { navController.navigate(NavRoutes.DISCOVER) },
            )
        }
        composable(NavRoutes.DISCOVER) {
            DiscoverScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
                onOpenModel = { repoId -> navController.navigate(NavRoutes.discoverDetailRoute(repoId)) },
            )
        }
        composable(
            route = NavRoutes.DISCOVER_DETAIL,
            arguments = listOf(
                navArgument(NavRoutes.DISCOVER_DETAIL_REPO_ID_ARG) {
                    type = NavType.StringType
                    nullable = false
                    defaultValue = ""
                },
            ),
        ) { entry ->
            val repoId = entry.arguments?.getString(NavRoutes.DISCOVER_DETAIL_REPO_ID_ARG).orEmpty()
            DiscoverDetailScreen(
                repoId = repoId,
                onBack = { navController.popBackStack() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(NavRoutes.MONITORING) {
            val monitoringViewModel: MonitoringViewModel = hiltViewModel()
            MonitoringScreen(
                viewModel = monitoringViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.TASK_MONITOR) {
            val taskMonitorViewModel: TaskMonitorViewModel = hiltViewModel()
            TaskMonitorScreen(
                viewModel = taskMonitorViewModel,
                onNavigateToChat = { _ -> navController.navigateToTab(NavRoutes.CHAT_TAB) },
                onBack = { navController.popBackStack() },
            )
        }
        navigation(startDestination = NavRoutes.SETTINGS_HUB, route = NavRoutes.SETTINGS) {
            val nav = settingsNavActions(navController)
            composable(NavRoutes.SETTINGS_HUB) { entry ->
                SettingsHubScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            composable(NavRoutes.SETTINGS_GENERATION) { entry ->
                GenerationSettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            composable(NavRoutes.SETTINGS_MODELS) { entry ->
                ModelsSettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            composable(NavRoutes.SETTINGS_MEMORY) { entry ->
                MemorySettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            composable(NavRoutes.SETTINGS_PIPELINES) { entry ->
                PipelinesSettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            composable(NavRoutes.SETTINGS_TOOLS) { entry ->
                ToolsSettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            // Tool / MCP management as a settings-owned destination. See
            // `NavRoutes.SETTINGS_TOOLS_MANAGE`: pushing the surface here keeps
            // the interaction inside settings, where linking to the Tools *tab*
            // dropped the user onto a tab they had not chosen (closed-test
            // finding `#14`).
            composable(NavRoutes.SETTINGS_TOOLS_MANAGE) { ToolsRoute(navController = navController) }
            composable(NavRoutes.SETTINGS_BACKGROUND) { entry ->
                BackgroundSettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            composable(NavRoutes.SETTINGS_BACKGROUND_EXTERNAL_AUTOMATION) {
                ExternalAutomationJournalScreen(onBack = { navController.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_PIPELINES_RUN_LIMITS) {
                RunLimitsScreen(viewModel = hiltViewModel(), onBack = { navController.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_PRIVACY) { entry ->
                PrivacySettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
            composable(NavRoutes.SETTINGS_PRIVACY_USAGE) {
                UsageTelemetryScreen(onBack = { navController.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_ABOUT) { entry ->
                AboutSettingsScreen(viewModel = settingsGraphViewModel(navController, entry), nav = nav)
            }
        }
        composable(
            route = NavRoutes.PROVIDER_DETAIL,
            arguments = listOf(
                navArgument(NavRoutes.PROVIDER_DETAIL_ID_ARG) {
                    type = NavType.StringType
                    nullable = false
                },
            ),
        ) { entry ->
            val wireId = entry.arguments?.getString(NavRoutes.PROVIDER_DETAIL_ID_ARG).orEmpty()
            val providerId = ProviderId.entries.firstOrNull { it.cloudProvider.id == wireId }
                ?: ProviderId.OpenAi
            ProviderDetailScreen(
                providerId = providerId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.ADD_PROVIDER) {
            // v0.1: Add provider goes through the same picker as the
            // detail screen — surface a minimal list-style picker that
            // forwards to the per-provider detail route. The richer
            // bottom-sheet picker is a follow-up.
            ProviderPickerScreen(
                onPick = { providerId ->
                    val wireId = providerId.cloudProvider.id
                    navController.navigate(
                        NavRoutes.PROVIDER_DETAIL.replace(
                            oldValue = "{${NavRoutes.PROVIDER_DETAIL_ID_ARG}}",
                            newValue = wireId,
                        ),
                    ) {
                        popUpTo(NavRoutes.SETTINGS)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.PROMPTS) {
            PromptLibraryScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.SKILLS) {
            SkillLibraryScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.CHAT_ARCHIVE) {
            ChatArchiveScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
                // Opening an archived chat routes into the *single* chat home
                // through the same relay every other entry surface uses, so no
                // second chat destination is ever pushed. The chat renders
                // read-only from the session's own archive flag — opening never
                // un-archives.
                onOpenChat = { sessionId ->
                    chatEntryRequestRelay.openThread(sessionId)
                    // Same mechanism as closed-test finding `#14`, second
                    // instance: a bare `navigate(CHAT_TAB)` pushed the Chat tab
                    // root on top of the More subtree, leaving the archive and
                    // the whole More stack buried under the chat.
                    navController.navigateToTab(NavRoutes.CHAT_TAB)
                },
            )
        }
        composable(NavRoutes.TRIGGERS) {
            TriggersScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(NavRoutes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onNavigateToHelp = { navController.navigate(NavRoutes.HELP) },
            )
        }

        composable(NavRoutes.HELP) {
            HelpScreen(
                onOpenDocument = { id -> navController.navigate(NavRoutes.helpDocumentRoute(id)) },
            )
        }

        composable(
            route = NavRoutes.HELP_DOCUMENT,
            arguments = listOf(
                navArgument(NavRoutes.HELP_DOCUMENT_ID_ARG) { type = NavType.StringType },
                navArgument(NavRoutes.HELP_DOCUMENT_ANCHOR_ARG) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            HelpReaderScreen(
                // A link from one document to another pushes rather than
                // replaces, so back returns to the document the reader left —
                // which is what a reader following a cross-reference expects.
                onOpenDocument = { id, anchor ->
                    navController.navigate(NavRoutes.helpDocumentRoute(id, anchor))
                },
                onBack = { navController.popBackStack() },
            )
        }

        // ─── Modal bottom-sheet placeholders (Tasks 6 / 7) ─────────────────
        composable(NavRoutes.SHEET_NODE_CONFIG) {
            KnotworkModalRoute(onDismiss = { navController.popBackStack() }) { _ -> }
        }
        composable(NavRoutes.SHEET_CONSOLE) {
            KnotworkModalRoute(onDismiss = { navController.popBackStack() }) { _ -> }
        }
    }
}

/**
 * The tool / MCP management surface, registered at two routes.
 *
 * [NavRoutes.TOOLS] is the Tools tab root; [NavRoutes.SETTINGS_TOOLS_MANAGE] is
 * the settings-owned twin reached from `Settings → Tools & workspace → Manage
 * tools`. Both render the identical screen and push the identical detail /
 * form / allowed-domains destinations on top of whichever stack they are on, so
 * Back always returns to the surface the user actually came from.
 *
 * Hoisting the wiring here rather than duplicating it at both registrations
 * keeps the two entries from drifting apart — a divergence would show up as
 * "the same screen behaves differently depending on how you got here", which is
 * the class of confusion this task exists to remove.
 *
 * @param navController controller the nested destinations are pushed onto.
 */
@Composable
private fun ToolsRoute(navController: NavHostController) {
    ToolsScreen(
        modifier = Modifier.fillMaxSize(),
        onBack = { navController.popBackStack() },
        onAddMcpServer = { navController.navigate(NavRoutes.MCP_SERVER_CONFIG_ADD) },
        onEditMcpServer = { originalUrl ->
            navController.navigate(NavRoutes.mcpServerConfigEditRoute(originalUrl = originalUrl))
        },
        onOpenToolDetail = { toolId ->
            // AppFunction-shaped tool ids embed `/` and `#` (e.g.
            // `<pkg>/<FQN>#invoke`). Percent-encode them via `Uri.encode` so
            // they fit a single `{toolId}` segment; Navigation's internal
            // `Uri.decode` is the inverse, so the receiver gets the raw id back.
            val encoded = android.net.Uri.encode(toolId)
            navController.navigate(NavRoutes.TOOL_DETAIL.replace(oldValue = "{toolId}", newValue = encoded))
        },
        onOpenAllowedDomains = { navController.navigate(NavRoutes.ALLOWED_DOMAINS) },
    )
}

/**
 * Resolves the shared [SettingsViewModel] scoped to the settings navigation
 * graph so the hub and every category sub-screen observe the same instance (the
 * Pipelines-graph pattern).
 */
@Composable
private fun settingsGraphViewModel(navController: NavHostController, entry: NavBackStackEntry): SettingsViewModel {
    val parentEntry = remember(entry) { navController.getBackStackEntry(NavRoutes.SETTINGS) }
    return hiltViewModel(parentEntry)
}

/** Builds the settings navigation actions over [navController]. */
private fun settingsNavActions(navController: NavHostController): SettingsNavActions = SettingsNavActions(
    onBack = { navController.popBackStack() },
    onOpenCategory = { category -> navController.navigate(settingsCategoryRoute(category)) },
    onOpenModels = { navController.navigate(NavRoutes.MODELS) },
    onOpenProvider = { providerId ->
        navController.navigate(
            NavRoutes.PROVIDER_DETAIL.replace(
                oldValue = "{${NavRoutes.PROVIDER_DETAIL_ID_ARG}}",
                newValue = providerId.cloudProvider.id,
            ),
        )
    },
    onOpenAddProvider = { navController.navigate(NavRoutes.ADD_PROVIDER) },
    onOpenManageTools = { navController.navigate(NavRoutes.SETTINGS_TOOLS_MANAGE) },
    onOpenAllowedDomains = { navController.navigate(NavRoutes.ALLOWED_DOMAINS) },
    onOpenLicenses = { navController.navigate(NavRoutes.ABOUT) },
    onOpenUsageStatistics = { navController.navigate(NavRoutes.SETTINGS_PRIVACY_USAGE) },
    onOpenExternalAutomationJournal = {
        navController.navigate(NavRoutes.SETTINGS_BACKGROUND_EXTERNAL_AUTOMATION)
    },
    onOpenRunLimits = { navController.navigate(NavRoutes.SETTINGS_PIPELINES_RUN_LIMITS) },
)

/** Maps a settings category id to its sub-screen route. */
private fun settingsCategoryRoute(category: SettingsCategoryId): String = when (category) {
    SettingsCategoryId.Generation -> NavRoutes.SETTINGS_GENERATION
    SettingsCategoryId.Models -> NavRoutes.SETTINGS_MODELS
    SettingsCategoryId.Memory -> NavRoutes.SETTINGS_MEMORY
    SettingsCategoryId.Pipelines -> NavRoutes.SETTINGS_PIPELINES
    SettingsCategoryId.Tools -> NavRoutes.SETTINGS_TOOLS
    SettingsCategoryId.Background -> NavRoutes.SETTINGS_BACKGROUND
    SettingsCategoryId.Privacy -> NavRoutes.SETTINGS_PRIVACY
    SettingsCategoryId.About -> NavRoutes.SETTINGS_ABOUT
}
