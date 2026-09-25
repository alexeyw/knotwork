# Directory Map: app/src/androidTest/java/app/knotwork/android

This file maps the instrumented test package — the suite the emulator matrix
runs (`.github/workflows/instrumented.yml`).

**Generated.** The tree below is rebuilt by `./gradlew :app:generateFileMap`
from the source tree itself, and `:app:verifyFileMap` fails `./gradlew check`
when the committed file no longer matches it. A *description* is yours to edit —
it is carried across regenerations by path. Entries themselves are not: adding,
removing or reordering one by hand is undone by the next run.

Only Kotlin files appear inside the generated blocks.

<!-- AUTO-GEN:FILE_MAP -->
- `AppFunctionsEndToEndTest.kt` - End-to-end coverage of the AppFunctions IPC path through the production Hilt graph.
- `data/` - Instrumented tests for the data layer.
  - `local/` - Instrumented tests for the on-device database.
    - `AppDatabaseMigrationHelperTest.kt` - Frozen-schema migration regression suite built on Room's `MigrationTestHelper`.
    - `AppDatabaseMigrationTest.kt` - Regression suite invoking every `MIGRATION_*` declared on `AppDatabase` against a real on-disk SQLite file (per-step + chained v17→v23 run).
    - `dao/` - Instrumented DAO coverage against a real Room database.
      - `ChatDaoTest.kt` - Tests for `ChatDao` — chat_messages + chat_sessions tables.
      - `LocalModelDaoTest.kt` - Tests for `LocalModelDao`.
      - `MemoryDaoTest.kt` - Tests for `MemoryDao`.
      - `PipelineDaoTest.kt` - Tests for `PipelineDao` — pipelines / pipeline_nodes / pipeline_connections + `NodeContextConfig` TypeConverter round-trip + FK cascade.
      - `PipelinePresetDaoTest.kt` - Instrumented coverage for `PipelinePresetDao`.
      - `PipelineRunDaoTest.kt` - Instrumented coverage for the retention queries of `PipelineRunDao` — the per-session window delete and the max-age delete.
      - `PromptTemplateDaoTest.kt` - Tests for `PromptTemplateDao`.
      - `TraceStepDaoTest.kt` - Tests for `TraceStepDao` — per-session ordering + FK cascade from `chat_sessions`.
    - `SqlCipherWrongKeyTest.kt` - The recovery screen's "wrong key" route, against the real SQLCipher library.
- `domain/` - Instrumented tests for the domain layer.
  - `usecases/` - Instrumented use-case coverage wired to real repositories.
    - `MemoryExtractionIntegrationTest.kt` - End-to-end integration coverage for `MemoryExtractionUseCase` wired to the real `MemoryRepositoryImpl` + `Converters` over an in-memory Room database.
    - `MemoryLifecycleIntegrationTest.kt` - End-to-end integration coverage for the **whole long-term memory lifecycle**, wiring the real domain components over an in-memory Room database: auto-extraction → embedding/storage → cross-session retrieval → injection into a node's `--- Long-Term Memory ---` context block → survival across a background compaction pass.
