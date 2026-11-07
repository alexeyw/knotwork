package app.knotwork.android.presentation.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.models.AgentMetrics
import app.knotwork.android.domain.models.Identity
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.ModelPerformanceSummary
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.services.ScheduledTaskKind
import app.knotwork.android.domain.services.ScheduledTaskLabel
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.help.HelpUiState
import app.knotwork.android.presentation.ui.help.helpListViewState
import app.knotwork.android.presentation.ui.help.helpStrings
import app.knotwork.android.presentation.ui.memory.MemoryScreenContent
import app.knotwork.android.presentation.ui.memory.MemoryUiState
import app.knotwork.android.presentation.ui.models.ModelsScreenContent
import app.knotwork.android.presentation.ui.models.ModelsUiState
import app.knotwork.android.presentation.ui.monitoring.MonitoringScreenContent
import app.knotwork.android.presentation.ui.monitoring.MonitoringUiState
import app.knotwork.android.presentation.ui.settings.SettingsHelpCatalog
import app.knotwork.android.presentation.ui.settings.SettingsUiState
import app.knotwork.android.presentation.ui.settings.buildAboutViewState
import app.knotwork.android.presentation.ui.settings.buildGenerationViewState
import app.knotwork.android.presentation.ui.settings.buildHubViewState
import app.knotwork.android.presentation.ui.settings.buildToolsViewState
import app.knotwork.android.presentation.ui.settings.runlimits.RunLimitsUiState
import app.knotwork.android.presentation.ui.settings.runlimits.buildRunLimitsViewState
import app.knotwork.android.presentation.ui.taskmonitor.TaskItem
import app.knotwork.android.presentation.ui.taskmonitor.TaskMonitorScreenContent
import app.knotwork.android.presentation.ui.taskmonitor.TaskMonitorState
import app.knotwork.android.presentation.ui.taskmonitor.TaskStatus
import app.knotwork.android.presentation.ui.taskmonitor.TaskType
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.screens.help.HelpListCallbacks
import app.knotwork.design.screens.help.HelpListContent
import app.knotwork.design.screens.memory.MemoryCallbacks
import app.knotwork.design.screens.models.ModelsCallbacks
import app.knotwork.design.screens.monitoring.MonitoringCallbacks
import app.knotwork.design.screens.settings.AboutSettingsContent
import app.knotwork.design.screens.settings.GenerationSettingsContent
import app.knotwork.design.screens.settings.LocalSettingsHints
import app.knotwork.design.screens.settings.RunLimitsCallbacks
import app.knotwork.design.screens.settings.RunLimitsContent
import app.knotwork.design.screens.settings.SettingsHubContent
import app.knotwork.design.screens.settings.ToolsSettingsContent
import app.knotwork.design.screens.taskmonitor.TaskMonitorCallbacks
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Screenshots of app screens in a translation, at font scale 200 % in the light theme.
 *
 * The design system's baselines (`:catalog`) show its own words, but most of what a
 * screen says comes from this module: the strings the screen builds its state from. A
 * translation only shows whole here, where both meet — and a translation is where a
 * label outgrows its English and erases the text beside it (decisions §113.18: a
 * pipeline title squeezed to 0 dp; §113.20: tool names cut to «schedule_…»).
 *
 * Each screen is drawn by the code the app draws it with — its settings state builder,
 * or the `…ScreenContent` its ViewModel wrapper calls — from a hand-made state, without
 * the ViewModel: a ViewModel's repositories open a database Robolectric cannot load.
 * Build information, the clock and the time zone are fixed, so a baseline changes only
 * when a screen does.
 *
 * One subclass per shipped translation names its language — the resource qualifier in
 * its `@Config` and [language], the prefix of its baselines — and the [samples] a user
 * would have written in it. The ten screens and their states are shared, so every
 * language is checked on the same screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TranslatedScreensSnapshotTest {

    /**
     * The language as its resource qualifier spells it (`ru`, `es`): the subclass's
     * `@Config` qualifier, the language the About screen shows as chosen, and the
     * prefix of every baseline file name.
     */
    protected abstract val language: String

    /** Text the user or the agent wrote in [language], which no translation supplies. */
    protected abstract val samples: SampleTexts

    /**
     * What a person using the app in one language would have typed or been told: memory
     * entries, a chat name, task titles. A screen shows them beside its own labels, so
     * they are part of what a translation has to make room for.
     *
     * @property memories Four memory entries, newest first: pinned, extracted,
     *   saved by hand, and an older one.
     * @property chatName The chat the extracted memories were learned from.
     * @property researchTask Title of the running chat session.
     * @property queueStage The pipeline stage that session is in.
     * @property digestTask Title of the scheduled background task.
     * @property digestPrompt The prompt that scheduled task runs with.
     * @property reportTask Title of the failed background task.
     */
    data class SampleTexts(
        val memories: List<String>,
        val chatName: String,
        val researchTask: String,
        val queueStage: String,
        val digestTask: String,
        val digestPrompt: String,
        val reportTask: String,
    )

    @get:Rule
    val composeTestRule = createComposeRule()

    private val defaultTimeZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultTimeZone)
    }

    @Test
    fun settings_hub() = snapshot("settings_hub") {
        SettingsHubContent(
            state = buildHubViewState(SETTINGS).copy(
                subtitleVersion = FIXED_VERSION,
                subtitleChannel = FIXED_CHANNEL,
                subtitleBuildDate = FIXED_BUILD_DATE,
            ),
        )
    }

    @Test
    fun settings_tools() = snapshot("settings_tools") {
        ToolsSettingsContent(state = buildToolsViewState(SETTINGS))
    }

    @Test
    fun settings_generation() = snapshot("settings_generation") {
        GenerationSettingsContent(state = buildGenerationViewState(SETTINGS, LocalContext.current))
    }

    @Test
    fun settings_about() = snapshot("settings_about") {
        val state = SETTINGS.copy(appLanguage = language, supportedLanguages = SUPPORTED_LANGUAGES)
        AboutSettingsContent(
            state = buildAboutViewState(state, LocalContext.current).copy(
                versionLine = "v$FIXED_VERSION · $FIXED_CHANNEL · $FIXED_BUILD_DATE",
            ),
        )
    }

    @Test
    fun run_limits() = snapshot("run_limits") {
        RunLimitsContent(
            state = buildRunLimitsViewState(RunLimitsUiState()),
            callbacks = RunLimitsCallbacks(),
            onBack = {},
        )
    }

    @Test
    fun models() = snapshot("models") {
        ModelsScreenContent(uiState = modelsState(), callbacks = ModelsCallbacks(), snackbarHost = {})
    }

    @Test
    fun memory() = snapshot("memory") {
        MemoryScreenContent(
            uiState = memoryState(),
            callbacks = MemoryCallbacks(),
            snackbarHost = {},
            nowMillis = NOW,
        )
    }

    @Test
    fun task_monitor() = snapshot("task_monitor") {
        TaskMonitorScreenContent(uiState = taskMonitorState(), callbacks = TaskMonitorCallbacks())
    }

    @Test
    fun monitoring() = snapshot("monitoring") {
        MonitoringScreenContent(uiState = monitoringState(), callbacks = MonitoringCallbacks())
    }

    @Test
    fun help() = snapshot("help") {
        HelpListContent(
            state = helpListViewState(HelpUiState(documents = HELP_DOCUMENTS), alternativeId = null),
            strings = helpStrings(),
            callbacks = HelpListCallbacks(),
        )
    }

    // ─── States ──────────────────────────────────────────────────────────────

    private fun modelsState(): ModelsUiState {
        val active = LocalModel(
            id = 1,
            name = "gemma-4-E2B-it.litertlm",
            path = "/models/gemma-4-E2B-it.litertlm",
            size = 2_580_000_000L,
            isActive = true,
            supportsVision = true,
        )
        return ModelsUiState(
            downloadedModels = listOf(active),
            activeModel = active,
            verifiedModelIds = setOf(1L),
            performanceSummary = ModelPerformanceSummary(
                sampleCount = 12,
                avgTtftMs = 840,
                avgDecodeTokensPerSec = 14.6f,
                peakNativeHeapBytes = 1_930_000_000L,
            ),
        )
    }

    private fun memoryState(): MemoryUiState = MemoryUiState(
        memories = listOf(
            memory(1, samples.memories[0], HOUR, pinned = true),
            memory(2, samples.memories[1], 3 * HOUR),
            memory(3, samples.memories[2], 26 * HOUR, source = MemorySource.Manual),
            memory(4, samples.memories[3], 4 * DAY),
        ),
        totalBytes = 48_200L,
        lastCompactedAt = NOW - 2 * DAY,
        sessionNames = mapOf(CHAT_ID to samples.chatName),
    )

    private fun memory(
        id: Long,
        text: String,
        age: Long,
        pinned: Boolean = false,
        source: MemorySource = MemorySource.ChatSession(CHAT_ID),
    ) = MemoryChunk(
        id = id,
        text = text,
        embedding = FloatArray(0),
        timestamp = NOW - age,
        isPinned = pinned,
        source = source,
    )

    private fun taskMonitorState(): TaskMonitorState = TaskMonitorState(
        tasks = listOf(
            TaskItem(
                id = "session-1",
                title = UiText.Dynamic(samples.researchTask),
                status = TaskStatus.RUNNING,
                progress = 0.4f,
                type = TaskType.SESSION,
                pipelineStage = UiText.Dynamic(samples.queueStage),
            ),
            TaskItem(
                id = "work-1",
                title = UiText.Dynamic(samples.digestTask),
                status = TaskStatus.QUEUED,
                progress = null,
                type = TaskType.BACKGROUND_WORK,
                scheduled = ScheduledTaskLabel(
                    kind = ScheduledTaskKind.PERIODIC,
                    intervalHours = 6,
                    sessionId = null,
                    promptPreview = samples.digestPrompt,
                ),
            ),
            TaskItem(
                id = "work-2",
                title = UiText.Dynamic(samples.reportTask),
                status = TaskStatus.FAILED,
                progress = null,
                type = TaskType.BACKGROUND_WORK,
            ),
        ),
        isLoading = false,
        scheduledTaskCount = 1,
    )

    private fun monitoringState(): MonitoringUiState = MonitoringUiState(
        metrics = AgentMetrics(
            lastInferenceTimeMs = 2_340,
            tokensPerSecond = 14.6f,
            totalTokensProcessed = 18_420,
            totalExecutionTimeMs = 61_500,
            timePerNodeType = mapOf(NodeType.LITE_RT to 48_000L, NodeType.TOOL to 9_200L, NodeType.OUTPUT to 4_300L),
        ),
    )

    // ─── Capture ─────────────────────────────────────────────────────────────

    /**
     * Renders [content] as the app hosts its screens — Knotwork theme, the settings
     * hints — at 200 % font scale, and compares it with its baseline.
     */
    private fun snapshot(name: String, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            val context = LocalContext.current
            KnotworkTheme(darkTheme = false) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = FONT_SCALE),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = FONT_SCALE),
                    LocalSettingsHints provides SettingsHelpCatalog.controller(context),
                ) { content() }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = AppRoborazziOptions,
            filePath = "src/test/snapshots/${language}_${name}_fontscale200_light.png",
        )
    }

    private companion object {
        /**
         * Settings as a running app has them: the identity is loaded (without it the
         * screens show their loading placeholders, which no translation reaches).
         */
        val SETTINGS = SettingsUiState(
            identity = Identity(displayName = "Anonymous", deviceId = "a1b2c3d4", keystoreAvailable = true),
        )

        /**
         * The Help list's documents with fixed sizes. The real registry is generated from
         * `docs/`, and its line and section counts would make a documentation edit change
         * this baseline.
         */
        val HELP_DOCUMENTS = DocumentationLinks.DOCUMENTS.map { it.copy(lineCount = 500, sectionCount = 10) }

        /** The languages the interface ships, as the build's generated `LocaleConfig` lists them. */
        val SUPPORTED_LANGUAGES = listOf("en", "es", "ru")

        const val FONT_SCALE = 2f
        const val CHAT_ID = "chat-1"
        const val FIXED_VERSION = "0.14.0"
        const val FIXED_CHANNEL = "release"
        const val FIXED_BUILD_DATE = "2026.11.06"

        /** 2026-11-06T12:00:00Z. */
        const val NOW = 1_793_966_400_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