- `ExampleInstrumentedTest.kt` - Example instrumentation test.
- `presentation/` - Instrumented tests for the presentation layer.
  - `ui/` - Compose UI tests, one package per screen.
    - `chat/` - Compose tests for the chat surface.
      - `archive/` - Compose tests for the chat archive.
        - `ChatArchiveScreenTest.kt` - Compose happy path for the chat archive: **archive → find it in the archive → restore**, driven end to end through the real `ArchiveChatUseCase` / `UnarchiveChatUseCase` and a real `ChatArchiveViewModel` over a fake `ChatRepository` whose archive writes push back into the observed flows — so the round-trip is genuine rather than a sequence of stubbed reads.
      - `home/` - Compose tests for the chat home screen and its console pane.
        - `ChatComposerAttachmentTest.kt` - Instrumented UI tests for the chat composer's multimodal affordances: the image-attachment chip (preview / remove / processing) and the voice-input notice banners.
        - `ChatHomeClarificationFlowTest.kt` - Covers the UI-side Clarification flow at the `ChatHomeScreen` boundary.
        - `ChatHomeConsolePaneTest.kt` - Covers the Console pane plumbing as exposed through `ChatHomeScreen`: tab switching, source-filter toggling, inline-search input, Clear with confirm-dialog, and the Copy-line round-trip into the Compose `ClipboardManager`.
        - `ChatHomeDrawerTest.kt` - Covers the drawer surface plus structural composer presence in the secondary states.
        - `ChatHomeHitlScreenFlowTest.kt` - Covers the full HITL approval flow through `ChatHomeScreen`.
        - `ChatHomeOverflowMenuTest.kt` - Tests for the chat home overflow menu.
        - `ChatHomeSendFlowTest.kt` - Covers the user-prompted send flow on the chat home surface: composer value propagation, the Send icon tap firing `sendMessage()`, and the `Idle → Generating → Idle` UI transitions that follow.
        - `ChatHomeViewModelMockFactory.kt` - Mocked `ChatHomeViewModel` for screen tests: a mutable mirror of its state flow that a scenario drives through phases without re-stubbing the mock.
        - `ClarificationIntegrationTest.kt` - Tests for the Clarification HITL flow.
        - `HitlIntegrationTest.kt` - Tests for the HITL approval / deny flow.
        - `ImageViewerInstrumentedTest.kt` - Instrumented UI tests for the full-screen `ImageViewer` opened when an attachment thumbnail is tapped: header metadata, the close / share actions, and the missing-file fallback.
    - `discover/` - Compose tests for the model-discovery surface.
      - `DiscoverFlowInstrumentedTest.kt` - Instrumented UI tests for the model-discovery flow: the list surface (network error / empty degradations) and the detail surface up to and including the licence-confirmation dialog — the gate the task requires ("Discover flow up to licence confirmation").
    - `help/` - On-device checks that the bundled documentation survives packaging, and that a link between two bundled documents lands on its anchor.
      - `HelpReaderNavigationTest.kt` - Walks the path the task specifies, on a real device, against the assets the APK actually carries: open the FAQ, follow one of its internal links, and land on the named anchor of the troubleshooting guide.
    - `models/` - Compose tests for the local-models surface.
      - `PerformanceCardInstrumentedTest.kt` - Instrumented UI tests for the model **Performance card**, iterating its state matrix (empty / busy / running / result / populated) and verifying the Run-benchmark action dispatches.
    - `navigation/` - Compose tests for navigation, routes and deep links.
      - `AppShellNavigationTest.kt` - Tests for `AppShellScaffold` + nav-graph wiring.
      - `NavigationContractTest.kt` - Compose-level tests for the two navigation contracts this task established.
    - `onboarding/` - Compose tests for the onboarding flow.
      - `OnboardingScreenDownloadGateTest.kt` - Pins the LiteRT model step CTA gate.
      - `OnboardingScreenPagerTest.kt` - Covers the 4-step pager wiring on the Onboarding surface: the Welcome step renders the brand title + Continue CTA, the primary CTA on each step forwards to the matching ViewModel call, and the top-bar Skip CTA invokes `OnboardingViewModel.skipOnboarding` plus the screen's `onCompleted` lambda.
      - `OnboardingViewModelMockFactory.kt` - Mocked `OnboardingViewModel` for screen tests: a mutable mirror of its state that walks the screen through pager / download / warm-up phases.
    - `orchestrator/` - Compose tests for the pipeline-library surface.
      - `presets/` - Compose tests for the preset gallery.
        - `PresetCategoryBadgeLayoutTest.kt` - Layout guard for the preset category badge on both preset rows: asserts the badge sits entirely inside its row when the preset name is long. Instrumented rather than JVM because the same assertions under a Robolectric-hosted composition reported nonsense (a 62 dp root for a full-width row) and stayed green with every fix reverted. Measures **position**, not height — a starved badge that has been pushed past the right edge is the same bug one constraint later, and height does not see it.
    - `pipeline/` - Compose tests for the pipeline editor surface.
      - `editor/` - Compose tests for the editor screen.
        - `OrchestratorViewModelMockFactory.kt` - Mocked `OrchestratorViewModel` for editor screen tests, plus the fixtures a config sheet needs — a small palette of `AgentTool`s so `availableTools` is non-empty.
        - `PipelineEditorContentRenderTest.kt` - Surface render coverage for `PipelineEditorContent`, the pure-layout anchor of the editor screen.
        - `PipelineEditorCopyPasteTest.kt` - Covers the copy → paste round-trip from the user's point of view.
        - `PipelineEditorGestureTest.kt` - The only file driving real `performTouchInput` gestures against the editor.
        - `PipelineEditorMiniMapAndGridTest.kt` - Verifies the mini-map overlay renders the canonical OVERVIEW header (including the scale percent) and the close button dispatches `onClose`.
        - `PipelineEditorMultiSelectTest.kt` - Verifies the multi-select toolbar swap, count label, and the three actions (Cancel / Copy / Delete).
        - `PipelineEditorNodeConfigSheetTest.kt` - Verifies the catalog `NodeConfigSheet` form surfaces the per-type fields and round-trips edits + save.
        - `PipelineEditorOverflowMenuTest.kt` - Verifies the overflow `DropdownMenu` wiring on `PipelineEditorScreen`.
        - `PipelineEditorRadialMenuTest.kt` - Verifies the quick-add radial menu surfaces every catalog `NodeType` and dispatches the domain pick on tap.
        - `PipelineEditorSearchTest.kt` - Covers the search bar that overlays the canvas when the user picks "Find node…" from the overflow menu.
        - `PipelineEditorValidationBarTest.kt` - Verifies the validation bar header banner, per-error rows, the Auto-fix action, and the per-row `Go` deep-link.
    - `prompts/` - Compose tests for the prompt library.
      - `PromptLibraryScreenEditorTest.kt` - Covers the prompt editor `ModalBottomSheet` on the Prompt Library surface.
      - `PromptLibraryScreenListTest.kt` - Covers the Prompt Library list surface: prompts in the currently-selected category render with their name; the category `ScrollableTabRow` forwards selections to `PromptLibraryViewModel.selectCategory`; and the per-card Duplicate / Delete affordances + the FAB call into their matching VM hooks.
      - `PromptLibraryViewModelMockFactory.kt` - Mocked `PromptLibraryViewModel` for screen tests, driving the screen through its list and editor phases.
    - `settings/` - Compose tests for the settings screens.
      - `SettingsNavigationTest.kt` - Integration tests for the redesigned Settings navigation graph: the category hub deep-links into focused sub-screens, and the in-settings search routes a result tap to its owning category.
      - `SettingsScreenDestructiveConfirmTest.kt` - Covers the destructive typed-confirm dialog on the Settings surface.
      - `SettingsScreenRestartRequiredTest.kt` - Covers the restart-required banner on the Settings surface (`compose/screens/README.md §C5 · Settings`).
      - `SettingsScreenTogglesTest.kt` - Covers a representative toggle row on the Background category sub-screen, exercising the clickable-row wiring that connects the catalog `IconToggleRow` to its ViewModel callback through the `SettingsCallbacks` bag.
      - `SettingsViewModelMockFactory.kt` - Mocked `SettingsViewModel` for screen tests, plus a no-op `SettingsNavActions` for tests that assert no navigation.
    - `tools/` - Compose tests for the Tools surface.
      - `ToolDetailScreenTest.kt` - Tests for `ToolDetailScreen`.
      - `ToolsScreenLocalToolsTest.kt` - Covers the built-in (AppFunctions) section of the Tools screen: row names render with their per-risk pill, and tapping the row click target invokes the ViewModel's toggle hook with the inverted enabled flag.
      - `ToolsScreenMcpServersTest.kt` - Covers the MCP server section of the Tools screen: connection-status subtitle flips from `connecting…` to `ok`, the expand chevron toggles the nested tool list, and the overflow menu's Refresh action invokes the ViewModel.
      - `ToolsScreenTest.kt` - Tests for `ToolsScreen`.
      - `ToolsViewModelMockFactory.kt` - Mocked `ToolsViewModel` for screen tests, plus `AgentTool` / MCP snapshot samples built per risk classification.
    - `triggers/` - Compose tests for the triggers surface.
      - `TriggersScreenTest.kt` - Compose happy-path for the Triggers surface: starting from the empty state, create a trigger, bind a pipeline, leave it enabled, save, and confirm the new trigger appears in the list.
- `testing/` - The `@DeviceOnlyInstrumentedTest` annotation, which names an instrumented test that cannot produce a meaningful verdict on an emulator.
  - `DeviceOnlyInstrumentedTest.kt` - Marks an instrumented test that **cannot produce a meaningful verdict on an emulator** and is therefore excluded from every automated CI run by name.
<!-- /AUTO-GEN:FILE_MAP -->
